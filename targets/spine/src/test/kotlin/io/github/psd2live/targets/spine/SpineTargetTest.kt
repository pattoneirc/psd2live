package io.github.psd2live.targets.spine

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import kotlin.test.*

class SpineTargetTest {
	/** "M" slides right 10 px per unit of A and sits in front of "S" once A passes 0.5; "S" fades with A. */
	private object Evaluator : GeometryEvaluator {
		override fun open(ir: RigIR) = object : GeometrySession {
			override fun evaluate(parameters: Map<String, Float>): PoseGeometry {
				val a = parameters["A"] ?: 0f
				return PoseGeometry(
					mapOf("M" to floatArrayOf(10f + 10f * a, 10f, 20f + 10f * a, 10f, 10f + 10f * a, 20f), "S" to floatArrayOf(50f, 50f, 60f, 50f, 50f, 60f)),
					mapOf("M" to 1f, "S" to 1f - 0.5f * a), mapOf("M" to if (a > 0.5f) 900f else 400f, "S" to 500f))
			}
			override fun close() {}
		}
	}

	private val geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
	private val rig = RigIR(
		canvas = Canvas(100f, 80f),
		parameters = listOf(Parameter("A", "Axis", 0f, 1f, 0f)),
		meshes = listOf(
			Mesh("M", "Moving", null, geometry = geometry, offsets = KeyGrid(listOf(KeyAxis("A", Floats.values(0f, 1f))), listOf(KeyCell(Ints.values(0), MeshOffsets(Floats.Empty)), KeyCell(Ints.values(1), MeshOffsets(Floats.Empty)))), page = 0, blend = ColorBlend.ADD),
			Mesh("S", "Still", null, geometry = geometry, offsets = null, page = 0, multiply = Rgb(1f, 0.5f, 0f)),
		),
		textures = Textures(pages = listOf(TexturePage(64, 32, Bytes.of(byteArrayOf(7))))),
		clips = listOf(Clip("wave", "Wave", "Idle", "wave", 1f, 30f, true, curves = listOf(Curve("A", 0f, 0f, listOf(CurveSegment.Linear(0.5f, 1f), CurveSegment.Linear(1f, 0f)))))),
	)

	private fun export(vararg settings: Pair<String, String>): Map<String, ByteArray> {
		val files = LinkedHashMap<String, ByteArray>()
		Compiler.export(SpineTarget(Evaluator), rig, ExportOptions("hero", settings = mapOf(*settings))) { path, bytes -> files[path] = bytes }
		return files
	}

	private fun parse(text: String): Any? = MiniJson(text).value()

	@Test fun writesSkeletonAtlasAndPages() {
		val files = export()
		assertEquals(listOf("hero.json", "hero.atlas", "hero_0.png"), files.keys.toList())
		assertEquals("hero_0.png\n\tsize: 64, 32\n\tfilter: Linear, Linear\nhero_0\n\tbounds: 0, 0, 64, 32\n", files.getValue("hero.atlas").decodeToString())
		@Suppress("UNCHECKED_CAST") val root = parse(files.getValue("hero.json").decodeToString()) as Map<String, Any?>
		val slots = root["slots"] as List<Map<String, Any?>>
		// Back to front by rest draw order: M (400) before S (500).
		assertEquals(listOf("M", "S"), slots.map { it["name"] })
		assertEquals("additive", slots[0]["blend"]); assertEquals("ff8000ff", slots[1]["color"])
		val skins = root["skins"] as List<Map<String, Any?>>
		val mesh = ((skins.single()["attachments"] as Map<*, *>)["M"] as Map<*, *>)["M"] as Map<*, *>
		// Origin at the canvas' bottom center, y up.
		assertEquals(listOf(-40.0, 70.0, -30.0, 70.0, -40.0, 60.0), (mesh["vertices"] as List<*>).map { (it as Number).toDouble() })
		assertEquals("hero_0", mesh["path"])
	}

