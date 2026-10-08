package io.github.psd2live.format.compile

import io.github.psd2live.format.model.*

/**
 * The generic step every export runs first: compares the IR with a target's capabilities and reports
 * each capability the target cannot hold, with the handling the export will apply. Targets append the
 * entries of their own lowering steps (and measured errors) after it.
 */
public object CapabilityScan {
	public fun scan(ir: RigIR, profile: CapabilityProfile, options: ExportOptions,
	                defaults: Map<Feature, Handling> = emptyMap()): List<LossEntry> {
		fun handling(feature: Feature, fallback: Handling) = options.handling[feature] ?: defaults[feature] ?: fallback
		val losses = ArrayList<LossEntry>()
		if (!profile.structure) {
			losses += LossEntry("*", Feature.STRUCTURE, Handling.BAKED, note = "Rendered to pixels; deformers, parameters and meshes are not kept")
			if (ir.physics.groups.isNotEmpty() && profile.physics == PhysicsSupport.NONE)
				losses += LossEntry("*", Feature.PHYSICS, handling(Feature.PHYSICS, Handling.BAKED), note = "Physics is simulated into the frames")
			return losses
		}
		if (ir.parameters.isNotEmpty() && profile.parameterGrid == 0)
			losses += LossEntry("*", Feature.PARAMETERS, handling(Feature.PARAMETERS, Handling.BAKED), note = "Parameters become sampled poses or animation")
		if (!profile.warpLattice) for (warp in ir.deformers.filterIsInstance<Deformer.Warp>())
			losses += LossEntry(warp.id, Feature.WARP_LATTICE, handling(Feature.WARP_LATTICE, Handling.BAKED), note = "Warp lattice is baked into per-vertex mesh keys")
		if (profile.parameterGrid > 0) {
			fun grid(id: String, axes: Int) {
				if (axes > profile.parameterGrid) losses += LossEntry(id, Feature.PARAMETER_GRID, handling(Feature.PARAMETER_GRID, Handling.APPROXIMATED),
					note = "$axes-axis keyform grid exceeds the target's ${profile.parameterGrid} axes")
			}
			ir.meshes.forEach { mesh -> mesh.offsets?.let { grid(mesh.id, it.axes.size) } }
			ir.deformers.forEach { deformer -> when (deformer) {
				is Deformer.Warp -> deformer.lattice?.let { grid(deformer.id, it.axes.size) }
				is Deformer.Rotation -> deformer.pivot?.let { grid(deformer.id, it.axes.size) }
			} }
		}
		if (!profile.blendShapes) {
			val owners = ir.meshes.filter { it.shapes.isNotEmpty() }.map { it.id } +
				ir.deformers.filter { (it as? Deformer.Warp)?.shapes?.isNotEmpty() == true || (it as? Deformer.Rotation)?.shapes?.isNotEmpty() == true }.map { it.id } +
				ir.parts.filter { it.shapes.isNotEmpty() }.map { it.id }
			owners.forEach { losses += LossEntry(it, Feature.BLEND_SHAPES, handling(Feature.BLEND_SHAPES, Handling.BAKED), note = "Additive blend shapes are baked into keyforms") }
		}
		for (mesh in ir.meshes) {
			if (mesh.blend !in profile.blendModes)
				losses += LossEntry(mesh.id, Feature.BLEND_MODE, handling(Feature.BLEND_MODE, Handling.APPROXIMATED),
					note = "Blend mode ${mesh.blend.name.lowercase()} falls back to normal")
			if (mesh.maskedBy.isNotEmpty() && profile.masks != MaskSupport.TEXTURE_ALPHA)
				losses += LossEntry(mesh.id, Feature.MASK, handling(Feature.MASK, if (profile.masks == MaskSupport.POLYGON) Handling.APPROXIMATED else Handling.DROPPED),
					note = if (profile.masks == MaskSupport.POLYGON) "Texture mask becomes a clipping polygon" else "Mask is not supported")
			if (Channel.DRAW_ORDER in mesh.channels && !profile.keyedDrawOrder)
				losses += LossEntry(mesh.id, Feature.KEYED_DRAW_ORDER, handling(Feature.KEYED_DRAW_ORDER, Handling.APPROXIMATED), note = "Keyed draw order is sampled")
		}
		if (!profile.glue) ir.glues.forEach {
			losses += LossEntry(it.id ?: "${it.meshA}+${it.meshB}", Feature.GLUE, handling(Feature.GLUE, Handling.BAKED), note = "Glue is baked into both meshes' keys")
		}
		if (ir.physics.groups.isNotEmpty() && profile.physics == PhysicsSupport.NONE) ir.physics.groups.forEach {
			losses += LossEntry(it.id, Feature.PHYSICS, handling(Feature.PHYSICS, Handling.DROPPED), note = "Physics is not supported by this target")
		}
		if (ir.clips.isNotEmpty() && !profile.timeline) ir.clips.forEach {
			losses += LossEntry(it.id, Feature.TIMELINE, handling(Feature.TIMELINE, Handling.DROPPED), note = "Motion clips are not written")
		}
		ir.textures.pages.forEachIndexed { index, page ->
			if (maxOf(page.width, page.height) > profile.maxTextureSize || (profile.powerOfTwo && (!pow2(page.width) || !pow2(page.height))))
				losses += LossEntry("texture:$index", Feature.TEXTURE_SIZE, handling(Feature.TEXTURE_SIZE, Handling.APPROXIMATED),
					note = "Page ${page.width}x${page.height} is repacked to the target's limits")
		}
		return losses
	}

