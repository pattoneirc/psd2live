package io.github.psd2live.targets.spine

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.format.model.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A Spine 4.2 skeleton: JSON, a libGDX-style atlas and its pages.
 *
 * Spine has bones but no parameters. The rig is baked: one root bone, every visible mesh an unweighted mesh
 * attachment at its rest position, and
 * - each parameter one animation, `param/<id>`, one second long, whose time is the parameter's normalized
 *   value (minimum at 0, maximum at 1) and whose deform keys sit at the parameter's sampled keys. A runtime
 *   plays each on its own track with additive mixing and sets the track time from the parameter;
 * - each motion clip one animation sampled at `clip_fps`.
 * What summing parameters misses is measured and reported, as for every additive target.
 *
 * Deform keys interpolation can rebuild within `key_tolerance` pixels (default 0.25) are dropped.
 *
 * Masks become clipping attachments in a slot just before the masked one, ending at it: the outline of a
 * single mask mesh, or the convex hull of several masks' outlines, deformed with them. Spine clips by the
 * polygon rather than the texture's alpha; inverted masks are dropped.
 *
 * Settings: `clip_fps` (default 15), `clips` (default true), `key_tolerance` (default 0.25), `sample_pairs` (default 48).
 */
public class SpineTarget(private val evaluator: GeometryEvaluator) : ExportTarget {
	override val id: String = "spine"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "Spine 4.2 skeleton (JSON + atlas)"
	override val capabilities: CapabilityProfile = CapabilityProfile(
		bones = true, skinning = true, warpLattice = false, parameterGrid = 0, blendShapes = false, timeline = true,
		physics = PhysicsSupport.CONSTRAINT, blendModes = setOf(ColorBlend.NORMAL, ColorBlend.ADD, ColorBlend.MULTIPLY, ColorBlend.SCREEN),
		masks = MaskSupport.POLYGON, keyedDrawOrder = true, glue = false,
	)

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		require(ir.textures.pages.all { it.png.size > 0 }) { "Texture pages have no pixels" }
		val clipFps = options.float("clip_fps", 15f)
		require(clipFps in 1f..60f) { "clip_fps must be within 1..60" }
		val losses = ArrayList(CapabilityScan.scan(ir, capabilities, options, defaults = mapOf(
			Feature.PARAMETERS to Handling.BAKED, Feature.WARP_LATTICE to Handling.BAKED, Feature.BLEND_SHAPES to Handling.BAKED, Feature.GLUE to Handling.BAKED)))
		// Not yet lowered: pendulums to physics constraints.
		ir.physics.groups.forEach { losses += LossEntry(it.id, Feature.PHYSICS, Handling.DROPPED, note = "Pendulum physics is not converted to physics constraints") }
		val inverted = ir.meshes.filter { it.invertMask && it.maskedBy.isNotEmpty() }.map { it.id }.toSet()
		losses.replaceAll {
			when {
				it.feature != Feature.MASK -> it
				it.objectId in inverted -> it.copy(handling = Handling.DROPPED, note = "Spine clipping cannot invert a mask")
				else -> it.copy(handling = Handling.APPROXIMATED, note = "The mask clips by its outline polygon, not its texture alpha")
			}
		}
		val meshes = ir.meshes.filter { it.visible && it.geometry != null }
		val json = evaluator.open(ir).use { session ->
			val bake = ParameterBake(ir, session, linkedPairs = false)
			losses += bake.crossTermLosses(options.int("sample_pairs", 48))
			skeleton(ir, options, meshes, bake, session, clipFps, losses)
		}
		val pageNames = ir.textures.pages.indices.map { "${options.baseName}_$it.png" }
		val atlas = buildString {
			ir.textures.pages.forEachIndexed { index, page ->
				if (index > 0) append('\n')
				append(pageNames[index]).append('\n')
				append("\tsize: ${page.width}, ${page.height}\n\tfilter: Linear, Linear\n")
				append("page$index\n\tbounds: 0, 0, ${page.width}, ${page.height}\n")
			}
		}
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) {
				sink.write("${options.baseName}.json", json.encodeToByteArray())
				sink.write("${options.baseName}.atlas", atlas.encodeToByteArray())
				ir.textures.pages.forEachIndexed { index, page -> sink.write(pageNames[index], page.png.shared()) }
			}
		}
	}

	private fun skeleton(ir: RigIR, options: ExportOptions, meshes: List<Mesh>, bake: ParameterBake, session: GeometrySession,
	                     clipFps: Float, losses: MutableList<LossEntry>): String {
		// Skeleton space: origin at the canvas' bottom center, y up, in pixels.
		val cx = ir.canvas.width / 2f; val bottom = ir.canvas.height
		fun spine(points: FloatArray) = FloatArray(points.size) { if (it % 2 == 0) points[it] - cx else bottom - points[it] }
		fun delta(offsets: FloatArray) = FloatArray(offsets.size) { if (it % 2 == 0) offsets[it] else -offsets[it] }
		val rest = bake.rest
		val tolerance = options.float("key_tolerance", 0.25f)
		val ordered = meshes.withIndex().sortedWith(compareBy({ rest.drawOrder[it.value.id] ?: it.value.drawOrder }, { it.index })).map { it.value }
		val vertices = ordered.associate { it.id to spine(rest.positions.getValue(it.id)) }
		val clips = ordered.filter { it.maskedBy.isNotEmpty() && !it.invertMask }.mapNotNull { mesh ->
			Clipping.polygon(mesh.maskedBy.mapNotNull { id -> ir.meshes.firstOrNull { it.id == id } }, rest)?.let { mesh.id to it }
		}.toMap()
		clips.forEach { (mesh, clip) ->
			if (clip.hull) losses += LossEntry(mesh, Feature.MASK, Handling.APPROXIMATED, note = "Several masks or outlines clip by their convex hull")
		}
		/** Slot names in setup order: each clipped mesh follows its clipping slot. */
		fun slotsOf(meshOrder: List<String>) = meshOrder.flatMap { id -> if (id in clips) listOf("clip/$id", id) else listOf(id) }
		val slotNames = slotsOf(ordered.map { it.id })
		val slotIndex = slotNames.withIndex().associate { it.value to it.index }
		var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
		for (points in vertices.values) for (i in points.indices step 2) {
			minX = minOf(minX, points[i]); maxX = maxOf(maxX, points[i]); minY = minOf(minY, points[i + 1]); maxY = maxOf(maxY, points[i + 1])
		}
		if (ordered.isEmpty()) { minX = 0f; minY = 0f; maxX = 0f; maxY = 0f }
		fun color(mesh: Mesh, opacity: Float) = hex(mesh.multiply.red) + hex(mesh.multiply.green) + hex(mesh.multiply.blue) + hex(opacity)
		ordered.filter { it.screen != Rgb.Black }.forEach {
			losses += LossEntry(it.id, Feature.BLEND_MODE, Handling.DROPPED, note = "Screen color is not written")
		}
		val slots = ordered.flatMap { mesh ->
			listOfNotNull(
				clips[mesh.id]?.let { J.obj("name" to J.Str("clip/${mesh.id}"), "bone" to J.Str("root"), "attachment" to J.Str("clip/${mesh.id}")) },
				J.obj("name" to J.Str(mesh.id), "bone" to J.Str("root"), "attachment" to J.Str(mesh.id),
					"color" to J.Str(color(mesh, rest.opacity[mesh.id] ?: mesh.opacity)), "blend" to J.Str(blend(mesh.blend))))
		}
		val attachments = ordered.map { mesh ->
			val geometry = mesh.geometry!!
			mesh.id to J.obj(mesh.id to J.obj(
				"type" to J.Str("mesh"), "path" to J.Str("page${ir.textures.bindings[mesh.id] ?: mesh.page}"),
				"uvs" to J.floats(geometry.uvs.shared()),
				"triangles" to J.arr((0 until geometry.indices.size).map { J.Num(geometry.indices[it]) }),
				"vertices" to J.floats(vertices.getValue(mesh.id)),
			))
		} + clips.map { (mesh, clip) ->
			"clip/$mesh" to J.obj("clip/$mesh" to J.obj(
				"type" to J.Str("clipping"), "end" to J.Str(mesh), "vertexCount" to J.Num(clip.points.size),
				"vertices" to J.floats(spine(clip.positions(rest))),
			))
		}

		/** A deform key: the offsets trimmed to their nonzero span, or no vertices at all (the setup pose). */
		fun deformKey(time: Float, offsets: FloatArray): J {
			val first = offsets.indexOfFirst { abs(it) > 1e-4f }
			if (first < 0) return J.obj("time" to J.Num(time))
			val last = offsets.indexOfLast { abs(it) > 1e-4f }
			// Thousandths of a pixel are below any visible difference and keep the file small.
			val trimmed = FloatArray(last + 1 - first) { (offsets[first + it] * 1000f).roundToInt() / 1000f }
			return J.obj("time" to J.Num(time), "offset" to J.Num(first), "vertices" to J.floats(trimmed))
		}

		/** An animation from (time, pose geometry) samples: deform, slot alpha and draw order where they change. */
		fun animation(samples: List<Pair<Float, PoseGeometry>>): J {
			val deforms = ArrayList<Pair<String, J>>()
			val slotTimelines = ArrayList<Pair<String, J>>()
			for (mesh in ordered) {
				val restPositions = rest.positions.getValue(mesh.id)
				val offsets = samples.map { (time, pose) ->
					val posed = pose.positions[mesh.id] ?: restPositions
					time to delta(FloatArray(posed.size) { posed[it] - restPositions[it] })
				}
				if (offsets.any { (_, values) -> values.any { abs(it) > 1e-4f } }) {
					val kept = KeyReduction.reduce(offsets.map { it.first }, offsets.map { it.second }, tolerance)
					deforms += mesh.id to J.obj(mesh.id to J.obj("deform" to J.arr(kept.map { offsets[it] }.map { (time, values) -> deformKey(time, values) })))
				}
				val restOpacity = rest.opacity[mesh.id] ?: mesh.opacity
				if (samples.any { (_, pose) -> abs((pose.opacity[mesh.id] ?: restOpacity) - restOpacity) > 1e-3f })
					slotTimelines += mesh.id to J.obj("rgba" to J.arr(samples.map { (time, pose) ->
						J.obj("time" to J.Num(time), "color" to J.Str(color(mesh, pose.opacity[mesh.id] ?: restOpacity)))
					}))
			}
			for ((mesh, clip) in clips) {
				val restPoints = clip.positions(rest)
				val offsets = samples.map { (time, pose) ->
					val posed = clip.positions(pose, rest)
					time to delta(FloatArray(posed.size) { posed[it] - restPoints[it] })
				}
				if (offsets.any { (_, values) -> values.any { abs(it) > 1e-4f } }) {
					val kept = KeyReduction.reduce(offsets.map { it.first }, offsets.map { it.second }, tolerance)
					deforms += "clip/$mesh" to J.obj("clip/$mesh" to J.obj("deform" to J.arr(kept.map { offsets[it] }.map { (time, values) -> deformKey(time, values) })))
				}
			}
			val orders = samples.map { (time, pose) ->
				time to slotsOf(ordered.withIndex().sortedWith(compareBy({ pose.drawOrder[it.value.id] ?: rest.drawOrder[it.value.id] ?: it.value.drawOrder }, { it.index }))
					.map { it.value.id })
			}
			val entries = ArrayList<Pair<String, J>>()
			if (slotTimelines.isNotEmpty()) entries += "slots" to J.Obj(slotTimelines)
			if (deforms.isNotEmpty()) entries += "attachments" to J.obj("default" to J.Obj(deforms))
			if (orders.any { (_, order) -> order != slotNames }) entries += "drawOrder" to J.arr(orders.map { (time, order) ->
				val moved = order.withIndex().filter { (index, id) -> slotIndex.getValue(id) != index }.sortedBy { slotIndex.getValue(it.value) }
				if (moved.isEmpty()) J.obj("time" to J.Num(time))
				else J.obj("time" to J.Num(time), "offsets" to J.arr(moved.map { (index, id) ->
					J.obj("slot" to J.Str(id), "offset" to J.Num(index - slotIndex.getValue(id)))
				}))
			})
			return J.Obj(entries)
		}

		val animations = ArrayList<Pair<String, J>>()
		for (axis in bake.axes) {
			val p = axis.x
			val samples = axis.xKeys.indices.map { xi -> (axis.xKeys[xi] - p.min) / (p.max - p.min) to bake.samples.getValue(axis)[xi][0] }
			animations += "param/${p.id}" to animation(samples)
		}
		if (options.flag("clips", true)) {
			val used = HashSet<String>()
			for (clip in ir.clips) {
				var name = "clip/${clip.name}"; var n = 2
				while (!used.add(name)) name = "clip/${clip.name} ${n++}"
				val times = ClipSampler.frameTimes(clip, clipFps).let { if (clip.loop) it + clip.duration else it }
				animations += name to animation(times.map { t -> t to session.evaluate(ClipSampler.valuesAt(clip, if (clip.loop && t >= clip.duration) 0f else t)) })
			}
		}
		val skeleton = J.obj(
			"skeleton" to J.obj("hash" to J.Str(ContentHash.of(ir.meshes.map { it.id }, ir.parameters.map { it.id }).take(16)),
				"spine" to J.Str("4.2.0"), "x" to J.Num(minX), "y" to J.Num(minY), "width" to J.Num(maxX - minX), "height" to J.Num(maxY - minY),
				"fps" to J.Num(clipFps), "images" to J.Str("./")),
			"bones" to J.arr(listOf(J.obj("name" to J.Str("root")))),
			"slots" to J.arr(slots),
			"skins" to J.arr(listOf(J.obj("name" to J.Str("default"), "attachments" to J.Obj(attachments)))),
			"animations" to J.Obj(animations),
		)
		return StringBuilder().also { skeleton.write(it) }.toString()
	}

	private fun hex(value: Float) = "%02x".format((value.coerceIn(0f, 1f) * 255f).roundToInt())

	private fun blend(mode: ColorBlend) = when (mode) {
		ColorBlend.ADD, ColorBlend.ADD_GLOW, ColorBlend.ADD_PREMULTIPLIED -> "additive"
		ColorBlend.MULTIPLY, ColorBlend.MULTIPLY_PREMULTIPLIED -> "multiply"
		ColorBlend.SCREEN -> "screen"
		else -> "normal"
	}
}
