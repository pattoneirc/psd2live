package io.github.psd2live.targets.dragonbones

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A DragonBones 5.5 skeleton (`_ske.json`) with one texture atlas per page (`_tex_<i>.json` and `.png`).
 *
 * DragonBones has bones but no parameters, so the rig is baked the way the Spine target bakes it: one root
 * bone, every visible mesh an unweighted mesh display at its rest position (origin at the canvas' bottom
 * center, y down), and
 * - each parameter one animation, `param/<id>`, one second long, whose time is the parameter's normalized
 *   value; its deform (ffd) frames sit at the parameter's keys rounded to the armature's frames. Hosts
 *   play each on its own layer and seek it from the parameter;
 * - each clip one animation sampled every frame, with frames linear interpolation rebuilds dropped.
 * Opacity is keyed in whole percents. The runtime addresses each animation's deform data with 16-bit
 * offsets, so keys of an animation over that limit are dropped at doubling tolerances until it fits, and
 * the tolerance used is reported. What summing parameters misses is measured and reported.
 *
 * Settings: `frame_rate` (default 60), `clips` (default true), `key_tolerance` (default 0.25 px),
 * `sample_pairs` (default 48).
 */
public class DragonBonesTarget(private val evaluator: GeometryEvaluator) : ExportTarget {
	override val id: String = "dragonbones"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "DragonBones 5.5 skeleton (JSON + atlas)"
	override val settings: List<TargetSetting> = listOf(
		TargetSetting.CLIPS, TargetSetting.Number("frame_rate", 60.0, 1.0, 120.0, 1.0, 0), TargetSetting.KEY_TOLERANCE, TargetSetting.SAMPLE_PAIRS,
	)
	override val capabilities: CapabilityProfile = CapabilityProfile(
		bones = true, skinning = true, timeline = true,
		blendModes = setOf(ColorBlend.NORMAL, ColorBlend.ADD, ColorBlend.ADD_PREMULTIPLIED, ColorBlend.MULTIPLY, ColorBlend.MULTIPLY_PREMULTIPLIED,
			ColorBlend.SCREEN, ColorBlend.DARKEN, ColorBlend.LIGHTEN, ColorBlend.OVERLAY, ColorBlend.HARD_LIGHT),
		keyedDrawOrder = true,
	)

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		require(ir.textures.pages.all { it.png.size > 0 }) { "Texture pages have no pixels" }
		val frameRate = options.int("frame_rate", 60)
		require(frameRate in 1..120) { "frame_rate must be within 1..120" }
		val losses = ArrayList(CapabilityScan.scan(ir, capabilities, options, defaults = mapOf(
			Feature.PARAMETERS to Handling.BAKED, Feature.WARP_LATTICE to Handling.BAKED, Feature.BLEND_SHAPES to Handling.BAKED, Feature.GLUE to Handling.BAKED)))
		val meshes = ir.meshes.filter { it.visible && it.geometry != null }
		val name = options.baseName
		val skeleton = evaluator.open(ir).use { session ->
			val bake = ParameterBake(ir, session, linkedPairs = false)
			losses += bake.crossTermLosses(options.int("sample_pairs", 48))
			Skeleton(ir, options, meshes, bake, session, frameRate, losses).json(name)
		}
		val atlases = ir.textures.pages.mapIndexed { index, page ->
			J.obj("name" to J.Str(name), "imagePath" to J.Str("${name}_tex_$index.png"), "width" to J.Num(page.width), "height" to J.Num(page.height),
				"SubTexture" to J.arr(listOf(J.obj("name" to J.Str("page$index"), "x" to J.Num(0), "y" to J.Num(0),
					"width" to J.Num(page.width), "height" to J.Num(page.height)))))
		}
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) {
				sink.write("${name}_ske.json", skeleton.encodeToByteArray())
				ir.textures.pages.forEachIndexed { index, page ->
					sink.write("${name}_tex_$index.json", StringBuilder().also(atlases[index]::write).toString().encodeToByteArray())
					sink.write("${name}_tex_$index.png", page.png.shared())
				}
			}
		}
	}

	private companion object {
		/** Deform floats one animation can hold: the runtime's frame offsets are signed 16-bit. */
		const val DEFORM_BUDGET = 32000
	}

	private class Skeleton(
		val ir: RigIR, val options: ExportOptions, meshes: List<Mesh>, val bake: ParameterBake, val session: GeometrySession,
		val frameRate: Int, val losses: MutableList<LossEntry>,
	) {
		val rest = bake.rest
		val tolerance = options.float("key_tolerance", 0.25f)
		val ordered = meshes.withIndex().sortedWith(compareBy({ rest.drawOrder[it.value.id] ?: it.value.drawOrder }, { it.index })).map { it.value }
		val slotIndex = ordered.withIndex().associate { it.value.id to it.index }
		// Armature space: origin at the canvas' bottom center, y down, in pixels.
		val cx = ir.canvas.width / 2f
		val bottom = ir.canvas.height
		fun place(points: FloatArray) = FloatArray(points.size) { if (it % 2 == 0) points[it] - cx else points[it] - bottom }

		fun percent(value: Float) = (value.coerceIn(0f, 1f) * 100f).roundToInt()

		fun color(mesh: Mesh, opacity: Float): J = J.obj("aM" to J.Num(percent(opacity)),
			"rM" to J.Num(percent(mesh.multiply.red)), "gM" to J.Num(percent(mesh.multiply.green)), "bM" to J.Num(percent(mesh.multiply.blue)))

		fun blend(mode: ColorBlend) = when (mode) {
			ColorBlend.ADD, ColorBlend.ADD_GLOW, ColorBlend.ADD_PREMULTIPLIED -> "add"
			ColorBlend.MULTIPLY, ColorBlend.MULTIPLY_PREMULTIPLIED -> "multiply"
			ColorBlend.SCREEN -> "screen"
			ColorBlend.DARKEN -> "darken"
			ColorBlend.LIGHTEN -> "lighten"
			ColorBlend.OVERLAY -> "overlay"
			ColorBlend.HARD_LIGHT -> "hardlight"
			else -> "normal"
		}

		/** Frames of [durations] in frames between consecutive keys; the last frame holds. */
		fun durations(frames: List<Int>) = frames.indices.map { if (it + 1 < frames.size) frames[it + 1] - frames[it] else 0 }

		/** A deform frame: the offsets trimmed to their nonzero span, or none (the setup pose). */
		fun deformFrame(duration: Int, offsets: FloatArray, last: Boolean): J {
			val entries = mutableListOf<Pair<String, J>>("duration" to J.Num(duration))
			if (!last) entries += "tweenEasing" to J.Num(0)
			val first = offsets.indexOfFirst { abs(it) > 1e-4f }
			if (first >= 0) {
				val end = offsets.indexOfLast { abs(it) > 1e-4f }
				entries += "offset" to J.Num(first)
				entries += "vertices" to J.floats(FloatArray(end + 1 - first) { (offsets[first + it] * 1000f).roundToInt() / 1000f })
			}
			return J.Obj(entries)
		}

		/** An animation from (frame, pose) samples: deform, slot color and z order where they change. */
		fun animation(name: String, duration: Int, playTimes: Int, samples: List<Pair<Int, PoseGeometry>>): J {
			val ffd = ArrayList<J>(); val slots = ArrayList<J>()
			val frames = samples.map { it.first }
			val times = frames.map { it.toFloat() }
			val moving = ordered.mapNotNull { mesh ->
				val restPositions = rest.positions.getValue(mesh.id)
				val offsets = samples.map { (_, pose) -> val posed = pose.positions[mesh.id] ?: restPositions; FloatArray(posed.size) { posed[it] - restPositions[it] } }
				if (offsets.any { values -> values.any { abs(it) > 1e-4f } }) mesh to offsets else null
			}
			// The runtime addresses an animation's deform floats (every frame holds all its mesh's vertices) with
			// 16-bit offsets: past the budget, keys are dropped at doubling tolerances until the animation fits.
			fun reduce(t: Float) = moving.map { (_, offsets) -> KeyReduction.reduce(times, offsets, t) }
			fun floats(keys: List<List<Int>>) = moving.indices.sumOf { keys[it].size * moving[it].second[0].size }
			var used = tolerance
			var kept = reduce(used)
			if (floats(kept) > DEFORM_BUDGET) {
				// Double until it fits, then bisect toward the smallest tolerance that does.
				var low = used
				while (floats(kept) > DEFORM_BUDGET && used < 512f) { low = used; used *= 2f; kept = reduce(used) }
				repeat(8) {
					val middle = (low + used) / 2f
					val candidate = reduce(middle)
					if (floats(candidate) <= DEFORM_BUDGET) { used = middle; kept = candidate } else low = middle
				}
			}
			fun floats() = floats(kept)
			if (used > tolerance) losses += LossEntry(name, Feature.PARAMETERS, Handling.APPROXIMATED, used,
				"Deform keys within ${"%.1f".format(used)} px are dropped to fit DragonBones' per-animation data limit")
			if (floats() > DEFORM_BUDGET) losses += LossEntry(name, Feature.STRUCTURE, Handling.APPROXIMATED,
				note = "The animation's deform data exceeds what the DragonBones runtime addresses")
			for ((index, entry) in moving.withIndex()) {
				val (mesh, offsets) = entry
				val keys = kept[index]
				val keptDurations = durations(keys.map { frames[it] })
				ffd += J.obj("name" to J.Str(mesh.id), "slot" to J.Str(mesh.id), "frame" to J.arr(keys.mapIndexed { i, k ->
					deformFrame(keptDurations[i], offsets[k], i == keys.size - 1)
				}))
			}
			for (mesh in ordered) {
				val restOpacity = rest.opacity[mesh.id] ?: mesh.opacity
				val opacities = samples.map { (_, pose) -> pose.opacity[mesh.id] ?: restOpacity }
				if (opacities.any { percent(it) != percent(restOpacity) }) {
					val all = durations(frames)
					slots += J.obj("name" to J.Str(mesh.id), "colorFrame" to J.arr(opacities.mapIndexed { i, opacity ->
						J.obj(*listOfNotNull("duration" to J.Num(all[i]), if (i + 1 < opacities.size) "tweenEasing" to J.Num(0) else null,
							"value" to color(mesh, opacity)).toTypedArray())
					}))
				}
			}
			val orders = samples.map { (_, pose) ->
				ordered.withIndex().sortedWith(compareBy({ pose.drawOrder[it.value.id] ?: rest.drawOrder[it.value.id] ?: it.value.drawOrder }, { it.index })).map { it.value.id }
			}
			val entries = mutableListOf<Pair<String, J>>("name" to J.Str(name), "duration" to J.Num(duration), "playTimes" to J.Num(playTimes))
			if (ffd.isNotEmpty()) entries += "ffd" to J.arr(ffd)
			if (slots.isNotEmpty()) entries += "slot" to J.arr(slots)
			if (orders.any { it != ordered.map(Mesh::id) }) {
				val all = durations(frames)
				entries += "zOrder" to J.obj("frame" to J.arr(orders.mapIndexed { i, order ->
					// Moved slots by their setup index with the offset to their place; the rest keep their order.
					val moved = order.withIndex().filter { (index, id) -> slotIndex.getValue(id) != index }.sortedBy { slotIndex.getValue(it.value) }
					J.obj("duration" to J.Num(all[i]), "zOrder" to J.arr(moved.flatMap { (index, id) -> listOf(J.Num(slotIndex.getValue(id)), J.Num(index - slotIndex.getValue(id))) }))
				}))
			}
			return J.Obj(entries)
		}

		fun json(name: String): String {
			val slots = ordered.map { mesh ->
				J.obj("name" to J.Str(mesh.id), "parent" to J.Str("root"), "color" to color(mesh, rest.opacity[mesh.id] ?: mesh.opacity),
					"blendMode" to J.Str(blend(mesh.blend)))
			}
			ordered.filter { it.screen != Rgb.Black }.forEach { losses += LossEntry(it.id, Feature.BLEND_MODE, Handling.DROPPED, note = "Screen color is not written") }
			val displays = ordered.map { mesh ->
				val g = mesh.geometry!!
				val page = ir.textures.bindings[mesh.id] ?: mesh.page
				J.obj("name" to J.Str(mesh.id), "display" to J.arr(listOf(J.obj(
					"type" to J.Str("mesh"), "name" to J.Str(mesh.id), "path" to J.Str("page$page"),
					"vertices" to J.floats(place(rest.positions.getValue(mesh.id))),
					"uvs" to J.floats(g.uvs.shared()),
					"triangles" to J.arr((0 until g.indices.size).map { J.Num(g.indices[it]) }),
				))))
			}
			var quantized = false
			val animations = ArrayList<J>()
			for (axis in bake.axes) {
				val p = axis.x
				// Keys rounded to frames; two keys on one frame keep the later.
				val byFrame = LinkedHashMap<Int, PoseGeometry>()
				axis.xKeys.indices.forEach { xi ->
					val exact = (axis.xKeys[xi] - p.min) / (p.max - p.min) * frameRate
					if (abs(exact - exact.roundToInt()) > 1e-3f) quantized = true
					byFrame[exact.roundToInt()] = bake.samples.getValue(axis)[xi][0]
				}
				animations += animation("param/${p.id}", frameRate, 1, byFrame.entries.sortedBy { it.key }.map { it.key to it.value })
			}
			if (quantized) losses += LossEntry("*", Feature.PARAMETERS, Handling.APPROXIMATED,
				note = "Parameter keys are rounded to 1/$frameRate of each parameter's range")
			if (options.flag("clips", true)) {
				val used = HashSet<String>()
				for (clip in ir.clips) {
					var animationName = "clip/${clip.name}"; var n = 2
					while (!used.add(animationName)) animationName = "clip/${clip.name} ${n++}"
					val total = (clip.duration * frameRate).roundToInt().coerceAtLeast(1)
					val samples = (0..total).map { f ->
						val t = f / frameRate.toFloat()
						f to session.evaluate(ClipSampler.valuesAt(clip, if (clip.loop && t >= clip.duration) 0f else t))
					}
					animations += animation(animationName, total, if (clip.loop) 0 else 1, samples)
				}
			}
			val document = J.obj(
				"frameRate" to J.Num(frameRate), "name" to J.Str(name), "version" to J.Str("5.5"), "compatibleVersion" to J.Str("5.5"),
				"armature" to J.arr(listOf(J.obj(
					"type" to J.Str("Armature"), "frameRate" to J.Num(frameRate), "name" to J.Str(name),
					"bone" to J.arr(listOf(J.obj("name" to J.Str("root")))),
					"slot" to J.arr(slots),
					"skin" to J.arr(listOf(J.obj("name" to J.Str(""), "slot" to J.arr(displays)))),
					"animation" to J.arr(animations),
				))),
			)
			return StringBuilder().also(document::write).toString()
		}
	}
}
