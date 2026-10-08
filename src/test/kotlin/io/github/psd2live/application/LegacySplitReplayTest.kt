package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
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
    private val language = I18n.currentLanguage

    // Generated names follow the UI language, and the project was saved (and its hashes taken) in Chinese.
    @BeforeTest fun pinLanguage() = I18n.setLanguage(AppLanguage.CHINESE, persist = false)
    @AfterTest fun restoreLanguage() = I18n.setLanguage(language, persist = false)

    private val hashes = mapOf(
        "history-a8855e4e-2343-4c25-ac37-5bbd5975f837" to "4a0b26852596e8ebcb3371a571667378edc016d7e515258298ecd7aabd2ff6a0",
        "history-35307cf9-8818-4ab3-9203-604da84ce587" to "b4290513e609f3c64a16989f2b247e9dc72db16f5cde4621f07c2bd6f94abb02",
        // The atlas is packed by rectangles (MaxRects) and, in the two split nodes, the soft-deleted original packs no tile, so
        // tiles and their uvs differ; geometry, keyforms and every other object are as that build replayed them.
        "history-9720a4c9-5233-4a5b-848c-220ad1c5d75c" to "ca99aaf7971bbbc12c7a3f3783cb3df16a93a213e60aee61a34e1cca61353af7",
        "history-0493aaf9-0a1c-4310-baf5-5896117ab92e" to "1c0d97c54f189f64a79c4f4f877a8b4e7f153658249d63b65d1f01bb8b48e7db",
    )

    private suspend fun open(): OpenedProject {
        val target = temporary.resolve("legacy-split.psd2live")
        LegacySplitReplayTest::class.java.getResourceAsStream("/projects/legacy-split.psd2live")!!.use { Files.copy(it, target) }
        return ProjectRepository().open(target)
    }

    // The IR's advanced block came after these hashes were taken; it is empty for a puppet and left out of the hash.
    private fun hash(model: RigPreviewModel): String {
        val ir = PuppetIr.toIr(model.rig.puppet)
        check(ir.advanced.isEmpty)
        return ContentHash.of(ir.toString().removeSuffix(", advanced=${ir.advanced})") + ")")
    }

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