	private fun pow2(value: Int) = value > 0 && value and (value - 1) == 0
}

/** Evaluates motion clips: parameter values at a time, with each curve's segment semantics. */
public object ClipSampler {
	/** Values of every curve in [clip] at [time] seconds; parameters without a curve are absent. */
	public fun valuesAt(clip: Clip, time: Float): Map<String, Float> {
		val t = if (clip.loop && clip.duration > 0f) ((time % clip.duration) + clip.duration) % clip.duration else time.coerceIn(0f, clip.duration)
		return clip.curves.associate { it.parameter to valueAt(it, t) }
	}

	public fun valueAt(curve: Curve, time: Float): Float {
		if (curve.segments.isEmpty() || time <= curve.startTime) return curve.startValue
		var fromTime = curve.startTime
		var fromValue = curve.startValue
		for (segment in curve.segments) {
			if (time <= segment.time) {
				val span = segment.time - fromTime
				val u = if (span <= 0f) 1f else (time - fromTime) / span
				return when (segment) {
					is CurveSegment.Linear -> fromValue + (segment.value - fromValue) * u
					is CurveSegment.Stepped -> if (time >= segment.time) segment.value else fromValue
					is CurveSegment.InverseStepped -> if (time > fromTime) segment.value else fromValue
					// Time handles are restricted to the segment, so the curve parameter advances with time.
					is CurveSegment.Bezier -> cubic(fromValue, segment.c1Value, segment.c2Value, segment.value, u)
				}
			}
			fromTime = segment.time
			fromValue = segment.value
		}
		return fromValue
	}

	private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, u: Float): Float {
		val v = 1f - u
		return v * v * v * p0 + 3f * v * v * u * p1 + 3f * v * u * u * p2 + u * u * u * p3
	}

	/** Frame times of a clip at [fps]: [0, duration) for a loop, [0, duration] for a one-shot. */
	public fun frameTimes(clip: Clip, fps: Float): List<Float> {
		require(fps > 0f && fps.isFinite()) { "FPS must be positive" }
		val count = (clip.duration * fps).toInt().coerceAtLeast(1)
		return List(if (clip.loop) count else count + 1) { it / fps }
	}
}

/** An ARGB image (non-premultiplied, 0xAARRGGBB per pixel), row by row. */
public class RasterImage(public val width: Int, public val height: Int, public val argb: IntArray) {
	init { require(width > 0 && height > 0 && argb.size == width * height) { "Raster size mismatch" } }
}

