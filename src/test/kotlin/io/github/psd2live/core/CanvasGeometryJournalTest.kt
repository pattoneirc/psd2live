package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.abs
import kotlin.test.*

/** `canvas_geometry` journal entries are written as sparse quantized deltas and replay as compiled. */
class CanvasGeometryJournalTest {
	private val parameter = ParameterId("P")

	/** A 6×6 vertex grid mesh keyed on P, under a 2×2 warp keyed on P. */
	private fun model(): PuppetModel {
		val side = 6
		val positions = FloatArray(side * side * 2) { i -> if (i % 2 == 0) (i / 2 % side) / (side - 1f) else (i / 2 / side) / (side - 1f) }
		val indices = (0 until side - 1).flatMap { r -> (0 until side - 1).flatMap { c ->
			val a = r * side + c
			listOf(a, a + 1, a + side, a + 1, a + side + 1, a + side)
		} }.toIntArray()
		val axis = KeyformAxis(parameter, floatArrayOf(0f, 1f))
		val meshGrid = KeyformGrid(listOf(axis), listOf(
			KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size))),
			KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(positions.size) { if (it % 2 == 0) 0.01f * it else 0f })),
		))
		val lattice = (0..2).flatMap { r -> (0..2).flatMap { c -> listOf(100f + c * 50f, 80f + r * 40f) } }.toFloatArray()
		val warp = Deformer.Warp(DeformerId("w"), "W", null, null, 2, 2, true, KeyformGrid(listOf(axis), listOf(
			KeyformCell(intArrayOf(0), WarpLatticeForm(lattice)),
			KeyformCell(intArrayOf(1), WarpLatticeForm(FloatArray(lattice.size) { lattice[it] + 3f })),
		)))
		val mesh = Drawable(DrawableId("m"), "M", warp.id, BlendMode.Normal, emptyList(),
			DrawableMesh(positions, positions.copyOf(), indices), meshGrid)
		return PuppetModel(listOf(Parameter(parameter, "P", 0f, 1f, 0f)), emptyList(), listOf(warp), listOf(mesh), emptyList(), null)
	}

	private fun command(kind: String, id: String, key: Map<String, Float>, points: FloatArray, pose: Map<String, Float>? = null) = buildJsonObject {
		put("op", "canvas_geometry"); put("kind", kind); put("id", id)
		put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
		pose?.let { put("pose", JsonObject(it.mapValues { (_, v) -> JsonPrimitive(v) })) }
		put("preserve_image", true)
		put("points", JsonArray(points.map(::JsonPrimitive)))
	}

	private fun meshPositions(model: PuppetModel) = model.drawables.single().mesh!!.positions
	private fun meshCell(model: PuppetModel, index: Int) = model.drawables.single().geometryGrid!!.cells[index].form.positionDeltas
	private fun lattice(model: PuppetModel, index: Int) = (model.deformers.single() as Deformer.Warp).geometryGrid!!.cells[index].form.controlPoints

	private fun assertClose(expected: FloatArray, actual: FloatArray, tolerance: Float) {
		assertEquals(expected.size, actual.size)
		for (i in expected.indices) assertTrue(abs(expected[i] - actual[i]) <= tolerance, "index $i: ${expected[i]} vs ${actual[i]}")
	}

	@Test fun aSparseMeshMoveCompilesToTheMovedVerticesOnly() {
		val model = model()
		val shown = RigGeometryTools.geometry(model, "mesh", "m", emptyMap()).points
		val moved = shown.copyOf().also { it[14] += 0.0371f; it[15] -= 0.0123f; it[40] += 0.25f }
		val v1 = command("mesh", "m", emptyMap(), moved, emptyMap())
		val (compiledModel, journal) = RigAuthoringJournal.compile(model, JsonArray(listOf(v1)))
		val v2 = journal.single()
		assertTrue(CanvasGeometryJournal.isEncoded(v2))
		assertNull(v2["points"])
		assertEquals(listOf(7, 20), v2.getValue("i").jsonArray.map { it.jsonPrimitive.int })
		assertEquals(moved.size, CanvasGeometryJournal.size(v2))
		assertTrue(v2.toString().length < v1.toString().length, "the entry shrinks: $v2")
		// Replay of the entry is what compile previewed, bit for bit; and both are the absolute edit within the quantum.
		val replayed = RigAuthoringJournal.apply(model, v2)
		assertContentEquals(meshPositions(compiledModel), meshPositions(replayed))
		assertClose(meshPositions(CanvasEdits.apply(model, v1)), meshPositions(replayed), 1e-5f)
	}

	@Test fun keyedMeshAndWarpEditsReplayAsTheAbsoluteEdit() {
		val model = model()
		val key = mapOf("P" to 1f)
		val mesh = RigGeometryTools.geometry(model, "mesh", "m", key).points
		val bent = FloatArray(mesh.size) { mesh[it] + if (it % 2 == 0) 0.02f * (it % 7) else -0.013f }
		val warp = RigGeometryTools.geometry(model, "warp", "w", key).points
		val pulled = FloatArray(warp.size) { warp[it] + if (it == 8) 12.5f else 0f }
		val commands = listOf(command("mesh", "m", key, bent), command("warp", "w", key, pulled))
		val (compiledModel, journal) = RigAuthoringJournal.compile(model, JsonArray(commands))
		assertEquals(2, journal.size)
		assertTrue(journal.all(CanvasGeometryJournal::isEncoded))
		assertNull(journal[0]["i"], "a dense edit lists every value")
		assertNotNull(journal[1]["i"])
		val replayed = journal.fold(model, RigAuthoringJournal::apply)
		assertContentEquals(meshCell(compiledModel, 1), meshCell(replayed, 1))
		assertContentEquals(lattice(compiledModel, 1), lattice(replayed, 1))
		val absolute = commands.fold(model, CanvasEdits::apply)
		assertClose(meshCell(absolute, 1), meshCell(replayed, 1), 1e-5f)
		assertClose(lattice(absolute, 1), lattice(replayed, 1), 1e-3f)
		// The JSON round trip of the journal replays the same.
		val reread = Json.parseToJsonElement(JsonArray(journal).toString()).jsonArray.map { it.jsonObject }
		assertContentEquals(lattice(replayed, 1), lattice(reread.fold(model, RigAuthoringJournal::apply), 1))
	}

	@Test fun versionOneEntriesStillReplayTheirAbsolutePoints() {
		val model = model()
		val shown = RigGeometryTools.geometry(model, "mesh", "m", emptyMap()).points
		val v1 = command("mesh", "m", emptyMap(), FloatArray(shown.size) { shown[it] + 0.125f }, emptyMap())
		val expected = CanvasEdits.apply(model, v1)
		assertContentEquals(meshPositions(expected), meshPositions(RigAuthoringJournal.apply(model, v1)))
		assertEquals(shown.size, CanvasGeometryJournal.size(v1))
		val v2 = CanvasGeometryJournal.encode(model, v1)
		assertSame(v2, CanvasGeometryJournal.encode(model, v2), "an encoded entry is kept as it is")
	}

	@Test fun anEditThatMovesNothingCompilesToNothing() {
		val model = model()
		val shown = RigGeometryTools.geometry(model, "mesh", "m", emptyMap()).points
		assertTrue(RigAuthoringJournal.compile(model, JsonArray(listOf(command("mesh", "m", emptyMap(), shown, emptyMap())))).second.isEmpty())
	}

	@Test fun anEncodedEntryRejectsATargetWithAnotherVertexCount() {
		val model = model()
		val shown = RigGeometryTools.geometry(model, "mesh", "m", emptyMap()).points
		val v2 = RigAuthoringJournal.compile(model, JsonArray(listOf(command("mesh", "m", emptyMap(),
			shown.copyOf().also { it[0] += 0.1f }, emptyMap())))).second.single()
		val smaller = model.copy(drawables = model.drawables.map { d ->
			d.copy(mesh = DrawableMesh(d.mesh!!.positions.copyOf(8), d.mesh!!.uvs.copyOf(8), intArrayOf(0, 1, 2, 1, 3, 2)), geometryGrid = null)
		})
		assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.apply(smaller, v2) }
	}
}
