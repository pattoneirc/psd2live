package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/** The explicit upgrade of version 1 split records: one undoable node, edits kept, parts generated from then on. */
class WorkspaceSplitUpgradeTest {
    private val builder = WorkspacePreviewBuilder()
    private val flag: String? = System.getProperty(ArtPrimitiveV2.FLAG_PROPERTY)
    private val provider = WorkspaceArtPrimitives.baseProvider
    @TempDir lateinit var temporary: Path

    @AfterEach fun reset() {
        if (flag == null) System.clearProperty(ArtPrimitiveV2.FLAG_PROPERTY) else System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, flag)
        WorkspaceArtPrimitives.baseProvider = provider
    }

    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val rgba = ByteArray(96 * 48 * 4)
        for (y in 8..39) for (x in (6..34) + (60..88)) {
            val offset = (y * 96 + x) * 4
            rgba[offset] = 90; rgba[offset + 1] = 120; rgba[offset + 2] = 150.toByte(); rgba[offset + 3] = 255.toByte()
        }
        val islands = WorkspaceSourceLayer(LayerId("islands"), "Islands", "", SourceLayerKind.Raster, true, 2,
            LayerBounds(0, 0, 96, 48), 1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(96, 48, rgba), null, null, false)
        val other = islands.copy(id = LayerId("other"), name = "Other", order = 1, bounds = LayerBounds(30, 10, 30, 24),
            raster = LayerRaster(30, 24, ByteArray(30 * 24 * 4) { if (it % 4 == 3) 255.toByte() else 70 }))
        val document = WorkspaceDocument(WorkspaceSourceArt(96, 48, listOf(islands, other), emptyList()), emptyMap(), emptySet(),
            mapOf("islands" to LayerClassificationOverride(tag = SemanticTag.OBJECTS), "other" to LayerClassificationOverride(tag = SemanticTag.OBJECTS)),
            emptyMap(), RigEditOverlay.Empty,
            WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, exportMoc3 = false, generatePhysics = false)))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "upgrade", document, builder.build(document))
        return runtime
    }

    private fun mesh(model: RigPreviewModel, layer: String) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == layer }

    /** A user shape key, a user path and a Glue on the original, then a version 1 split of it. */
    private suspend fun splitV1(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
        val root = runtime.capture(); val source = mesh(root.model, "islands"); val other = mesh(root.model, "other")
        val count = source.mesh!!.vertexCount
        val deltas = JsonArray(List(count * 2) { JsonPrimitive(kotlin.math.sin(it.toDouble()).toFloat() * 0.5f) })
        val positions = source.mesh!!.positions
        val commands = WorkspaceDocumentCommands(runtime)
        val authored = commands.execute(root.projectId, root.state, "Author original", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("canvas_glue", buildJsonObject { put("id", "Weld"); put("mesh_a", source.id.raw); put("mesh_b", other.id.raw); put("distance", 256) }),
            WorkspaceDocumentOperation("path_put", buildJsonObject { put("id", "Spine"); put("target", "mesh:${source.id.raw}"); putJsonArray("points") {
                add(buildJsonArray { add(positions[0]); add(positions[1]) }); add(buildJsonArray { add(positions[2]); add(positions[3]) })
            } }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                add(buildJsonObject { put("op", "set"); put("target", "mesh:${source.id.raw}"); putJsonObject("key") { put("Shape", 1) }
                    putJsonObject("geometry") { put("positionDeltas", deltas) } })
            } })), MutationAuthor.USER).capture
        preSplit = authored
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "false")
        val split = commands.execute(authored.projectId, authored.state, "Split", listOf(WorkspaceDocumentOperation("source_split_components", buildJsonObject {
            put("layer_id", "islands"); put("names", JsonArray(listOf("First", "Second").map(::JsonPrimitive)))
            put("piece_ids", JsonArray(listOf("first", "second").map(::JsonPrimitive)))
        })), MutationAuthor.USER).capture
        assertEquals(listOf(1), records(split.document).map { it.getValue("v").jsonPrimitive.int })
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true")
        return split
    }

    private var preSplit: WorkspaceCapture<RigPreviewModel>? = null

    private fun records(document: WorkspaceDocument) = ArtPrimitiveJournal.commands(document.rigEdits)
    /** The first paths where [a] and [b] differ beyond float rounding (a polygon cut interpolates its vertices). */
    private fun difference(a: JsonElement?, b: JsonElement?, path: String): List<String> = when {
        a == b -> emptyList()
        a is JsonPrimitive && b is JsonPrimitive && a.doubleOrNull != null && b.doubleOrNull != null &&
            abs(a.double - b.double) <= 1e-5 -> emptyList()
        a is JsonObject && b is JsonObject -> (a.keys + b.keys).flatMap { difference(a[it], b[it], "$path.$it") }
        a is JsonArray && b is JsonArray && a.size == b.size -> a.indices.flatMap { difference(a[it], b[it], "$path[$it]") }
        else -> listOf("$path: ${a.toString().take(200)} vs ${b.toString().take(200)}")
    }.take(8)

    private fun hash(model: RigPreviewModel) = ContentHash.of(RigIrCompiler.compile(model))

    private fun toggle(capture: WorkspaceCapture<RigPreviewModel>) = WorkspaceDocumentOperation("layer_classify", buildJsonObject {
        put("layer_id", "first"); put("type", "TOGGLE"); put("parameter", "ParamFirstToggle")
    })

    @Test fun anUpgradeRewritesTheRecordInOneUndoableNodeAndKeepsTheEdits() = runBlocking<Unit> {
        val runtime = fixture(); val v1 = splitV1(runtime)
        assertTrue(WorkspaceSplitUpgradeEdits.upgradable(v1.document.rigEdits))
        val upgraded = WorkspaceSplitUpgradeCommands(runtime).execute(v1.projectId, v1.state, null, "Upgrade", MutationAuthor.USER)
        val result = upgraded.result
        val report = result.getValue("records").jsonArray.single().jsonObject
        assertTrue(report.getValue("upgraded").jsonPrimitive.boolean, report.toString())
        assertEquals(0, report.getValue("index").jsonPrimitive.int)
        assertEquals(listOf("islands"), report.getValue("layers").jsonArray.map { it.jsonPrimitive.content })
        validateOperationSchema(result, WorkspaceJobResultSchemas.splitUpgrade)
        val after = upgraded.commit.capture
        assertTrue(upgraded.commit.applied)
        assertNotEquals(v1.historyHead, after.historyHead)
        val record = records(after.document).single()
        assertTrue(ArtPrimitiveV2.isV2(record))
        assertFalse(WorkspaceSplitUpgradeEdits.upgradable(after.document.rigEdits))
        // The record keeps its place: the journal before it is untouched, and the parts keep their ids.
        val before = v1.document.rigEdits.authoringJournal
        val position = before.indexOfFirst(ArtPrimitiveJournal::isRecord)
        assertEquals(before.take(position), after.document.rigEdits.authoringJournal.take(position))
        assertEquals(ArtPrimitiveJournal.primitives(records(v1.document).single()).map { it.getValue("id") },
            ArtPrimitiveJournal.primitives(record).map { it.getValue("id") })
        // Version 1 pinned the parts' parents; the upgrade leaves them to generation.
        assertEquals(setOf("first", "second"), v1.document.parentOverrides.keys)
        assertTrue(after.document.parentOverrides.isEmpty())

        // The user's shape key, path and Glue are kept: the parts move as before at every key.
        val parts = listOf("first", "second").map { mesh(v1.model, it).id }
        val evaluator = CpuDeformationEvaluator()
        for (shape in listOf(-1f, 0f, 1f)) {
            val pose = mapOf(ParameterId("Shape") to shape)
            val a = evaluator.evaluate(v1.model.rig.puppet, pose).worldPositions; val b = evaluator.evaluate(after.model.rig.puppet, pose).worldPositions
            for (id in parts) {
                val x = a.getValue(id); val y = b.getValue(id)
                assertEquals(x.size, y.size)
                for (i in x.indices) assertTrue(abs(x[i] - y[i]) < 0.05f, "${id.raw} at Shape=$shape coordinate $i: ${x[i]} vs ${y[i]}")
            }
        }
        assertTrue(after.model.rig.puppet.glues.any { it.id == "Weld" })
        assertTrue(after.model.rig.puppet.deformPaths.any { it.id.endsWith("/Spine") })

        // Rewriting an earlier entry invalidates the replay checkpoints: a build warm from the version 1 model and a cold one agree.
        assertEquals(hash(after.model), hash(builder.build(after.document, v1.model)))
        assertEquals(hash(after.model), hash(WorkspacePreviewBuilder().build(after.document)))

        // Save and reopen give the same document and rig.
        val archive = temporary.resolve("upgraded.psd2live")
        ProjectRepository().save(ProjectSaveCapture(after.projectId, runtime.history(), JsonObject(emptyMap()), null,
            WorkspaceStore(temporary.resolve("store"))), archive)
        val reopened = ProjectRepository().open(archive).use { it.history.head().snapshot }
        assertEquals(WorkspaceRevisions.of(after.document), WorkspaceRevisions.of(reopened))
        assertEquals(hash(after.model), hash(builder.build(reopened)))

        // Undo returns to the version 1 document exactly.
        val undone = runtime.checkout(after.projectId, after.state, v1.historyHead)
        assertEquals(WorkspaceRevisions.of(v1.document), WorkspaceRevisions.of(undone.document))
        assertEquals(v1.document, undone.document)
        assertEquals(hash(v1.model), hash(undone.model))
    }

    /** With nothing changed since the split, the upgrade writes exactly what a version 2 split would have written. */
    @Test fun anUpgradeWritesWhatAVersionTwoSplitWould() = runBlocking<Unit> {
        val runtime = fixture(); val v1 = splitV1(runtime)
        val authored = requireNotNull(preSplit)
        val fresh = WorkspacePartitionEdits.apply(WorkspaceDocumentOperation("source_split_components", buildJsonObject {
            put("layer_id", "islands"); put("names", JsonArray(listOf("First", "Second").map(::JsonPrimitive)))
            put("piece_ids", JsonArray(listOf("first", "second").map(::JsonPrimitive)))
        }), authored.document, authored.model)
        assertTrue(ArtPrimitiveV2.isV2(records(fresh).single()))
        val upgraded = WorkspaceSplitUpgradeEdits.upgrade(v1.document, v1.model, null).document
        assertEquals(fresh.rigEdits.authoringJournal, upgraded.rigEdits.authoringJournal)
        assertEquals(fresh.parentOverrides, upgraded.parentOverrides)
        assertEquals(fresh.rigEdits.skeleton, upgraded.rigEdits.skeleton)
    }

    /** A version 1 split of a version 1 part upgrades after the record that made the part, in one node. */
    @Test fun aSplitOfAPartUpgradesInJournalOrder() = runBlocking<Unit> {
        val runtime = fixture(); val v1 = splitV1(runtime)
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "false")
        val again = WorkspaceDocumentCommands(runtime).execute(v1.projectId, v1.state, "Split again", listOf(
            WorkspaceDocumentOperation("source_split_polygon", buildJsonObject {
                put("layer_id", "first"); put("names", JsonArray(listOf("Top", "Bottom").map(::JsonPrimitive)))
                put("piece_ids", JsonArray(listOf("top", "bottom").map(::JsonPrimitive)))
                putJsonArray("polygon") { listOf(0 to 0, 96 to 0, 96 to 24, 0 to 24).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
            })), MutationAuthor.USER).capture
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true")
        assertEquals(listOf(false, false), records(again.document).map(ArtPrimitiveV2::isV2))
        val upgraded = WorkspaceSplitUpgradeCommands(runtime).execute(again.projectId, again.state, null, "Upgrade", MutationAuthor.USER)
        val reports = upgraded.result.getValue("records").jsonArray.map { it.jsonObject }
        assertEquals(listOf(0, 1), reports.map { it.getValue("index").jsonPrimitive.int })
        assertTrue(reports.all { it.getValue("upgraded").jsonPrimitive.boolean }, reports.toString())
        val after = upgraded.commit.capture
        assertEquals(listOf(true, true), records(after.document).map(ArtPrimitiveV2::isV2))
        assertEquals(setOf("top", "bottom", "second"), after.model.rig.layerIdByDrawableId.values.toSet() - "other")
        assertEquals(hash(after.model), hash(WorkspacePreviewBuilder().build(after.document)))
        // One node for both records.
        assertEquals(again.historyHead, runtime.history().selections.single { it.node.id == after.historyHead }.node.parentId)
    }

    /** Polygon cuts (interpolated vertices) and depth slices upgrade to the record a version 2 split writes too. */
    @Test fun polygonAndDepthRecordsUpgradeLikeAVersionTwoSplit() = runBlocking<Unit> {
        val runtime = fixture(); splitV1(runtime)
        val authored = requireNotNull(preSplit)
        val source = mesh(authored.model, "islands"); val other = mesh(authored.model, "other")
        val splits = listOf(
            WorkspaceDocumentOperation("source_split_polygon", buildJsonObject {
                put("layer_id", "islands"); put("names", JsonArray(listOf("Top", "Bottom").map(::JsonPrimitive)))
                put("piece_ids", JsonArray(listOf("top", "bottom").map(::JsonPrimitive)))
                putJsonArray("polygon") { listOf(0 to 0, 96 to 0, 96 to 21, 0 to 27).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
            }),
            WorkspaceDocumentOperation("source_split_depth", buildJsonObject {
                put("source_id", source.id.raw); put("middle_ids", JsonArray(listOf(JsonPrimitive(other.id.raw))))
                put("front_layer_id", "front"); put("front_mesh_id", "FrontMesh"); put("back_layer_id", "back"); put("back_mesh_id", "BackMesh")
                put("glue_id", "DepthGlue")
            }))
        for (split in splits) {
            System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "false")
            val legacy = WorkspaceDocumentEdits.apply(split, authored.document, authored.model)
            assertEquals(listOf(1), records(legacy).map { it.getValue("v").jsonPrimitive.int }, split.operation)
            System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true")
            val fresh = WorkspaceDocumentEdits.apply(split, authored.document, authored.model)
            assertTrue(ArtPrimitiveV2.isV2(records(fresh).single()), "${split.operation}: ${records(fresh).single()[ArtPrimitiveV2.FALLBACK]}")
            val result = WorkspaceSplitUpgradeEdits.upgrade(legacy, builder.build(legacy), null)
            assertTrue(result.records.single().upgraded, "${split.operation}: ${result.records}")
            assertEquals(emptyList(), difference(JsonArray(fresh.rigEdits.authoringJournal),
                JsonArray(result.document.rigEdits.authoringJournal), "journal"), split.operation)
            assertEquals(fresh.parentOverrides, result.document.parentOverrides, split.operation)
        }
    }

    @Test fun upgradedPartsFollowAGenerationChangeVersionOnePartsMiss() = runBlocking<Unit> {
        val runtime = fixture(); val v1 = splitV1(runtime)
        val commands = WorkspaceDocumentCommands(runtime)
        // Version 1: the part is a snapshot, so classifying its layer as a toggle never reaches it.
        val legacy = commands.execute(v1.projectId, v1.state, "Toggle", listOf(toggle(v1)), MutationAuthor.USER).capture
        assertTrue(mesh(legacy.model, "first").channelGrids.gridsByChannel.values.none { grid ->
            grid.axes.any { it.parameterId == ParameterId("ParamFirstToggle") } })
        val undone = runtime.checkout(legacy.projectId, legacy.state, v1.historyHead)
        // As a batch member, then the same classification: the generated toggle reaches the upgraded part.
        val upgraded = commands.execute(undone.projectId, undone.state, "Upgrade", listOf(
            WorkspaceDocumentOperation(WorkspaceSplitUpgradeEdits.OP, JsonObject(emptyMap()))), MutationAuthor.USER).capture
        assertTrue(ArtPrimitiveV2.isV2(records(upgraded.document).single()))
        val toggled = commands.execute(upgraded.projectId, upgraded.state, "Toggle", listOf(toggle(upgraded)), MutationAuthor.USER).capture
        assertTrue(mesh(toggled.model, "first").channelGrids.gridsByChannel.values.any { grid ->
            grid.axes.any { it.parameterId == ParameterId("ParamFirstToggle") } })
        assertEquals(hash(toggled.model), hash(WorkspacePreviewBuilder().build(toggled.document)))
    }

    @Test fun aFailedGuardKeepsVersionOneAndCommitsNothing() = runBlocking<Unit> {
        val runtime = fixture(); val v1 = splitV1(runtime)
        WorkspaceArtPrimitives.baseProvider = PrimitiveBaseProvider.Unavailable
        val failed = WorkspaceSplitUpgradeCommands(runtime).execute(v1.projectId, v1.state, listOf(0), "Upgrade", MutationAuthor.USER)
        val report = failed.result.getValue("records").jsonArray.single().jsonObject
        assertFalse(report.getValue("upgraded").jsonPrimitive.boolean)
        assertEquals(WorkspaceArtPrimitives.REASON_NO_BASE, report.getValue("reason").jsonPrimitive.content)
        validateOperationSchema(failed.result, WorkspaceJobResultSchemas.splitUpgrade)
        assertFalse(failed.commit.applied)
        assertFalse(failed.result.getValue("applied").jsonPrimitive.boolean)
        assertEquals(v1.historyHead, failed.commit.capture.historyHead)
        assertEquals(v1.document, failed.commit.capture.document)

        // With the flag off nothing is upgraded either, and an index past the records is an error.
        WorkspaceArtPrimitives.baseProvider = provider
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "false")
        val current = runtime.capture()
        val disabled = WorkspaceSplitUpgradeEdits.upgrade(current.document, current.model, null)
        assertEquals(listOf(WorkspaceArtPrimitives.REASON_DISABLED), disabled.records.map { it.reason })
        assertSame(current.document, disabled.document)
        assertFailsWith<IllegalArgumentException> { WorkspaceSplitUpgradeEdits.upgrade(current.document, current.model, listOf(1)) }
    }

    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val after: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        override fun captureQueries(): WorkspaceQueries = WorkspaceReadSession(runtime.read())
        override fun snapshot() = captureQueries().snapshot()
        override suspend fun upgradeSplitRecords(state: String, indexes: List<Int>?, author: MutationAuthor): JsonObject {
            val result = WorkspaceSplitUpgradeCommands(runtime).execute(runtime.capture().projectId, state, indexes, "Upgrade", author)
            after(); return result.result
        }
    }

    /** The public operation is a process job and batch member; a late refresh failure keeps the committed result. */
    @Test fun thePublicOperationRunsAsAJobAndKeepsItsResult() = runBlocking<Unit> {
        val runtime = fixture(); val v1 = splitV1(runtime)
        val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
        WorkspaceOperations(Host(runtime) { throw java.io.IOException("Refresh failed") }).use { operations ->
            val definition = operations.registry.definition(WorkspaceSplitUpgradeEdits.OP)
            assertTrue(definition.jobBacked && definition.batchable)
            val request = buildJsonObject {
                put("project_id", v1.projectId); put("state", v1.state); put("request_id", "upgrade")
                put("record_indexes", JsonArray(listOf(JsonPrimitive(0))))
            }
            val job = operations.registry.invoke(WorkspaceSplitUpgradeEdits.OP, request, agent).data
            val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data
            assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
            val result = terminal.getValue("result").jsonObject
            validateOperationSchema(result, definition.jobResultSchema!!)
            assertEquals(runtime.capture().state, result.getValue("state").jsonPrimitive.content)
            assertTrue(result.getValue("records").jsonArray.single().jsonObject.getValue("upgraded").jsonPrimitive.boolean)
            assertTrue(ArtPrimitiveV2.isV2(records(runtime.capture().document).single()))
        }
    }

    @Test fun nothingToUpgradeIsANoOpWithoutAHistoryNode() = runBlocking<Unit> {
        val runtime = fixture(); val v1 = splitV1(runtime)
        val first = WorkspaceSplitUpgradeCommands(runtime).execute(v1.projectId, v1.state, null, "Upgrade", MutationAuthor.USER).commit.capture
        val again = WorkspaceSplitUpgradeCommands(runtime).execute(first.projectId, first.state, null, "Upgrade", MutationAuthor.USER)
        assertEquals(0, again.result.getValue("records").jsonArray.size)
        assertFalse(again.commit.applied)
        assertEquals(first.historyHead, again.commit.capture.historyHead)
        // Naming a version 2 record reports it without changing anything.
        val named = WorkspaceSplitUpgradeCommands(runtime).execute(first.projectId, again.commit.capture.state, listOf(0), "Upgrade", MutationAuthor.USER)
        assertEquals(WorkspaceArtPrimitives.REASON_ALREADY, named.result.getValue("records").jsonArray.single().jsonObject.getValue("reason").jsonPrimitive.content)
        assertFalse(named.commit.applied)
    }
}
