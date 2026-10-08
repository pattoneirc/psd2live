package io.github.psd2live.targets.spine

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import kotlin.test.*

class SpineClippingTest {
	/** The mask "K" (a unit square of two triangles) slides right 10 px with A; "M" is masked by it and "S" draws over M. */
	private object Evaluator : GeometryEvaluator {
		override fun open(ir: RigIR) = object : GeometrySession {
			override fun evaluate(parameters: Map<String, Float>): PoseGeometry {
				val a = parameters["A"] ?: 0f
				return PoseGeometry(
					mapOf("K" to floatArrayOf(10f + 10f * a, 10f, 20f + 10f * a, 10f, 10f + 10f * a, 20f, 20f + 10f * a, 20f),
						"M" to floatArrayOf(5f, 5f, 25f, 5f, 5f, 25f), "S" to floatArrayOf(50f, 50f, 60f, 50f, 50f, 60f)),
					mapOf("K" to 1f, "M" to 1f, "S" to 1f), mapOf("K" to 100f, "M" to 400f, "S" to if (a > 0.5f) 300f else 500f))
			}
			override fun close() {}
		}
	}

	private val triangle = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
	private val rig = RigIR(
		canvas = Canvas(100f, 80f),
		parameters = listOf(Parameter("A", "Axis", 0f, 1f, 0f)),
		meshes = listOf(
			Mesh("K", "Mask", null, page = 0, offsets = KeyGrid(listOf(KeyAxis("A", Floats.values(0f, 1f))), (0..1).map { KeyCell(Ints.values(it), MeshOffsets(Floats.Empty)) }),
				geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), Ints.values(0, 1, 2, 1, 3, 2))),
			Mesh("M", "Masked", null, geometry = triangle, offsets = null, page = 0, maskedBy = listOf("K")),
			Mesh("S", "Swapping", null, geometry = triangle, offsets = KeyGrid(listOf(KeyAxis("A", Floats.values(0f, 1f))), (0..1).map { KeyCell(Ints.values(it), MeshOffsets(Floats.Empty)) }), page = 0),
		),
		textures = Textures(pages = listOf(TexturePage(4, 4, Bytes.of(byteArrayOf(7))))),
	)

	@Suppress("UNCHECKED_CAST")
	@Test fun masksBecomeClippingSlotsThatFollowTheirMasks() {
		val files = LinkedHashMap<String, ByteArray>()
		val report = Compiler.export(SpineTarget(Evaluator), rig, ExportOptions("hero")) { path, bytes -> files[path] = bytes }
		val root = MiniJson(files.getValue("hero.json").decodeToString()).value() as Map<String, Any?>
		// The clipping slot sits right before the slot it clips.
		assertEquals(listOf("K", "clip/M", "M", "S"), (root["slots"] as List<Map<String, Any?>>).map { it["name"] })
		val attachments = ((root["skins"] as List<Map<String, Any?>>).single()["attachments"] as Map<String, Any?>)
		val clip = (attachments["clip/M"] as Map<String, Any?>)["clip/M"] as Map<String, Any?>
		assertEquals("clipping", clip["type"]); assertEquals("M", clip["end"]); assertEquals(4.0, clip["vertexCount"])
		// The square's outline at rest, in skeleton space (origin bottom center, y up).
		val outline = (clip["vertices"] as List<Number>).map { it.toDouble() }.chunked(2).toSet()
		assertEquals(setOf(listOf(-40.0, 70.0), listOf(-30.0, 70.0), listOf(-40.0, 60.0), listOf(-30.0, 60.0)), outline)
		val anim = (root["animations"] as Map<String, Any?>)["param/A"] as Map<String, Any?>
		// The clip deforms with its mask: every outline vertex moves 10 px right at A = 1 (trailing zeros trimmed).
		val keys = (((anim["attachments"] as Map<String, Any?>)["default"] as Map<String, Any?>)["clip/M"] as Map<String, Any?>)["clip/M"] as Map<String, Any?>
		val last = (keys["deform"] as List<Map<String, Any?>>).last()
		assertEquals(listOf(10.0, 0.0, 10.0, 0.0, 10.0, 0.0, 10.0), (last["vertices"] as List<Number>).map { it.toDouble() })
		// S moving behind M moves past the clipping slot too: K, S, clip/M, M.
		val order = (anim["drawOrder"] as List<Map<String, Any?>>).last()["offsets"] as List<Map<String, Any?>>
		assertEquals(listOf(mapOf("slot" to "clip/M", "offset" to 1.0), mapOf("slot" to "M", "offset" to 1.0), mapOf("slot" to "S", "offset" to -2.0)), order)
		assertEquals(Handling.APPROXIMATED, report.losses.single { it.feature == Feature.MASK }.handling)
	}
}
