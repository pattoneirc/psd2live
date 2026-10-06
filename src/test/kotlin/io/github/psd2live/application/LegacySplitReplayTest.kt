package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * A project split before splits were materialized: a component split that soft-deleted its original and a depth
 * split that kept its source as the back slice. Saved by that build, with the model hashes it replayed to.
 */
class LegacySplitReplayTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()

    private val hashes = mapOf(
        "history-a8855e4e-2343-4c25-ac37-5bbd5975f837" to "4b6091ae739d9cfc97bca6185e2dcfe4fb97cc194fab810f62389db93ddf848f",
        "history-35307cf9-8818-4ab3-9203-604da84ce587" to "7629519a31e7dc497f053ed392776c2c754fde9291a794723c6760f2e97d9884",
        "history-9720a4c9-5233-4a5b-848c-220ad1c5d75c" to "4e50bb3cdd9f110b2260cb9d0d7567ba9ebb823e3874bb6fe383d739e1764ae8",
        "history-0493aaf9-0a1c-4310-baf5-5896117ab92e" to "5a3814618e3da6913b6667d6fe5eaaaa2cdda74328615dce2a8c36d2c8da9933",
    )

    private suspend fun open(): OpenedProject {
        val target = temporary.resolve("legacy-split.psd2live")
        LegacySplitReplayTest::class.java.getResourceAsStream("/projects/legacy-split.psd2live")!!.use { Files.copy(it, target) }
        return ProjectRepository().open(target)
    }

    private fun hash(model: RigPreviewModel) = ContentHash.of(PuppetIr.toIr(model.rig.puppet)).toString()

    @Test fun legacySplitsReplayUnchangedAndAreNotUpgradedOnRead() = runBlocking<Unit> {
        open().use { opened ->
            val selections = opened.history.selections()
            assertEquals(hashes.keys, selections.map { it.node.id }.toSet())
            for (selection in selections) {
                assertEquals(hashes.getValue(selection.node.id), hash(builder.build(selection.snapshot)), selection.node.summary)
            }
            val head = opened.history.head().snapshot
            val ops = head.rigEdits.authoringJournal.map { it.getValue("op").jsonPrimitive.content }
            assertTrue(SourcePartitionJournal.OP in ops && DepthSplit.OP in ops)
            assertTrue(ArtPrimitiveJournal.commands(head.rigEdits).isEmpty())
            assertEquals(setOf("islands"), head.deletedLayerIds)
            assertTrue(head.source.layers.any { it.id.raw == "islands" })

            // The old rules still hold: the soft-deleted original comes back on restore, and a new split beside
            // the old ones is materialized.
            val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
            runtime.install(runtime.state.value.state, opened.projectId, head, builder.build(head))
            val start = runtime.capture()
            val restored = WorkspaceDocumentCommands(runtime).execute(start.projectId, start.state, "Restore", listOf(
                WorkspaceDocumentOperation("layer_restore", buildJsonObject { put("layer_ids", buildJsonArray { add("islands") }) })), MutationAuthor.USER).capture
            assertTrue(restored.model.rig.layerIdByDrawableId.values.contains("islands"))
            assertEquals(hash(restored.model), hash(builder.build(restored.document)))
            val split = WorkspacePartitionCommands(runtime).execute(start.projectId, runtime.capture().state, listOf(
                WorkspaceDocumentOperation("source_split_polygon", buildJsonObject {
                    put("layer_id", "other"); putJsonArray("names") { add("Left"); add("Right") }; putJsonArray("piece_ids") { add("left"); add("right") }
                    putJsonArray("polygon") { listOf(0 to 0, 23 to 0, 23 to 24, 0 to 24).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
                })), "Split beside legacy", MutationAuthor.USER).commit.capture
            assertTrue(split.document.source.layers.none { it.id.raw == "other" })
            assertEquals(1, ArtPrimitiveJournal.commands(split.document.rigEdits).size)
            assertEquals(hash(split.model), hash(builder.build(split.document)))
            runtime.checkout(split.projectId, split.state, start.historyHead)
            assertEquals(hashes.getValue(opened.history.head().node.id), hash(runtime.capture().model))
        }
    }
}
