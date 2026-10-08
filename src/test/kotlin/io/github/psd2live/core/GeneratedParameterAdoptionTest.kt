package io.github.psd2live.core

import io.github.psd2live.core.sim.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

/**
 * Edits made on the generated rig and replayed on the authored one, before the generators: a swing on WarpTail
 * (meshes m and n under it) and a baked simulation on mesh s. Whatever the user did on the shown rig replays to the
 * same result, or is refused while it is made - never dropped on the way.
 */
class GeneratedParameterAdoptionTest {
	private val angle = Parameter(ParameterId("ParamAngleX"), "Angle X", -30f, 30f, 0f)
	private val swing = "ParamSwingTail"
	private val sim = "ParamSimS_1"
	private val rows = 2; private val columns = 2

	private fun lattice(w: Float, h: Float) = FloatArray((rows + 1) * (columns + 1) * 2).also { p ->
		var i = 0; for (r in 0..rows) for (c in 0..columns) { p[i++] = w * c / columns; p[i++] = h * r / rows } }

	private fun grid(side: Int, x0: Float, y0: Float, size: Float): DrawableMesh {
		val positions = FloatArray(side * side * 2) { i -> if (i % 2 == 0) x0 + size * (i / 2 % side) / (side - 1f) else y0 + size * (i / 2 / side) / (side - 1f) }
		val indices = (0 until side - 1).flatMap { r -> (0 until side - 1).flatMap { c -> val a = r * side + c; listOf(a, a + 1, a + side, a + 1, a + side + 1, a + side) } }.toIntArray()
		return DrawableMesh(positions, positions.copyOf(), indices)
	}

