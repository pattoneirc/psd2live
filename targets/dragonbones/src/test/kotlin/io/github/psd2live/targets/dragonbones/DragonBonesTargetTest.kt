package io.github.psd2live.targets.dragonbones

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.spine.MiniJson
import kotlin.test.*

class DragonBonesTargetTest {
	/** "M" slides right 10 px per unit of A and sits in front of "S" once A passes 0.5; "S" fades with A. */
	private class Evaluator(private val vertices: Int = 3) : GeometryEvaluator {
		override fun open(ir: RigIR) = object : GeometrySession {
			override fun evaluate(parameters: Map<String, Float>): PoseGeometry {
				val a = parameters["A"] ?: 0f
				val moving = FloatArray(vertices * 2) { if (it % 2 == 0) 10f + it + 10f * a else 10f + a * a * 20f }
				return PoseGeometry(mapOf("M" to moving, "S" to floatArrayOf(50f, 50f, 60f, 50f, 50f, 60f)),
					mapOf("M" to 1f, "S" to 1f - 0.5f * a), mapOf("M" to if (a > 0.5f) 900f else 400f, "S" to 500f))
			}
			override fun close() {}
		}
	}

	private fun rig(vertices: Int = 3) = RigIR(
		canvas = Canvas(100f, 80f),
		parameters = listOf(Parameter("A", "Axis", 0f, 1f, 0f)),
		meshes = listOf(
			Mesh("M", "Moving", null, page = 0, blend = ColorBlend.ADD,
				geometry = MeshGeometry(Floats.wrap(FloatArray(vertices * 2)), Floats.wrap(FloatArray(vertices * 2)), Ints.values(0, 1, 2)),
				offsets = KeyGrid(listOf(KeyAxis("A", Floats.values(0f, 0.5f, 1f))), (0..2).map { KeyCell(Ints.values(it), MeshOffsets(Floats.Empty)) })),
			Mesh("S", "Still", null, geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)),
				offsets = null, page = 0, multiply = Rgb(1f, 0.5f, 0f)),
		),
		textures = Textures(pages = listOf(TexturePage(64, 32, Bytes.of(byteArrayOf(7))))),
		clips = listOf(Clip("c", "Wave", "Idle", "wave", 1f, 30f, true, curves = listOf(Curve("A", 0f, 0f, listOf(CurveSegment.Linear(0.5f, 1f), CurveSegment.Linear(1f, 0f)))))),
	)

	private fun export(vertices: Int = 3, vararg settings: Pair<String, String>): Pair<Map<String, ByteArray>, ExportReport> {
		val files = LinkedHashMap<String, ByteArray>()
		val report = Compiler.export(DragonBonesTarget(Evaluator(vertices)), rig(vertices), ExportOptions("hero", settings = mapOf("frame_rate" to "4") + settings)) { path, bytes -> files[path] = bytes }
		return files to report
	}

	@Suppress("UNCHECKED_CAST")
	private fun armature(files: Map<String, ByteArray>) =
		((MiniJson(files.getValue("hero_ske.json").decodeToString()).value() as Map<String, Any?>)["armature"] as List<Map<String, Any?>>).single()

	@Suppress("UNCHECKED_CAST")
	private fun animation(armature: Map<String, Any?>, name: String) = (armature["animation"] as List<Map<String, Any?>>).single { it["name"] == name }

	@Test fun theSkeletonHoldsSlotsMeshesAndOneAtlasPerPage() {
		val (files, _) = export()
		assertEquals(listOf("hero_ske.json", "hero_tex_0.json", "hero_tex_0.png"), files.keys.toList())
		val atlas = MiniJson(files.getValue("hero_tex_0.json").decodeToString()).value() as Map<*, *>
		assertEquals("hero_tex_0.png", atlas["imagePath"])
		assertEquals("page0", ((atlas["SubTexture"] as List<*>).single() as Map<*, *>)["name"])
		val armature = armature(files)
		@Suppress("UNCHECKED_CAST") val slots = armature["slot"] as List<Map<String, Any?>>
		// Back to front by rest draw order: M (400) before S (500).
		assertEquals(listOf("M", "S"), slots.map { it["name"] })
		assertEquals("add", slots[0]["blendMode"])
		assertEquals(mapOf("aM" to 100.0, "rM" to 100.0, "gM" to 50.0, "bM" to 0.0), slots[1]["color"])
		@Suppress("UNCHECKED_CAST") val display = ((((armature["skin"] as List<Map<String, Any?>>).single()["slot"] as List<Map<String, Any?>>)
			.first { it["name"] == "M" })["display"] as List<Map<String, Any?>>).single()
		// Armature space: origin at the bottom center, y down.
		assertEquals(listOf(-40.0, -70.0), (display["vertices"] as List<*>).take(2))
		assertEquals("page0", display["path"])
	}

	@Test fun parameterAnimationsKeyTheirFramesWithLinearTweens() {
		val (files, _) = export()
		val anim = animation(armature(files), "param/A")
		assertEquals(4.0, anim["duration"])
		@Suppress("UNCHECKED_CAST") val frames = ((anim["ffd"] as List<Map<String, Any?>>).single()["frame"] as List<Map<String, Any?>>)
		// Keys 0, 0.5 and 1 at 4 frames per second: frames 0, 2 and 4.
		assertEquals(listOf(2.0, 2.0, 0.0), frames.map { it["duration"] })
		assertEquals(listOf(0.0, 0.0, null), frames.map { it["tweenEasing"] })
		@Suppress("UNCHECKED_CAST") val zOrder = ((anim["zOrder"] as Map<String, Any?>)["frame"] as List<Map<String, Any?>>)
		// M moves in front of S at A = 1: slot 0 moves one place up and slot 1 one down.
		assertEquals(listOf(0.0, 1.0, 1.0, -1.0), zOrder.last()["zOrder"])
		@Suppress("UNCHECKED_CAST") val colors = ((anim["slot"] as List<Map<String, Any?>>).single()["colorFrame"] as List<Map<String, Any?>>)
		assertEquals(50.0, (colors.last()["value"] as Map<*, *>)["aM"])
	}

	@Test fun animationsOverTheRuntimesDataLimitAreReducedAndReported() {
		// 6000 moving vertices keyed at three frames exceed the 16-bit addressed deform data.
		val (files, report) = export(6000)
		val anim = animation(armature(files), "param/A")
		@Suppress("UNCHECKED_CAST") val frames = ((anim["ffd"] as List<Map<String, Any?>>).single()["frame"] as List<*>)
		assertEquals(2, frames.size)
		val loss = report.losses.single { it.objectId == "param/A" }
		assertTrue(loss.error!! > 0.25f)
	}
}
