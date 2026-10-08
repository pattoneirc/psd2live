package io.github.psd2live.targets.gltf

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.spine.MiniJson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class GltfTargetTest {
	/** "M" slides right 10 px per unit of A on [0, 1] and bends at A = 0.5 (up 4 px there); "S" never moves. */
	private object Evaluator : GeometryEvaluator {
		override fun open(ir: RigIR) = object : GeometrySession {
			override fun evaluate(parameters: Map<String, Float>): PoseGeometry {
				val a = parameters["A"] ?: 0f
				val lift = 4f * (1f - kotlin.math.abs(a - 0.5f) * 2f).coerceAtLeast(0f)
				return PoseGeometry(
					mapOf("M" to floatArrayOf(10f + 10f * a, 10f - lift, 20f + 10f * a, 10f, 10f + 10f * a, 20f), "S" to floatArrayOf(50f, 50f, 60f, 50f, 50f, 60f)),
					mapOf("M" to 1f, "S" to 0.5f), mapOf("M" to 600f, "S" to 400f))
			}
			override fun close() {}
		}
	}

	private val triangle = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
	private val rig = RigIR(
		canvas = Canvas(100f, 80f),
		parameters = listOf(Parameter("A", "Axis", 0f, 1f, 0f)),
		meshes = listOf(
			Mesh("M", "Moving", null, geometry = triangle, page = 0,
				offsets = KeyGrid(listOf(KeyAxis("A", Floats.values(0f, 0.5f, 1f))), (0..2).map { KeyCell(Ints.values(it), MeshOffsets(Floats.Empty)) })),
			Mesh("S", "Still", null, geometry = triangle, offsets = null, page = 0),
		),
		textures = Textures(pages = listOf(TexturePage(4, 4, Bytes.of(byteArrayOf(1, 2, 3))))),
		clips = listOf(Clip("c", "Sweep", "Idle", "sweep", 1f, 10f, false, curves = listOf(Curve("A", 0f, 0f, listOf(CurveSegment.Linear(1f, 1f)))))),
	)

	private class Glb(bytes: ByteArray) {
		val json: Map<String, Any?>
		val bin: ByteBuffer
		init {
			val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
			assertEquals(0x46546C67, b.getInt(0)); assertEquals(2, b.getInt(4)); assertEquals(bytes.size, b.getInt(8))
			val jsonLength = b.getInt(12)
			@Suppress("UNCHECKED_CAST")
			json = MiniJson(String(bytes, 20, jsonLength, Charsets.UTF_8)).value() as Map<String, Any?>
			val binLength = b.getInt(20 + jsonLength)
			bin = ByteBuffer.wrap(bytes, 28 + jsonLength, binLength).slice().order(ByteOrder.LITTLE_ENDIAN)
		}

		@Suppress("UNCHECKED_CAST")
		fun list(key: String) = json[key] as List<Map<String, Any?>>

		fun floats(accessor: Int): FloatArray {
			val a = list("accessors")[accessor]
			val view = list("bufferViews")[(a["bufferView"] as Number).toInt()]
			val offset = (view["byteOffset"] as Number).toInt()
			val count = (a["count"] as Number).toInt() * when (a["type"]) { "VEC3" -> 3; "VEC2" -> 2; else -> 1 }
			return FloatArray(count) { bin.getFloat(offset + it * 4) }
		}
	}

	private fun export(): Pair<Glb, ExportReport> {
		var bytes = ByteArray(0)
		val report = Compiler.export(GltfTarget(Evaluator), rig, ExportOptions("hero", settings = mapOf("pixels_per_meter" to "10", "clip_fps" to "10"))) { path, data ->
			assertEquals("hero.glb", path); bytes = data
		}
		return Glb(bytes) to report
	}

	@Test fun meshesStackInDrawOrderInMetersWithYUp() {
		val (glb, _) = export()
		val nodes = glb.list("nodes")
		// S (400) is behind M (600); the rig node holds both.
		assertEquals(listOf("S", "M", "rig"), nodes.map { it["name"] })
		val mesh = glb.list("meshes")[1]
		@Suppress("UNCHECKED_CAST") val primitive = (mesh["primitives"] as List<Map<String, Any?>>).single()
		@Suppress("UNCHECKED_CAST") val position = glb.floats(((primitive["attributes"] as Map<String, Any?>)["POSITION"] as Number).toInt())
		// (10, 10) px on a 100 x 80 canvas at 10 px per meter: x (10 - 50) / 10, y (80 - 10) / 10, one layer forward.
		assertEquals(-4f, position[0], 1e-5f); assertEquals(7f, position[1], 1e-5f); assertEquals(0.0005f, position[2], 1e-7f)
		val material = glb.list("materials")[0]
		@Suppress("UNCHECKED_CAST") assertEquals(0.5, ((material["pbrMetallicRoughness"] as Map<String, Any?>)["baseColorFactor"] as List<Number>)[3].toDouble())
	}

	@Test fun weightsFallBetweenKeysAndReproduceSampledPoses() {
		val (glb, _) = export()
		val mesh = glb.list("meshes")[1]
		@Suppress("UNCHECKED_CAST") assertEquals(listOf("A=0.5", "A=1.0"), (mesh["extras"] as Map<String, Any?>)["targetNames"])
		@Suppress("UNCHECKED_CAST") val primitive = (mesh["primitives"] as List<Map<String, Any?>>).single()
		@Suppress("UNCHECKED_CAST") val targets = (primitive["targets"] as List<Map<String, Any?>>).map { glb.floats((it["POSITION"] as Number).toInt()) }
		@Suppress("UNCHECKED_CAST") val rest = glb.floats(((primitive["attributes"] as Map<String, Any?>)["POSITION"] as Number).toInt())
		// At A = 0.75 the hats give 0.5 to each target: x moves 7.5 px and the bend is half gone.
		val posed = FloatArray(rest.size) { rest[it] + 0.5f * targets[0][it] + 0.5f * targets[1][it] }
		assertEquals((17.5f - 50f) / 10f, posed[0], 1e-5f)
		assertEquals((80f - (10f - 2f)) / 10f, posed[1], 1e-5f)
	}

	@Test fun clipsAnimateWeightsWithRedundantKeysDropped() {
		val (glb, _) = export()
		val animation = glb.list("animations").single()
		assertEquals("Sweep", animation["name"])
		@Suppress("UNCHECKED_CAST") val sampler = (animation["samplers"] as List<Map<String, Any?>>).single()
		// A linear sweep keeps the keys at 0, 0.5 and 1 s (the hats' corners).
		assertContentEquals(floatArrayOf(0f, 0.5f, 1f), glb.floats((sampler["input"] as Number).toInt()))
		assertContentEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), glb.floats((sampler["output"] as Number).toInt()))
	}
}