	private fun authored(): PuppetModel {
		val rest = lattice(100f, 300f)
		val turned = FloatArray(rest.size) { if (it % 2 == 0) rest[it] + 20f else rest[it] }
		val warp = Deformer.Warp(DeformerId("WarpTail"), "Tail", null, null, rows, columns, true,
			KeyformGrid(listOf(KeyformAxis(angle.id, floatArrayOf(-30f, 30f))), listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(rest)), KeyformCell(intArrayOf(1), WarpLatticeForm(turned)))))
		val m = Drawable(DrawableId("m"), "M", warp.id, BlendMode.Normal, emptyList(), grid(4, 0.1f, 0.1f, 0.5f), null)
		val n = Drawable(DrawableId("n"), "N", warp.id, BlendMode.Normal, emptyList(), grid(4, 0.55f, 0.1f, 0.4f), null)
		val s = Drawable(DrawableId("s"), "S", null, BlendMode.Normal, emptyList(), grid(4, 300f, 0f, 60f), null)
		return PuppetModel(listOf(angle), emptyList(), listOf(warp), listOf(m, n, s), emptyList(), null, worldOriginX = 500f)
	}

	private fun overlay(journal: List<JsonObject> = emptyList()): RigEditOverlay {
		val swingEdit = RigSwingEdit.single("tail", "Tail swing", SwingKind.LATERAL, listOf("WarpTail"), listOf(swing), shape = SwingShape())
		val count = 16
		val bake = SimBakeResult("f", mapOf("s" to count), emptyList(), listOf(SimBakedMode(
			SimBakedAxis(sim, floatArrayOf(-1f, 1f), mapOf("s" to listOf(FloatArray(count * 2) { 3f }, FloatArray(count * 2) { -3f }))), 1f, 1f)))
		return RigEditOverlay(swingEdits = listOf(swingEdit), simEdits = listOf(RigSimEdit("sim", "Sim", SimKind.CLOTH, listOf("s"), bake = bake)),
			authoringJournal = journal)
	}

	private fun key(vararg pairs: Pair<String, Float>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

	private fun canvas(shown: PuppetModel, kind: String, id: String, key: JsonObject, extra: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
		put("op", "canvas_geometry"); put("kind", kind); put("id", id); put("key", key); put("preserve_image", true)
		val points = RigGeometryTools.geometry(shown, kind, id, key.mapValues { it.value.jsonPrimitive.float }).points.copyOf().also { it[2] += 0.03f; it[3] += 0.01f }
		put("points", JsonArray(points.map(::JsonPrimitive)))
		extra.forEach { (k, v) -> put(k, v) }
	}

	/** What the shown rig became in the session, the journal written for it, and the rig that journal replays to. */
	private class Recorded(val session: PuppetModel, val journal: List<JsonObject>, val replayed: PuppetModel)

	private fun record(vararg edits: (PuppetModel) -> JsonObject): Recorded {
		val authored = authored(); val overlay = overlay()
		val shown = overlay.applyTo(authored)
		val raw = JsonArray(edits.map { it(shown) })
		val journal = JournalRecording.record(shown, authored, overlay, raw)
		val session = RigAuthoringJournal.compile(shown, raw).first
		return Recorded(session, journal, overlay(journal).applyTo(authored))
	}

	private fun ops(recorded: Recorded) = recorded.journal.map { it.getValue("op").jsonPrimitive.content }
	private fun warpCell(model: PuppetModel, key: Map<String, Float>) = (model.deformers.single { it.id.raw == "WarpTail" } as Deformer.Warp).geometryGrid!!.let { grid ->
		grid.cells.single { cell -> grid.axes.withIndex().all { (i, axis) -> axis.keys[cell.coordinate[i]] == key.getValue(axis.parameterId.raw) } }.form.controlPoints }
	private fun axes(model: PuppetModel, id: String) = model.drawables.single { it.id.raw == id }.geometryGrid?.axes?.map { it.parameterId.raw to it.keys.toList() }

	private fun refused(message: String, vararg edits: (PuppetModel) -> JsonObject) {
		val failure = assertFailsWith<IllegalArgumentException> { record(*edits) }
		assertTrue(message in failure.message.orEmpty(), failure.message)
	}

	@Test fun keyingAnotherMeshOnASwingParameterTakesTheParameterIntoTheDocument() {
		for (value in listOf(1f, 0f)) {
			val recorded = record({ shown -> canvas(shown, "mesh", "m", key(swing to value)) })
			assertEquals(listOf("structure", "canvas_geometry"), ops(recorded), "at $value")
			assertEquals(axes(recorded.session, "m"), axes(recorded.replayed, "m"), "at $value")
			assertContentEquals(recorded.session.drawables.single { it.id.raw == "m" }.geometryGrid!!.cells.last().form.positionDeltas,
				recorded.replayed.drawables.single { it.id.raw == "m" }.geometryGrid!!.cells.last().form.positionDeltas)
		}
	}

	@Test fun anAdoptedParameterStaysWhenItsGeneratorGoesAndTheGeneratorKeepsWorking() {
		val recorded = record({ shown -> canvas(shown, "mesh", "m", key(swing to 1f)) })
		val created = recorded.journal.first().getValue("edits").jsonArray.single().jsonObject
		assertEquals("create", created.getValue("action").jsonPrimitive.content)
		assertEquals(swing, created.getValue("id").jsonPrimitive.content)
		// The swing still makes its keyforms on the parameter it now finds.
		assertEquals(listOf("ParamAngleX", swing), (recorded.replayed.deformers.single() as Deformer.Warp).geometryGrid!!.axes.map { it.parameterId.raw })
		val withoutSwing = overlay(recorded.journal).copy(swingEdits = emptyList()).applyTo(authored())
		assertTrue(withoutSwing.parameters.any { it.id.raw == swing })
		assertEquals(axes(recorded.replayed, "m"), axes(withoutSwing, "m"))
	}

	@Test fun keysChannelsRenamesAndRangesOfGeneratedParametersReplay() {
		val keys = record({ _ -> buildJsonObject { put("op", "parameter_keys"); put("track", "geometry"); put("target", "mesh:m"); put("parameter", sim)
			put("action", "add"); put("values", JsonArray(listOf(-1f, 1f).map(::JsonPrimitive))) } })
		assertEquals(listOf(sim to listOf(-1f, 1f)), axes(keys.replayed, "m"))
		val opacity = record({ _ -> buildJsonObject { put("op", "set"); put("target", "mesh:m"); put("key", key(swing to 1f)); putJsonObject("channels") { put("opacity", 0.5f) } } })
		assertEquals(listOf(swing), opacity.replayed.drawables.single { it.id.raw == "m" }.channelGrids.gridsByChannel.values.single().axes.map { it.parameterId.raw })
		val renamed = record({ _ -> buildJsonObject { put("op", "structure"); putJsonArray("edits") { add(buildJsonObject {
			put("action", "rename"); put("kind", "parameter"); put("id", swing); put("name", "Tail") }) } } })
		assertEquals("Tail", renamed.replayed.parameters.single { it.id.raw == swing }.name)
		val ranged = record({ _ -> buildJsonObject { put("op", "structure"); putJsonArray("edits") { add(buildJsonObject {
			put("action", "update"); put("kind", "parameter"); put("id", swing); put("name", "Tail swing"); put("min", -2f); put("max", 2f); put("default", 0f)
			put("parameter_kind", "NORMAL"); put("repeat", false) }) } } })
		assertEquals(-2f to 2f, ranged.replayed.parameters.single { it.id.raw == swing }.let { it.min to it.max })
	}

	@Test fun editsOfGeneratedKeyformsBecomeOverrides() {
		val at = mapOf("ParamAngleX" to 30f, swing to 1f)
		val bezier = record({ shown ->
			val controls = RigBezierJournal.read(shown, overlay(), "WarpTail", at, at)
			controls.state.moveAnchor(0, 0, 4f, 2f)
			RigBezierJournal.materialize(shown, "WarpTail", at, at, controls) })
		assertEquals(listOf(GeneratedOverrides.OP), ops(bezier))
		assertContentEquals(warpCell(bezier.session, at), warpCell(bezier.replayed, at))
		val set = record({ shown -> buildJsonObject { put("op", "set"); put("target", "warp:WarpTail"); put("key", key("ParamAngleX" to 30f, swing to 1f))
			putJsonObject("geometry") { put("controlPoints", JsonArray(warpCell(shown, at).map { JsonPrimitive(it + 1f) })) } } })
		assertEquals(listOf(GeneratedOverrides.OP), ops(set))
		assertContentEquals(warpCell(set.session, at), warpCell(set.replayed, at))
	}

	@Test fun whatCannotReplayOnGeneratedKeyformsIsRefusedWhenMade() {
		refused("Keeping the children", { shown -> canvas(shown, "warp", "WarpTail", key("ParamAngleX" to 30f, swing to 1f), mapOf("preserve_children" to JsonPrimitive(true))) })
		refused("cannot be copied", { _ -> buildJsonObject { put("op", "copy"); put("target", "warp:WarpTail"); put("from", key("ParamAngleX" to 30f, swing to 1f))
			put("key", key("ParamAngleX" to -30f, swing to 0f)) } })
		refused("makes the keys of $swing", { _ -> buildJsonObject { put("op", "delete"); put("target", "warp:WarpTail"); put("parameter", swing); put("value", 1f) } })
		refused("makes the keys of $swing", { _ -> buildJsonObject { put("op", "parameter_keys"); put("track", "geometry"); put("target", "warp:WarpTail"); put("parameter", swing)
			put("action", "add"); put("values", JsonArray(listOf(JsonPrimitive(0.5f)))) } })
	}

	@Test fun glueUnderAGeneratorPairsAsTheJournalSeesIt() {
		val recorded = record({ _ -> buildJsonObject { put("op", "canvas_create_glue"); put("id", "g"); put("mesh_a", "m"); put("mesh_b", "n")
			put("distance", 30f); put("pose", key(swing to 1f)) } })
		val replayed = recorded.replayed.glues.single().pairs.map { it.indexA to it.indexB }
		val atRest = overlay(listOf(buildJsonObject { put("op", "canvas_create_glue"); put("id", "g"); put("mesh_a", "m"); put("mesh_b", "n")
			put("distance", 30f); put("pose", key()) })).applyTo(authored()).glues.single().pairs.map { it.indexA to it.indexB }
		assertEquals(atRest, replayed)
	}

	@Test fun editsThatNameNoGeneratedParameterAreRecordedAsBefore() {
		val recorded = record({ shown -> canvas(shown, "warp", "WarpTail", key("ParamAngleX" to 30f, swing to 0f)) })
		assertEquals(listOf("canvas_geometry"), ops(recorded))
		assertEquals(setOf("ParamAngleX"), recorded.journal.single().getValue("key").jsonObject.keys)
	}
}
