package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** A split's parts are the only drawables left: the original leaves the source art, the atlas and every export. */
@Tag("slow")
class WorkspaceMaterializedSplitTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()

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
        runtime.install(runtime.state.value.state, "materialized", document, builder.build(document))
        return runtime
    }

    private fun mesh(model: RigPreviewModel, layer: String) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == layer }

    /** Keyforms, a blend shape, a path, weights and a Glue authored on the original before it is split. */
    private suspend fun author(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
        val root = runtime.capture(); val source = mesh(root.model, "islands"); val other = mesh(root.model, "other")
        val count = source.mesh!!.vertexCount
        val deltas = JsonArray(List(count * 2) { JsonPrimitive(kotlin.math.sin(it.toDouble()).toFloat() * 0.5f) })
        val positions = source.mesh!!.positions
        return WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Author original", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Blend"); put("name", "Blend"); put("kind", "blend_shape") }),
            WorkspaceDocumentOperation("canvas_glue", buildJsonObject { put("id", "Weld"); put("mesh_a", source.id.raw); put("mesh_b", other.id.raw); put("distance", 256) }),
            WorkspaceDocumentOperation("path_put", buildJsonObject { put("id", "Spine"); put("target", "mesh:${source.id.raw}"); putJsonArray("points") {
                add(buildJsonArray { add(positions[0]); add(positions[1]) }); add(buildJsonArray { add(positions[2]); add(positions[3]) })
            } }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                add(buildJsonObject { put("op", "set"); put("target", "mesh:${source.id.raw}"); putJsonObject("key") { put("Shape", 1) }
                    putJsonObject("geometry") { put("positionDeltas", deltas) }; putJsonObject("channels") { put("opacity", 0.4) } })
                add(buildJsonObject { put("op", "set"); put("target", "mesh:${source.id.raw}"); putJsonObject("key") { put("Blend", 1) }
                    putJsonObject("geometry") { put("positionDeltas", deltas) } })
                add(VertexGroupJournal.encode(VertexGroup("Pins", source.id, VertexGroupKind.PIN, FloatArray(count) { it.toFloat() / count })))
            } })), MutationAuthor.USER).capture
    }

    private fun split(kind: String, layer: String = "islands", ids: List<String> = listOf("first", "second")) = WorkspaceDocumentOperation(kind, buildJsonObject {
        put("layer_id", layer); put("names", JsonArray(ids.map { JsonPrimitive(it.replaceFirstChar(Char::uppercase)) }))
        put("piece_ids", JsonArray(ids.map(::JsonPrimitive)))
        if (kind == "source_split_polygon") putJsonArray("polygon") { listOf(0 to 0, 48 to 0, 48 to 48, 0 to 48).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
    })

    private fun hash(model: RigPreviewModel) = ContentHash.of(io.github.psd2live.targets.cubism.PuppetIr.toIr(model.rig.puppet))

    private fun assertSamePoses(expected: PuppetModel, actual: PuppetModel, ids: Collection<DrawableId>, tolerance: Float = 0.001f) {
        val evaluator = CpuDeformationEvaluator()
        for (shape in listOf(-1f, 0f, 1f)) for (blend in listOf(0f, 1f)) {
            val pose = mapOf(ParameterId("Shape") to shape, ParameterId("Blend") to blend)
            val a = evaluator.evaluate(expected, pose); val b = evaluator.evaluate(actual, pose)
            for (id in ids) {
                val xy = a.worldPositions.getValue(id)
                xy.indices.forEach { assertEquals(xy[it], b.worldPositions.getValue(id)[it], tolerance, "${id.raw} $pose vertex ${it / 2}") }
                assertEquals(a.opacity.getValue(id), b.opacity.getValue(id), tolerance)
            }
        }
    }

    @Test fun partsReplaceTheOriginalThroughReopenExportRestoreAndUndo() = runBlocking<Unit> {
        for (kind in WorkspacePartitionEdits.supported) {
            val runtime = fixture(); val before = author(runtime)
            val original = mesh(before.model, "islands")
            val result = WorkspacePartitionCommands(runtime).execute(before.projectId, before.state, listOf(split(kind)), "Split", MutationAuthor.USER).commit.capture
            val document = result.document; val puppet = result.model.rig.puppet
            // The parts are ordinary layers; the original is neither in the source art nor soft-deleted.
            assertEquals(listOf("first", "second", "other"), document.source.layers.map { it.id.raw })
            assertTrue(document.deletedLayerIds.isEmpty())
            assertEquals(1, ArtPrimitiveJournal.commands(document.rigEdits).size)
            assertTrue(SourcePartitionJournal.commands(document.rigEdits).isEmpty())
            assertTrue(puppet.drawables.none { it.id == original.id })
            assertTrue(result.model.analysis.layers.none { it.source.id.raw == "islands" })
            assertTrue(puppet.atlas.tiles.none { it.source?.layerKey == "islands" })
            assertTrue(puppet.sources.flatMap { it.layers }.none { it.key == "islands" })
            assertTrue(puppet.glues.none { it.meshA == original.id || it.meshB == original.id })
            val parts = listOf("first", "second").map { mesh(result.model, it) }
            for (part in parts) {
                assertEquals(listOf(ParameterId("Shape")), part.geometryGrid!!.axes.map { it.parameterId })
                assertEquals(listOf(ParameterId("Blend")), part.blendShapes.map { it.parameterId })
                assertEquals(listOf("${part.id.raw}/Spine"), puppet.deformPaths.filter { it.drawableId == part.id }.map { it.id })
                assertEquals(part.mesh!!.vertexCount, puppet.vertexGroups.single { it.drawableId == part.id && it.name == "Pins" }.weights.size)
            }
            assertTrue(puppet.glues.any { glue -> parts.any { it.id == glue.meshA } && glue.meshB == mesh(result.model, "other").id })
            // The parts move exactly as the original's vertices did.
            val evaluator = CpuDeformationEvaluator()
            for (pose in listOf(mapOf(ParameterId("Shape") to 1f), mapOf(ParameterId("Blend") to 1f), emptyMap())) {
                val old = evaluator.evaluate(before.model.rig.puppet, pose); val now = evaluator.evaluate(puppet, pose)
                val oldXY = old.worldPositions.getValue(original.id)
                for (part in parts) {
                    val rest = part.mesh!!.positions
                    for (vertex in 0 until part.mesh!!.vertexCount) {
                        val from = (0 until original.mesh!!.vertexCount).firstOrNull { o ->
                            original.mesh!!.positions[o * 2] == rest[vertex * 2] && original.mesh!!.positions[o * 2 + 1] == rest[vertex * 2 + 1]
                        } ?: continue
                        for (axis in 0..1) assertEquals(oldXY[from * 2 + axis], now.worldPositions.getValue(part.id)[vertex * 2 + axis], 0.0002f)
                    }
                    assertEquals(old.opacity.getValue(original.id), now.opacity.getValue(part.id), 0.00001f)
                }
            }
            // Save, reopen: the same document and the same model, bit for bit.
            val archive = temporary.resolve("$kind.psd2live")
            ProjectRepository().save(ProjectSaveCapture(result.projectId, runtime.history(), JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("store-$kind"))), archive)
            val reopened = ProjectRepository().open(archive).use { it.history.head().snapshot }
            assertEquals(WorkspaceRevisions.of(document), WorkspaceRevisions.of(reopened))
            assertEquals(hash(result.model), hash(builder.build(reopened)))
            // Exports carry the parts only.
            val exported = PSD2LivePipeline().run(document.source, "split", temporary.resolve("export-$kind"),
                document.config().copy(exportMoc3 = true)).exportedFiles
            val cmo3 = Cmo3ModelImport.read(Files.readAllBytes(exported.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
            assertTrue(cmo3.sources.flatMap { it.layers }.none { it.key == "islands" || it.name == "Islands" })
            assertEquals(setOf("First", "Second"), cmo3.sources.flatMap { it.layers }.map { it.name }.filter { it in setOf("First", "Second") }.toSet())
            assertTrue(cmo3.drawables.none { it.id == original.id })
            val moc3 = Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(exported.single { it.path.toString().endsWith(".moc3") }.path)), null)
            assertSamePoses(puppet, cmo3, parts.map { it.id })
            assertSamePoses(puppet, moc3, parts.map { it.id })
            // Restoring cannot bring the original back; references to it name the parts.
            val commands = WorkspaceDocumentCommands(runtime)
            val named = assertFailsWith<IllegalArgumentException> { commands.execute(result.projectId, result.state, "Restore original",
                listOf(WorkspaceDocumentOperation("layer_restore", buildJsonObject { put("layer_ids", buildJsonArray { add("islands") }) })), MutationAuthor.AGENT) }
            assertTrue("first" in named.message!! && "second" in named.message!!, named.message)
            assertFalse(commands.execute(result.projectId, result.state, "Restore all",
                listOf(WorkspaceDocumentOperation("layer_restore", buildJsonObject {})), MutationAuthor.AGENT).applied)
            val mesh = assertFailsWith<IllegalArgumentException> { commands.execute(result.projectId, result.state, "Old mesh",
                listOf(WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:${original.id.raw}"); putJsonObject("key") { put("Shape", -1) }; putJsonObject("channels") { put("opacity", 0.2) }
                }) } })), MutationAuthor.AGENT) }
            assertTrue(parts.all { it.id.raw in mesh.message!! }, mesh.message)
            // A part can be hidden and restored like any layer.
            val hidden = commands.execute(result.projectId, result.state, "Hide", listOf(WorkspaceDocumentOperation("layer_soft_delete",
                buildJsonObject { put("layer_id", "second") })), MutationAuthor.USER).capture
            assertTrue(hidden.model.rig.puppet.drawables.none { it.id == parts[1].id })
            val shown = commands.execute(hidden.projectId, hidden.state, "Show", listOf(WorkspaceDocumentOperation("layer_restore", buildJsonObject {})), MutationAuthor.USER).capture
            assertEquals(puppet.drawables.map { it.id }, shown.model.rig.puppet.drawables.map { it.id })
            // Only undo returns to the original.
            runtime.checkout(result.projectId, runtime.capture().state, before.historyHead)
            assertEquals(before.document, runtime.capture().document)
            assertEquals(hash(before.model), hash(runtime.capture().model))
        }
    }

    @Test fun aPartSplitsAgainAndMeshSettingsRebuildAPart() = runBlocking<Unit> {
        val runtime = fixture(); val before = author(runtime)
        val first = WorkspacePartitionCommands(runtime).execute(before.projectId, before.state,
            listOf(split("source_split_components")), "Split", MutationAuthor.USER).commit.capture
        val second = WorkspacePartitionCommands(runtime).execute(first.projectId, first.state,
            listOf(split("source_split_polygon", "first", listOf("upper", "lower")).let { op ->
                op.copy(request = JsonObject(op.request + buildJsonObject { putJsonArray("polygon") {
                    listOf(0 to 0, 96 to 0, 96 to 24, 0 to 24).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) }
                } }))
            }), "Split a part", MutationAuthor.USER).commit.capture
        assertEquals(listOf("upper", "lower", "second", "other"), second.document.source.layers.map { it.id.raw })
        assertEquals(2, ArtPrimitiveJournal.commands(second.document.rigEdits).size)
        assertEquals(listOf("upper", "lower"), ArtPrimitiveJournal.replacementLayers(second.document.rigEdits).getValue("first"))
        assertTrue(second.model.rig.puppet.atlas.tiles.none { it.source?.layerKey in setOf("islands", "first") })
        assertEquals(hash(second.model), hash(builder.build(second.document)))
        // A mesh setting regenerates a part through the materialized mesh rebuild, keeping its keyforms.
        val remeshed = WorkspaceDocumentCommands(runtime).execute(second.projectId, second.state, "Remesh", listOf(
            WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject { put("layer_id", "second"); putJsonObject("changes") { put("outerMargin", 7); put("interiorDensity", 6) } })),
            MutationAuthor.USER).capture
        val rebuilt = remeshed.document.rigEdits.authoringJournal.last { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP }
        assertEquals(mesh(second.model, "second").id.raw, rebuilt.getValue("id").jsonPrimitive.content)
        assertFalse(mesh(second.model, "second").mesh!!.positions.contentEquals(mesh(remeshed.model, "second").mesh!!.positions))
        assertEquals(listOf(ParameterId("Shape")), mesh(remeshed.model, "second").geometryGrid!!.axes.map { it.parameterId })
        assertEquals(hash(remeshed.model), hash(builder.build(remeshed.document)))
        // Painting a part beyond its pixels, keeping or rebuilding its mesh, keeps its keyforms.
        var state = remeshed
        for (rebuild in listOf(false, true)) {
            state = WorkspaceDocumentCommands(runtime).execute(state.projectId, state.state, "Paint part", listOf(
                WorkspaceDocumentOperation("source_paint_brush", buildJsonObject {
                    put("layer_id", "second"); put("radius", 3); put("rebuild_mesh", rebuild)
                    put("points", buildJsonArray { add(buildJsonArray { add(74); add(42) }); add(buildJsonArray { add(80); add(44) }) })
                    put("color", buildJsonArray { add(220); add(70); add(40); add(255) })
                })), MutationAuthor.USER).capture
            assertEquals(listOf(ParameterId("Shape")), mesh(state.model, "second").geometryGrid!!.axes.map { it.parameterId })
            assertEquals(hash(state.model), hash(builder.build(state.document)))
        }
        assertTrue(state.model.rig.puppet.atlas.tiles.none { it.source?.layerKey in setOf("islands", "first") })
    }

    @Test fun depthSlicesReplaceTheSourceAndLeaveNoCopyOfIt() = runBlocking<Unit> {
        val runtime = fixture(); val before = author(runtime)
        val source = mesh(before.model, "islands"); val middle = mesh(before.model, "other")
        val result = WorkspacePartitionCommands(runtime).execute(before.projectId, before.state, listOf(WorkspaceDocumentOperation("source_split_depth", buildJsonObject {
            put("source_id", source.id.raw); putJsonArray("middle_ids") { add(middle.id.raw) }
            put("front_layer_id", "front"); put("front_mesh_id", "Front"); put("back_layer_id", "back"); put("back_mesh_id", "Back"); put("glue_id", "Depth")
        })), "Depth", MutationAuthor.USER).commit.capture
        assertEquals(listOf("front", "back", "other"), result.document.source.layers.map { it.id.raw })
        assertTrue(result.document.source.layers.none { (it as WorkspaceSourceLayer).derived })
        val puppet = result.model.rig.puppet
        assertTrue(puppet.drawables.none { it.id == source.id })
        assertTrue(puppet.atlas.tiles.none { it.source?.layerKey == "islands" })
        assertEquals(DrawableId("Back"), puppet.glues.single { it.id == "Weld" }.meshA)
        assertTrue(DepthSplit.isFrontLayer(result.model, "front")); assertFalse(DepthSplit.isFrontLayer(result.model, "back"))
        for (pose in listOf(mapOf(ParameterId("Shape") to -1f), mapOf(ParameterId("Shape") to 1f), mapOf(ParameterId("Blend") to 1f))) {
            val old = CpuDeformationEvaluator().evaluate(before.model.rig.puppet, pose); val now = CpuDeformationEvaluator().evaluate(puppet, pose)
            for ((was, id) in listOf(source.id to DrawableId("Back"), middle.id to middle.id)) {
                val xy = old.worldPositions.getValue(was)
                xy.indices.forEach { assertEquals(xy[it], now.worldPositions.getValue(id)[it], 0.0001f) }
                assertEquals(old.opacity.getValue(was), now.opacity.getValue(id), 0.00001f)
            }
        }
        val evaluated = CpuDeformationEvaluator().evaluate(puppet, mapOf(ParameterId("Shape") to 1f))
        assertContentEquals(evaluated.worldPositions.getValue(DrawableId("Back")), evaluated.worldPositions.getValue(DrawableId("Front")))
        assertEquals(hash(result.model), hash(builder.build(result.document)))
    }
}
