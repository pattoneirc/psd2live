package io.github.psd2live.targets.gltf

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * glTF 2.0 binary (`.glb`): every visible mesh becomes a flat, unlit, textured mesh in the rig's rest pose,
 * stacked toward the viewer in draw order. Each parameter key becomes a morph target per mesh it moves,
 * sampled by the host's evaluator with the other parameters at their defaults; a parameter value maps to
 * weights falling linearly between its neighbouring keys (`extras.psd2live.parameters` lists the keys,
 * `extras.targetNames` names each target `id=key`). Clips become weight animations sampled at `clip_fps`
 * with redundant keys dropped. What summing parameters misses is measured and reported.
 *
 * Units are meters at `pixels_per_meter` (default 1000), y up, the canvas' bottom center at the origin.
 * Settings: `pixels_per_meter`, `clip_fps` (default 30), `clips` (default true), `sample_pairs` (default 48).
 */
public class GltfTarget(private val evaluator: GeometryEvaluator) : ExportTarget {
	override val id: String = "gltf"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "glTF 2.0 binary with morph targets (.glb)"
	override val capabilities: CapabilityProfile = CapabilityProfile(parameterGrid = 1, timeline = true)
	override val settings: List<TargetSetting> = listOf(
		TargetSetting.CLIPS, TargetSetting.Number("clip_fps", 30.0, 1.0, 120.0, 1.0, 0),
		TargetSetting.Number("pixels_per_meter", 1000.0, 1.0, 100000.0, 100.0, 0), TargetSetting.SAMPLE_PAIRS,
	)

	/** Layers stack this far apart, in meters, so depth sorting keeps the draw order. */
	private val layerGap = 0.0005f

