package io.github.psd2live.application

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.random.Random
import kotlin.test.*

/**
 * The commit gate compares only the targets its commands can move. On a generated rig with a swing, random
 * geometry edits, including ones that fold or collapse triangles, must give the scoped evaluation exactly
 * the full evaluation's classification: no target the full pass flags is left out of the scope.
 */
@Tag("slow")
class WorkspaceGeometrySafetyScopeTest {
    private val preview by lazy { PSD2LivePipeline().buildPreview(File("examples/tml/psd-input/tml.psd").toPath()) }

    private fun rebuild(overlay: RigEditOverlay): PuppetModel =
        preview.baseRig.withRigEdits(overlay, preview.config.layerVisibility, preview.config.drawOrderOverrides).puppet

    /** Everything but the coverage, which is what differs by design. */
    private fun classification(report: GeometrySafetyReport): JsonObject =
        JsonObject(report.toJson().filterKeys { it != "coverage" && it != "scope" })

    @Test fun scopedEvaluationMatchesFullOnRandomEdits() {
        val base = preview.baseRig.puppet
        val swingTarget = base.drawables.first { it.mesh != null && it.parentDeformerId != null }
        val overlay = SwingAuthoring.put(preview.config.rigEdits, preview.config.rigEdits.applyTo(base),
            RigSwingEdit.single("scope", "Scope", SwingKind.LATERAL, listOf(swingTarget.id.raw), listOf("ParamSwingScope"),
                shape = SwingShape(magnitude = 0.2f, parallel = 0.7f)))
        val before = rebuild(overlay)
        val targets = before.drawables.filter { it.mesh != null }.map { "mesh" to it.id.raw } +
            before.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>().map { "warp" to it.id.raw }
        val random = Random(20261006)
        var flagged = 0
        repeat(16) { round ->
            val (kind, id) = if (round == 0) "mesh" to swingTarget.id.raw else targets[random.nextInt(targets.size)]
            val points = RigGeometryTools.geometry(before, kind, id, emptyMap()).points.copyOf()
            // Small moves, then large ones that fold and collapse triangles.
            val amplitude = if (round % 2 == 0) 0.5f else 400f
            for (i in points.indices) points[i] += (random.nextFloat() - 0.5f) * amplitude
            val command = buildJsonObject {
                put("op", "canvas_geometry"); put("kind", kind); put("id", id)
                put("key", JsonObject(emptyMap())); put("pose", JsonObject(emptyMap()))
                if (kind == "mesh") put("preserve_image", true)
                put("points", JsonArray(points.map(::JsonPrimitive)))
            }
            val (_, journal) = RigAuthoringJournal.compile(before, JsonArray(listOf(command)))
            val edited = overlay.copy(authoringJournal = overlay.authoringJournal + journal)
            val candidate = rebuild(edited)
            val scope = assertNotNull(WorkspaceGeometrySafety.scope(overlay, edited), "geometry edits have a scope")
            assertTrue("$kind:$id" in scope)
            val full = GeometrySafetyEvaluator.evaluate(before, candidate, blockFoldovers = false)
            val scoped = GeometrySafetyEvaluator.evaluate(before, candidate, blockFoldovers = false, scope = scope)
            assertEquals(classification(full), classification(scoped), "round $round on $kind:$id")
            assertEquals(GeometrySafetyCoverage.SCOPED, scoped.coverage.mode)
            assertTrue(scoped.coverage.checkedTargets <= scoped.coverage.totalTargets)
            assertEquals(full.coverage.checkedTargets, scoped.coverage.totalTargets)
            if (full.violations.isNotEmpty() || full.warnings.isNotEmpty()) flagged++
        }
        assertTrue(flagged > 0, "the random edits include flagged geometry")
    }

    @Test fun restructuringCommandsFallBackToTheFullModel() {
        val overlay = preview.config.rigEdits
        fun appended(vararg commands: JsonObject) = overlay.copy(authoringJournal = overlay.authoringJournal + commands)
        val create = buildJsonObject { put("op", "canvas_create_warp"); put("id", "W"); put("name", "W") }
        assertNull(WorkspaceGeometrySafety.scope(overlay, appended(create)))
        val move = buildJsonObject {
            put("op", "structure"); putJsonArray("edits") { add(buildJsonObject { put("action", "move"); put("kind", "mesh"); put("id", "A") }) }
        }
        assertNull(WorkspaceGeometrySafety.scope(overlay, appended(move)))
        assertNull(WorkspaceGeometrySafety.scope(overlay, overlay), "nothing appended")
        val keyformOnly = buildJsonObject { put("op", "set"); put("target", "mesh:A"); putJsonObject("key") {} }
        assertNull(WorkspaceGeometrySafety.scope(overlay, appended(keyformOnly).copy(physicsFps = overlay.physicsFps + 1)), "other overlay changes")
        val glue = buildJsonObject { put("op", "canvas_glue_edit"); put("mesh_a", "A"); put("mesh_b", "B") }
        val keyform = buildJsonObject { put("op", "set"); put("target", "warp:W"); putJsonObject("key") {} }
        assertEquals(setOf("mesh:A", "mesh:B", "warp:W"), WorkspaceGeometrySafety.scope(overlay, appended(glue, keyform)))
    }
}