	/** Setup vertices plus the interpolated deform at [time], as a Spine runtime applies unweighted deform keys. */
	private fun deformed(root: Map<String, Any?>, animation: String, slot: String, time: Double): List<Double> {
		val skins = root["skins"] as List<*>
		val setup = ((((skins.single() as Map<*, *>)["attachments"] as Map<*, *>)[slot] as Map<*, *>)[slot] as Map<*, *>)["vertices"] as List<*>
		val base = setup.map { (it as Number).toDouble() }
		val anim = (root["animations"] as Map<*, *>)[animation] as Map<*, *>
		val keys = ((((anim["attachments"] as Map<*, *>?)?.get("default") as Map<*, *>?)?.get(slot) as Map<*, *>?)?.get(slot) as Map<*, *>?)?.get("deform") as List<*>?
			?: return base
		fun full(key: Map<*, *>): List<Double> {
			val values = DoubleArray(base.size)
			val start = (key["offset"] as Number?)?.toInt() ?: 0
			(key["vertices"] as List<*>?)?.forEachIndexed { i, v -> values[start + i] = (v as Number).toDouble() }
			return values.toList()
		}
		val parsed = keys.map { it as Map<*, *> }.map { ((it["time"] as Number?)?.toDouble() ?: 0.0) to full(it) }
		val after = parsed.indexOfFirst { it.first >= time }
		val delta = when {
			after <= 0 -> parsed.first().second
			else -> { val (t0, d0) = parsed[after - 1]; val (t1, d1) = parsed[after]; val u = (time - t0) / (t1 - t0); d0.indices.map { d0[it] + (d1[it] - d0[it]) * u } }
		}
		return base.indices.map { base[it] + delta[it] }
	}

	@Test fun parameterAnimationsReproduceTheRigAtTheirKeys() {
		@Suppress("UNCHECKED_CAST") val root = parse(export().getValue("hero.json").decodeToString()) as Map<String, Any?>
		// Time 1 is A at its maximum: M moved 10 px right; y is unchanged.
		assertEquals(listOf(-30.0, 70.0, -20.0, 70.0, -30.0, 60.0), deformed(root, "param/A", "M", 1.0).map { Math.round(it * 1000) / 1000.0 })
		val anim = (root["animations"] as Map<*, *>)["param/A"] as Map<*, *>
		// S fades: its alpha is keyed; M changes draw order past S, applied with Spine's offset algorithm.
		val rgba = (((anim["slots"] as Map<*, *>)["S"] as Map<*, *>)["rgba"] as List<*>).map { (it as Map<*, *>)["color"] }
		assertEquals(listOf("ff8000ff", "ff800080"), rgba)
		val keys = anim["drawOrder"] as List<*>
		assertEquals(listOf("M", "S"), applyDrawOrder(listOf("M", "S"), keys.first() as Map<*, *>))
		assertEquals(listOf("S", "M"), applyDrawOrder(listOf("M", "S"), keys.last() as Map<*, *>))
	}

	/** Spine's draw order key: moved slots placed at index + offset, the rest keep their relative order. */
	private fun applyDrawOrder(setup: List<String>, key: Map<*, *>): List<String> {
		val offsets = key["offsets"] as List<*>? ?: return setup
		val order = arrayOfNulls<String>(setup.size)
		val unchanged = ArrayList<String>()
		var original = 0
		for (entry in offsets.map { it as Map<*, *> }) {
			val index = setup.indexOf(entry["slot"])
			while (original != index) unchanged += setup[original++]
			order[original + (entry["offset"] as Number).toInt()] = setup[original++]
		}
		while (original < setup.size) unchanged += setup[original++]
		var u = unchanged.size - 1
		for (i in order.indices.reversed()) if (order[i] == null) order[i] = unchanged[u--]
		return order.map { it!! }
	}

	@Test fun clipsAreSampledAtTheClipFrameRate() {
		@Suppress("UNCHECKED_CAST") val root = parse(export("clip_fps" to "10").getValue("hero.json").decodeToString()) as Map<String, Any?>
		// A looping 1 s clip at 10 fps samples 11 frames; the triangle wave reduces to its start, peak and end.
		val keys = (((((root["animations"] as Map<*, *>)["clip/Wave"] as Map<*, *>)["attachments"] as Map<*, *>)["default"] as Map<*, *>)["M"] as Map<*, *>)["M"] as Map<*, *>
		assertEquals(listOf(0.0, 0.5, 1.0), (keys["deform"] as List<*>).map { ((it as Map<*, *>)["time"] as Number).toDouble() })
		// At 0.5 s the clip reaches A = 1.
		assertEquals(-30.0, Math.round(deformed(root, "clip/Wave", "M", 0.5).first() * 1000) / 1000.0)
		assertNull((root["animations"] as Map<*, *>)["clip/Wave"].let { export("clips" to "false") }.let { parse(it.getValue("hero.json").decodeToString()) as Map<*, *> }.let { (it["animations"] as Map<*, *>)["clip/Wave"] })
	}
}