	private class Target(val parameter: Int, val key: Int, val name: String, val deltas: FloatArray)

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		require(ir.textures.pages.all { it.png.size > 0 }) { "Texture pages have no pixels" }
		val scale = 1f / (options.setting("pixels_per_meter")?.toFloatOrNull()?.takeIf { it > 0f } ?: 1000f)
		val fps = options.setting("clip_fps")?.toFloatOrNull()?.takeIf { it > 0f } ?: 30f
		val losses = ArrayList(CapabilityScan.scan(ir, capabilities, options,
			defaults = mapOf(Feature.WARP_LATTICE to Handling.BAKED, Feature.BLEND_SHAPES to Handling.BAKED, Feature.GLUE to Handling.BAKED)))
		val meshes = ir.meshes.filter { it.visible && it.geometry != null && it.page >= 0 }
		val glb = evaluator.open(ir).use { session ->
			val bake = ParameterBake(ir, session, linkedPairs = false)
			losses += bake.crossTermLosses(options.int("sample_pairs", 48))
			val clips = if (options.flag("clips", true)) ir.clips else emptyList()
			Writer(ir, meshes, bake, scale, fps, clips).glb()
		}
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) = sink.write("${options.baseName}.glb", glb)
		}
	}

	private inner class Writer(
		val ir: RigIR, val meshes: List<Mesh>, val bake: ParameterBake, val scale: Float, val fps: Float, val clips: List<Clip>,
	) {
		private val binary = ByteArrayOutputStream()
		private val bufferViews = ArrayList<J>()
		private val accessors = ArrayList<J>()
		private val axes = bake.axes
		private val parameterIndex = ir.parameters.withIndex().associate { it.value.id to it.index }

		/** Appends [bytes] aligned to 4 as a buffer view; returns its index. */
		private fun view(bytes: ByteArray, target: Int? = null): Int {
			while (binary.size() % 4 != 0) binary.write(0)
			val offset = binary.size()
			binary.write(bytes)
			bufferViews += J.obj(*listOfNotNull("buffer" to J.Num(0), "byteOffset" to J.Num(offset), "byteLength" to J.Num(bytes.size),
				target?.let { "target" to J.Num(it) }).toTypedArray())
			return bufferViews.size - 1
		}

		private fun floats(values: FloatArray, components: Int, type: String, bounds: Boolean, target: Int? = 34962): Int {
			val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
			values.forEach(buffer::putFloat)
			val view = view(buffer.array(), target)
			val count = values.size / components
			val entries = mutableListOf<Pair<String, J>>("bufferView" to J.Num(view), "componentType" to J.Num(5126), "count" to J.Num(count), "type" to J.Str(type))
			if (bounds) {
				val lo = FloatArray(components) { Float.MAX_VALUE }; val hi = FloatArray(components) { -Float.MAX_VALUE }
				for (i in values.indices) { lo[i % components] = min(lo[i % components], values[i]); hi[i % components] = max(hi[i % components], values[i]) }
				entries += "min" to J.floats(lo); entries += "max" to J.floats(hi)
			}
			accessors += J.Obj(entries)
			return accessors.size - 1
		}

		private fun indices(values: Ints): Int {
			val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
			for (i in 0 until values.size) buffer.putInt(values[i])
			val view = view(buffer.array(), 34963)
			accessors += J.obj("bufferView" to J.Num(view), "componentType" to J.Num(5125), "count" to J.Num(values.size), "type" to J.Str("SCALAR"))
			return accessors.size - 1
		}

		/** Canvas pixels (y down) to meters (y up), bottom center at the origin, at depth [z]. */
		private fun place(points: FloatArray, z: Float): FloatArray = FloatArray(points.size / 2 * 3) { i ->
			val v = i / 3
			when (i % 3) {
				0 -> (points[v * 2] - ir.canvas.width / 2f) * scale
				1 -> (ir.canvas.height - points[v * 2 + 1]) * scale
				else -> z
			}
		}

		/** The targets of [mesh]: every key, other than the default, of every parameter that moves it. */
		private fun targets(mesh: Mesh): List<Target> = axes.flatMap { axis ->
			axis.xKeys.indices.filter { axis.xKeys[it] != axis.x.default }.mapNotNull { xi ->
				val offsets = bake.offsets(axis, xi, 0, mesh.id)
				if (offsets.all { abs(it) < 1e-3f }) return@mapNotNull null
				val deltas = FloatArray(offsets.size / 2 * 3) { i -> when (i % 3) { 0 -> offsets[i / 3 * 2] * scale; 1 -> -offsets[i / 3 * 2 + 1] * scale; else -> 0f } }
				Target(axes.indexOf(axis), xi, "${axis.x.id}=${axis.xKeys[xi]}", deltas)
			}
		}

		/** Weights of [targets] when the parameters are at [values]: hats over each parameter's keys. */
		private fun weights(targets: List<Target>, values: Map<String, Float>): FloatArray = FloatArray(targets.size) { t ->
			val axis = axes[targets[t].parameter]
			val keys = axis.xKeys
			val v = (values[axis.x.id] ?: axis.x.default).coerceIn(keys.first(), keys.last())
			val k = targets[t].key
			when {
				keys[k] == v -> 1f
				k > 0 && v > keys[k - 1] && v < keys[k] -> (v - keys[k - 1]) / (keys[k] - keys[k - 1])
				k < keys.size - 1 && v > keys[k] && v < keys[k + 1] -> (keys[k + 1] - v) / (keys[k + 1] - keys[k])
				else -> 0f
			}
		}

		fun glb(): ByteArray {
			val rest = bake.rest
			// Back to front by rest draw order, then rig order.
			val stacked = meshes.withIndex().sortedWith(compareBy({ rest.drawOrder[it.value.id] ?: it.value.drawOrder }, { it.index })).map { it.value }
			val images = ir.textures.pages.map { page -> J.obj("bufferView" to J.Num(view(page.png.shared())), "mimeType" to J.Str("image/png")) }
			val gltfMeshes = ArrayList<J>(); val materials = ArrayList<J>(); val nodes = ArrayList<J>()
			val meshTargets = ArrayList<List<Target>>()
			for ((layer, mesh) in stacked.withIndex()) {
				val g = mesh.geometry!!
				val positions = rest.positions[mesh.id] ?: g.positions.shared()
				val targets = targets(mesh)
				meshTargets += targets
				val position = floats(place(positions, layer * layerGap), 3, "VEC3", bounds = true)
				val uv = floats(g.uvs.shared(), 2, "VEC2", bounds = false)
				val targetAccessors = targets.map { J.obj("POSITION" to J.Num(floats(it.deltas, 3, "VEC3", bounds = true))) }
				val opacity = rest.opacity[mesh.id] ?: mesh.opacity
				materials += J.obj(
					"name" to J.Str(mesh.id),
					"pbrMetallicRoughness" to J.obj(
						"baseColorTexture" to J.obj("index" to J.Num(ir.textures.bindings[mesh.id] ?: mesh.page)),
						"baseColorFactor" to J.vec(mesh.multiply.red, mesh.multiply.green, mesh.multiply.blue, opacity),
						"metallicFactor" to J.Num(0), "roughnessFactor" to J.Num(1)),
					"alphaMode" to J.Str("BLEND"), "doubleSided" to J.Bool(true),
					"extensions" to J.obj("KHR_materials_unlit" to J.obj()))
				val primitive = J.obj(*listOfNotNull(
					"attributes" to J.obj("POSITION" to J.Num(position), "TEXCOORD_0" to J.Num(uv)),
					"indices" to J.Num(indices(g.indices)), "material" to J.Num(materials.size - 1),
					targetAccessors.takeIf { it.isNotEmpty() }?.let { "targets" to J.arr(it) }).toTypedArray())
				gltfMeshes += J.obj(*listOfNotNull("name" to J.Str(mesh.id), "primitives" to J.arr(listOf(primitive)),
					targets.takeIf { it.isNotEmpty() }?.let { "weights" to J.floats(FloatArray(it.size)) },
					targets.takeIf { it.isNotEmpty() }?.let { t -> "extras" to J.obj("targetNames" to J.arr(t.map { J.Str(it.name) })) }).toTypedArray())
				nodes += J.obj("name" to J.Str(mesh.id), "mesh" to J.Num(gltfMeshes.size - 1))
			}
			nodes += J.obj("name" to J.Str("rig"), "children" to J.arr(stacked.indices.map { J.Num(it) }))
			val animations = clips.mapNotNull { clip -> animation(clip, meshTargets) }
			val parameters = axes.map { axis ->
				J.obj("id" to J.Str(axis.x.id), "name" to J.Str(axis.x.name), "min" to J.Num(axis.x.min), "max" to J.Num(axis.x.max),
					"default" to J.Num(axis.x.default), "keys" to J.floats(axis.xKeys.toFloatArray()))
			}
			while (binary.size() % 4 != 0) binary.write(0)
			val document = J.obj(*listOfNotNull(
				"asset" to J.obj("version" to J.Str("2.0"), "generator" to J.Str("PSD2Live ${Compiler.version}")),
				"extensionsUsed" to J.arr(listOf(J.Str("KHR_materials_unlit"))),
				"scene" to J.Num(0),
				"scenes" to J.arr(listOf(J.obj("nodes" to J.arr(listOf(J.Num(nodes.size - 1)))))),
				"nodes" to J.arr(nodes), "meshes" to J.arr(gltfMeshes), "materials" to J.arr(materials),
				"textures" to J.arr(images.indices.map { J.obj("source" to J.Num(it), "sampler" to J.Num(0)) }),
				"images" to J.arr(images),
				"samplers" to J.arr(listOf(J.obj("magFilter" to J.Num(9729), "minFilter" to J.Num(9729), "wrapS" to J.Num(33071), "wrapT" to J.Num(33071)))),
				animations.takeIf { it.isNotEmpty() }?.let { "animations" to J.arr(it) },
				"accessors" to J.arr(accessors), "bufferViews" to J.arr(bufferViews),
				"buffers" to J.arr(listOf(J.obj("byteLength" to J.Num(binary.size())))),
				"extras" to J.obj("psd2live" to J.obj("parameters" to J.arr(parameters))),
			).toTypedArray())
			return container(StringBuilder().also(document::write).toString().encodeToByteArray(), binary.toByteArray())
		}

		/** A clip as weight animations of the meshes it moves, sampled at [fps] with redundant keys dropped. */
		private fun animation(clip: Clip, meshTargets: List<List<Target>>): J? {
			val times = ClipSampler.frameTimes(clip, fps)
			val poses = times.map { ClipSampler.valuesAt(clip, it) }
			val samplers = ArrayList<J>(); val channels = ArrayList<J>()
			for ((node, targets) in meshTargets.withIndex()) {
				if (targets.isEmpty() || targets.none { axes[it.parameter].x.id in clip.curves.map(Curve::parameter) }) continue
				val frames = poses.map { weights(targets, it) }
				val kept = KeyReduction.reduce(times, frames, 1e-3f)
				val input = floats(FloatArray(kept.size) { times[kept[it]] }, 1, "SCALAR", bounds = true, target = null)
				val output = floats(kept.flatMap { frames[it].asList() }.toFloatArray(), 1, "SCALAR", bounds = false, target = null)
				samplers += J.obj("input" to J.Num(input), "output" to J.Num(output), "interpolation" to J.Str("LINEAR"))
				channels += J.obj("sampler" to J.Num(samplers.size - 1), "target" to J.obj("node" to J.Num(node), "path" to J.Str("weights")))
			}
			if (channels.isEmpty()) return null
			return J.obj("name" to J.Str(clip.name), "samplers" to J.arr(samplers), "channels" to J.arr(channels))
		}
	}

	/** The GLB container: header, JSON chunk padded with spaces, binary chunk padded with zeros. */
	private fun container(json: ByteArray, bin: ByteArray): ByteArray {
		val jsonLength = (json.size + 3) / 4 * 4
		val binLength = (bin.size + 3) / 4 * 4
		val total = 12 + 8 + jsonLength + (if (binLength > 0) 8 + binLength else 0)
		val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
		out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
		out.putInt(jsonLength); out.putInt(0x4E4F534A); out.put(json); repeat(jsonLength - json.size) { out.put(' '.code.toByte()) }
		if (binLength > 0) { out.putInt(binLength); out.putInt(0x004E4942); out.put(bin); repeat(binLength - bin.size) { out.put(0) } }
		return out.array()
	}
}
