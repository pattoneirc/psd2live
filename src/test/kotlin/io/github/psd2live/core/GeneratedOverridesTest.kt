package io.github.psd2live.core

import io.github.psd2live.application.WorkspaceDocumentEdits
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.test.*

/** A user's edit of a swing-generated keyform survives the swing regenerating, merged point by point. */
class GeneratedOverridesTest {
	private val initial by lazy { PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd")) }

	private fun swingOverlay(magnitude: Float): RigEditOverlay {
		val puppet = initial.rig.puppet
		val layers = initial.analysis.layers.associateBy { it.source.id.raw }
		val back = puppet.drawables.first { d -> d.mesh != null && layers[initial.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == SemanticTag.BACK_HAIR }
		return SwingAuthoring.put(initial.config.rigEdits, puppet, RigSwingEdit.single("back", "Back", SwingKind.LATERAL, listOf(back.id.raw),
			listOf("ParamSwingBack"), shape = SwingShape(magnitude = magnitude, parallel = 0.7f)))
	}

	private fun warpOf(model: PuppetModel, id: String) = model.deformers.single { it.id.raw == id } as Deformer.Warp

	/** The swing's generated cell (its parameter at 1, every other axis at its default) and that key. */
	private fun swungCell(model: PuppetModel, warpId: String): Pair<Map<String, Float>, FloatArray> {
		val grid = warpOf(model, warpId).geometryGrid!!
		val key = grid.axes.associate { axis ->
			val parameter = model.parameters.single { it.id == axis.parameterId }
			axis.parameterId.raw to if (axis.parameterId.raw == "ParamSwingBack") parameter.max else parameter.default
		}
		val coordinate = IntArray(grid.axes.size) { a -> grid.axes[a].keys.indexOfFirst { kotlin.math.abs(it - key.getValue(grid.axes[a].parameterId.raw)) < 1e-3f } }
		return key to grid.cells.single { it.coordinate.contentEquals(coordinate) }.form.controlPoints
	}

	private fun edit(warpId: String, key: Map<String, Float>, points: FloatArray) = JsonArray(listOf(buildJsonObject {
		put("op", "canvas_geometry"); put("kind", "warp"); put("id", warpId)
		put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) })); put("preserve_image", false)
		put("points", JsonArray(points.map(::JsonPrimitive)))
	}))

	@Test fun editsOfGeneratedKeyformsMergeWithRegeneration() {
		val overlay = swingOverlay(0.25f)
		val warpId = overlay.swingEdits.single().targets.single()
		val generated = overlay.applyTo(initial.rig.puppet)
		val (key, cell) = swungCell(generated, warpId)
		// The tip (last point): the swing moves it, unlike the pinned top edge.
		val tip = cell.size - 2
		val moved = cell.copyOf().also { it[tip] += 12f; it[tip + 1] -= 6f }
		val document = WorkspaceDocument(initial.analysis.source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
			WorkspaceSettingsCodec.encode(initial.config))
		val authored = WorkspaceDocumentEdits.journal(document, initial.copy(rig = initial.rig.copy(puppet = generated)), edit(warpId, key, moved))
		val command = authored.rigEdits.authoringJournal.last()
		assertEquals(GeneratedOverrides.OP, command.getValue("op").jsonPrimitive.content, "a generated cell is captured as an override")
		assertEquals("swing:back", command.getValue("generator").jsonPrimitive.content)

		// Replayed after the swing: the edit shows.
		val replayed = authored.rigEdits.applyTo(initial.rig.puppet)
		assertContentEquals(moved, swungCell(replayed, warpId).second)

		// A stronger swing regenerates: untouched points follow it, the moved one keeps the user's position.
		val stronger = authored.rigEdits.copy(swingEdits = swingOverlay(0.6f).swingEdits)
		val regenerated = stronger.applyTo(initial.rig.puppet)
		val fresh = swingOverlay(0.6f).applyTo(initial.rig.puppet)
		val result = swungCell(regenerated, warpId).second
		val expected = swungCell(fresh, warpId).second
		assertEquals(moved[tip], result[tip]); assertEquals(moved[tip + 1], result[tip + 1])
		for (i in 0 until tip) assertEquals(expected[i], result[i], "point ${i / 2} follows the regenerated swing")
		assertNotEquals(cell[tip], expected[tip], "the stronger swing really moved the tip")
		val outcome = GeneratedOverrides.applyAll(SwingGenerator.apply(stronger.copy(swingEdits = emptyList(), authoringJournal =
			stronger.authoringJournal.filterNot { it.getValue("op").jsonPrimitive.content == GeneratedOverrides.OP }).applyTo(initial.rig.puppet),
			stronger.swingEdits), stronger.authoringJournal)
		assertEquals(1, outcome.conflicts.size, outcome.conflicts.toString())

		// Without the swing there is no generated cell: the override changes nothing and reports itself.
		val removed = authored.rigEdits.copy(swingEdits = emptyList())
		val plain = removed.applyTo(initial.rig.puppet)
		assertEquals(warpOf(overlay.copy(swingEdits = emptyList()).applyTo(initial.rig.puppet), warpId).geometryGrid!!.cells.size,
			warpOf(plain, warpId).geometryGrid!!.cells.size)
		assertTrue(GeneratedOverrides.applyAll(plain, removed.authoringJournal).conflicts.single().contains("no generated keyform"))
	}

	@Test fun anOrdinaryLatticeEditOfASwungCellCannotReplay() {
		// What overrides replace: replayed before the swing exists, a raw edit at its key cannot apply at all.
		val overlay = swingOverlay(0.25f)
		val warpId = overlay.swingEdits.single().targets.single()
		val generated = overlay.applyTo(initial.rig.puppet)
		val (key, cell) = swungCell(generated, warpId)
		val moved = cell.copyOf().also { it[0] += 12f }
		val raw = overlay.copy(authoringJournal = overlay.authoringJournal + edit(warpId, key, moved).single().jsonObject)
		assertFailsWith<IllegalArgumentException> { raw.applyTo(initial.rig.puppet) }
	}

	@Test fun editsAtTheSwingDefaultStayOrdinaryLatticeEdits() {
		val overlay = swingOverlay(0.25f)
		val warpId = overlay.swingEdits.single().targets.single()
		val generated = overlay.applyTo(initial.rig.puppet)
		val (key, _) = swungCell(generated, warpId)
		val atDefault = key + ("ParamSwingBack" to 0f)
		val captured = GeneratedOverrides.capture(generated, overlay, edit(warpId, atDefault, FloatArray(4)))
		assertEquals("canvas_geometry", captured.single().jsonObject.getValue("op").jsonPrimitive.content)
	}
}
