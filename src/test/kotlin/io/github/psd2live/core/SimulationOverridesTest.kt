package io.github.psd2live.core

import io.github.psd2live.application.WorkspaceDocumentEdits
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimBakeResult
import io.github.psd2live.core.sim.SimBakedAxis
import io.github.psd2live.core.sim.SimBakedMode
import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.core.sim.SimKind
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.test.*

/**
 * Baked simulations and swings in the document's generator graph: a user's edit of a simulation's mode
 * keyform is kept as an override and merged when the bake changes, as for swings.
 */
class SimulationOverridesTest {
	private val initial by lazy { PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd")) }

	private val back by lazy {
		val layers = initial.analysis.layers.associateBy { it.source.id.raw }
		initial.rig.puppet.drawables.first { d -> d.mesh != null &&
			layers[initial.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == SemanticTag.BACK_HAIR }
	}

	/** A simulation of the back hair with one mode whose offsets push every vertex sideways by [scale] times its index. */
	private fun simulated(scale: Float): RigEditOverlay {
		val count = back.mesh!!.vertexCount
		val sim = RigSimEdit("back", "Back hair", SimKind.HAIR, listOf(back.id.raw), modes = 1, keys = 3, blendShapes = false)
		val parameter = SimGenerator.parameterId(sim, 1)
		val push = FloatArray(count * 2) { if (it % 2 == 0) scale * (it / 2 % 7) * 1e-3f else 0f }
		val axis = SimBakedAxis(parameter, floatArrayOf(-SimGenerator.MODE_RANGE, 0f, SimGenerator.MODE_RANGE),
			mapOf(back.id.raw to listOf(FloatArray(count * 2) { -push[it] }, FloatArray(count * 2), push)))
		val bake = SimBakeResult("synthetic", mapOf(back.id.raw to count), emptyList(), listOf(SimBakedMode(axis, 10f, 1f)))
		return initial.config.rigEdits.copy(simEdits = listOf(sim.copy(bake = bake)))
	}

	private fun parameterOf(overlay: RigEditOverlay) = overlay.simEdits.single().outputParameters.single()

	/** The key of the simulated cell: the mode at its maximum, every other axis of the mesh at its default. */
	private fun simulatedKey(model: PuppetModel, parameter: String): Map<String, Float> {
		val grid = model.drawables.single { it.id == back.id }.geometryGrid!!
		return grid.axes.associate { axis ->
			val p = model.parameters.single { it.id == axis.parameterId }
			axis.parameterId.raw to if (axis.parameterId.raw == parameter) SimGenerator.MODE_RANGE else p.default
		}
	}

	private fun deltasAt(model: PuppetModel, key: Map<String, Float>): FloatArray {
		val grid = model.drawables.single { it.id == back.id }.geometryGrid!!
		val coordinate = IntArray(grid.axes.size) { a -> grid.axes[a].keys.indexOfFirst { kotlin.math.abs(it - key.getValue(grid.axes[a].parameterId.raw)) < 1e-3f } }
		return grid.cells.single { it.coordinate.contentEquals(coordinate) }.form.positionDeltas
	}

	private fun edit(key: Map<String, Float>, points: FloatArray) = JsonArray(listOf(buildJsonObject {
		put("op", "canvas_geometry"); put("kind", "mesh"); put("id", back.id.raw)
		put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) })); put("preserve_image", false)
		put("points", JsonArray(points.map(::JsonPrimitive)))
	}))

	@Test fun editsOfSimulatedKeyformsMergeWithANewBake() {
		val overlay = simulated(1f)
		val parameter = parameterOf(overlay)
		val generated = overlay.applyTo(initial.rig.puppet)
		val key = simulatedKey(generated, parameter)
		val generatedDeltas = deltasAt(generated, key)
		val shown = RigGeometryTools.geometry(generated, "mesh", back.id.raw, key).points
		// Vertex 13 moves sideways under the mode (13 % 7 != 0); the user drags it further.
		val vertex = 13
		val moved = shown.copyOf().also { it[vertex * 2] += 0.02f; it[vertex * 2 + 1] -= 0.01f }
		val document = WorkspaceDocument(initial.analysis.source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
			WorkspaceSettingsCodec.encode(initial.config))
		val authored = WorkspaceDocumentEdits.journal(document, initial.copy(rig = initial.rig.copy(puppet = generated)), edit(key, moved))
		val command = authored.rigEdits.authoringJournal.last()
		assertEquals(GeneratedOverrides.OP, command.getValue("op").jsonPrimitive.content, "a simulated cell is captured as an override")
		assertEquals("simulation:back", command.getValue("generator").jsonPrimitive.content)
		assertEquals("mesh:${back.id.raw}", command.getValue("target").jsonPrimitive.content)

		// Replayed after the simulation: the edit shows where the user put it.
		val replayed = authored.rigEdits.applyTo(initial.rig.puppet)
		val replayedShown = RigGeometryTools.geometry(replayed, "mesh", back.id.raw, key).points
		for (i in moved.indices) assertEquals(moved[i], replayedShown[i], 1e-4f, "coordinate $i shows as edited")
		val edited = deltasAt(replayed, key)

		// A bake twice as strong: untouched vertices follow it, the edited one keeps the user's offset.
		val stronger = authored.rigEdits.copy(simEdits = simulated(2f).simEdits)
		val regenerated = deltasAt(stronger.applyTo(initial.rig.puppet), key)
		val fresh = deltasAt(simulated(2f).applyTo(initial.rig.puppet), key)
		assertEquals(edited[vertex * 2], regenerated[vertex * 2]); assertEquals(edited[vertex * 2 + 1], regenerated[vertex * 2 + 1])
		for (i in regenerated.indices) if (i / 2 != vertex) assertEquals(fresh[i], regenerated[i], "coordinate $i follows the new bake")
		assertNotEquals(generatedDeltas[vertex * 2], fresh[vertex * 2], "the stronger bake really moved the vertex")
		val outcome = GeneratedOverrides.applyAll(DocumentGenerators.generate(stronger.copy(simEdits = emptyList(), authoringJournal =
			stronger.authoringJournal.filterNot(GeneratedOverrides::isOverride)).applyTo(initial.rig.puppet), stronger), stronger.authoringJournal)
		assertEquals(1, outcome.conflicts.size, outcome.conflicts.toString())

		// Without the bake there is no generated keyform: the override changes nothing and reports itself.
		val unbaked = authored.rigEdits.copy(simEdits = authored.rigEdits.simEdits.map { it.copy(bake = null) })
		val plain = unbaked.applyTo(initial.rig.puppet)
		assertEquals(initial.config.rigEdits.applyTo(initial.rig.puppet).drawables.single { it.id == back.id }.geometryGrid,
			plain.drawables.single { it.id == back.id }.geometryGrid)
		assertTrue(GeneratedOverrides.applyAll(plain, unbaked.authoringJournal).conflicts.single().contains("no generated keyform"))
	}

	@Test fun anOrdinaryMeshEditOfASimulatedCellCannotReplay() {
		val overlay = simulated(1f)
		val generated = overlay.applyTo(initial.rig.puppet)
		val key = simulatedKey(generated, parameterOf(overlay))
		val shown = RigGeometryTools.geometry(generated, "mesh", back.id.raw, key).points
		val raw = overlay.copy(authoringJournal = overlay.authoringJournal + edit(key, shown).single().jsonObject)
		assertFails { raw.applyTo(initial.rig.puppet) }
	}

	@Test fun editsAtTheModeDefaultStayOrdinaryMeshEdits() {
		val overlay = simulated(1f)
		val parameter = parameterOf(overlay)
		val generated = overlay.applyTo(initial.rig.puppet)
		val key = simulatedKey(generated, parameter) + (parameter to 0f)
		val shown = RigGeometryTools.geometry(generated, "mesh", back.id.raw, key).points
		assertEquals("canvas_geometry", GeneratedOverrides.capture(generated, overlay, edit(key, shown)).single().jsonObject.getValue("op").jsonPrimitive.content)
	}

	@Test fun theGraphOrdersAndOwnsTheGenerators() {
		val swing = RigSwingEdit.single("front", "Front", SwingKind.LATERAL, listOf("WarpFront"), listOf("ParamSwingFront"))
		val overlay = simulated(1f).copy(swingEdits = listOf(swing))
		val graph = DocumentGenerators.graph(overlay)
		assertEquals(listOf("rig", "skeleton", "journal", "swing:front", "simulation:back", "overrides", "physics", "motions"), graph.order.map { it.id })
		assertEquals("swing:front", DocumentGenerators.owner(graph, DocumentGenerators.keyform("warp", "WarpFront", "ParamSwingFront"))?.id)
		val mode = parameterOf(overlay)
		assertEquals("simulation:back", DocumentGenerators.owner(graph, DocumentGenerators.keyform("mesh", back.id.raw, mode))?.id)
		assertEquals("simulation:back", DocumentGenerators.owner(graph, "parameter:$mode")?.id)
		assertNull(DocumentGenerators.owner(graph, DocumentGenerators.keyform("mesh", back.id.raw, "ParamAngleX")))

		// Retuning the swing reruns it and what reads it, nothing upstream and not the simulation.
		val retuned = overlay.copy(swingEdits = listOf(swing.copy(tilt = 10f)))
		assertEquals(listOf("swing:front", "overrides", "physics", "motions"), DocumentGenerators.stale(overlay, retuned))
		// A new skeleton reruns everything after the rig builder.
		val skeletal = overlay.copy(skeleton = SkeletonSpec())
		assertEquals(graph.order.map { it.id }.drop(1), DocumentGenerators.stale(overlay, skeletal))
		// Removing the simulation drops its keyforms: the overrides and physics read again.
		val removed = overlay.copy(simEdits = emptyList())
		assertEquals(listOf("overrides", "physics", "motions"), DocumentGenerators.stale(overlay, removed))
		// An override alone only merges again.
		val overridden = overlay.copy(authoringJournal = overlay.authoringJournal + buildJsonObject { put("op", GeneratedOverrides.OP) })
		assertEquals(listOf("overrides", "physics", "motions"), DocumentGenerators.stale(overlay, overridden))
	}

	@Test fun generatorsRunInGraphOrderAsBefore() {
		val layers = initial.analysis.layers.associateBy { it.source.id.raw }
		val front = initial.rig.puppet.drawables.first { d -> d.mesh != null &&
			layers[initial.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == SemanticTag.FRONT_HAIR }
		val overlay = SwingAuthoring.put(simulated(1f), initial.rig.puppet, RigSwingEdit.single("front", "Front", SwingKind.LATERAL,
			listOf(front.id.raw), listOf("ParamSwingFront")))
		val before = overlay.copy(swingEdits = emptyList(), simEdits = emptyList()).applyTo(initial.rig.puppet)
		val expected = SimGenerator.apply(SwingGenerator.apply(before, overlay.swingEdits), overlay.simEdits)
		assertEquals(io.github.psd2live.targets.cubism.PuppetIr.toIr(expected), io.github.psd2live.targets.cubism.PuppetIr.toIr(
			DocumentGenerators.generate(before, overlay)))
	}
}