/** [image] with its top-left pixel at ([left], [top]) of a larger frame. */
public class PlacedRaster(public val left: Int, public val top: Int, public val image: RasterImage) {
	/** This raster cut to the bounding box of its pixels with any alpha, or null when every pixel is transparent. */
	public fun trimmed(): PlacedRaster? {
		val width = image.width; val argb = image.argb
		var left = width; var top = image.height; var right = -1; var bottom = -1
		for (y in 0 until image.height) {
			val row = y * width
			var first = -1; var last = -1
			for (x in 0 until width) if (argb[row + x] ushr 24 != 0) { if (first < 0) first = x; last = x }
			if (first < 0) continue
			if (first < left) left = first; if (last > right) right = last
			if (y < top) top = y; bottom = y
		}
		if (right < 0) return null
		if (left == 0 && top == 0 && right == width - 1 && bottom == image.height - 1) return this
		val w = right - left + 1; val h = bottom - top + 1
		val out = IntArray(w * h)
		for (y in 0 until h) System.arraycopy(argb, (top + y) * width + left, out, y * w, w)
		return PlacedRaster(this.left + left, this.top + top, RasterImage(w, h, out))
	}
}

/** The canvas rectangle to render and the output size in pixels. [background] is ARGB; 0 is transparent. */
public data class FrameSpec(
	val left: Float, val top: Float, val width: Float, val height: Float,
	val outputWidth: Int, val outputHeight: Int, val background: Int = 0,
) {
	init { require(width > 0f && height > 0f && outputWidth > 0 && outputHeight > 0) { "Empty frame" } }

	public companion object {
		/** The whole canvas, fitted into [maxSize] pixels on its longer side. */
		public fun canvas(ir: RigIR, maxSize: Int, background: Int = 0): FrameSpec {
			val scale = maxSize / maxOf(ir.canvas.width, ir.canvas.height)
			return FrameSpec(0f, 0f, ir.canvas.width, ir.canvas.height,
				(ir.canvas.width * scale).toInt().coerceAtLeast(1), (ir.canvas.height * scale).toInt().coerceAtLeast(1), background)
		}
	}
}

/**
 * Renders an IR rig. The host supplies the implementation (the evaluator), so raster targets stay free
 * of any particular renderer. A session keeps physics state between consecutive frames.
 */
public interface FrameRenderer {
	public fun open(ir: RigIR, physics: Boolean): FrameSession
}

public interface FrameSession : AutoCloseable {
	/** Whether this session advances physics between frames. */
	public val simulatesPhysics: Boolean
	/**
	 * Renders the rig at [parameters] (missing ones at their defaults), [deltaSeconds] after the previous frame.
	 * [meshes] limits drawing to those mesh ids (masks still apply); null draws every visible mesh.
	 */
	public fun render(parameters: Map<String, Float>, deltaSeconds: Float, frame: FrameSpec, meshes: Set<String>? = null): RasterImage

	/**
	 * Each of [meshes] drawn alone at [parameters] (its masks still apply), pixel for pixel as [render] with
	 * only that mesh and no time step draws it, trimmed to its drawn pixels ([PlacedRaster.trimmed]); null
	 * where nothing is drawn. A host may render the meshes in parallel and only over the pixels each reaches.
	 */
	public fun renderEach(parameters: Map<String, Float>, frame: FrameSpec, meshes: List<String>): List<PlacedRaster?> =
		meshes.map { PlacedRaster(0, 0, render(parameters, 0f, frame, setOf(it))).trimmed() }

	/**
	 * Consecutive frames, each at its parameters and the seconds since the previous one, as one [render] call
	 * after another draws them. A host may draw them in parallel once physics has been stepped in order.
	 */
	public fun renderSequence(frames: List<Pair<Map<String, Float>, Float>>, frame: FrameSpec): List<RasterImage> =
		frames.map { (parameters, delta) -> render(parameters, delta, frame) }
}

/** Evaluated geometry of a rig at one pose: mesh vertices in canvas pixels (y down), opacity and draw order. */
public class PoseGeometry(
	public val positions: Map<String, FloatArray>,
	public val opacity: Map<String, Float>,
	public val drawOrder: Map<String, Float>,
)

/**
 * Evaluates an IR rig's geometry at parameter poses. The host supplies the implementation, so targets that
 * bake deformation into their own representation stay free of any particular evaluator.
 */
public interface GeometryEvaluator {
	public fun open(ir: RigIR): GeometrySession
}

public interface GeometrySession : AutoCloseable {
	/** The rig at [parameters]; parameters left out sit at their defaults. */
	public fun evaluate(parameters: Map<String, Float>): PoseGeometry
}
