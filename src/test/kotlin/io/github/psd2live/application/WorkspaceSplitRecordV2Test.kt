package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.math.abs
import kotlin.test.*

/** The writer's choice between version 2 and version 1 `art_primitive` records, and a version 2 split end to end. */
class WorkspaceSplitRecordV2Test {
    private val builder = WorkspacePreviewBuilder()
    // The suite may run with the flag passed in (-Ppsd2live.artPrimitiveV2); each test sets what it needs and restores it.
    private val flag: String? = System.getProperty(ArtPrimitiveV2.FLAG_PROPERTY)
    private val provider = WorkspaceArtPrimitives.baseProvider

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
        runtime.install(runtime.state.value.state, "v2", document, builder.build(document))
        return runtime
    }

    private fun mesh(model: RigPreviewModel, layer: String) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == layer }

    /** A user shape key, a user path and a Glue on the original. */
    private suspend fun author(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
        val root = runtime.capture(); val source = mesh(root.model, "islands"); val other = mesh(root.model, "other")
        val count = source.mesh!!.vertexCount
        val deltas = JsonArray(List(count * 2) { JsonPrimitive(kotlin.math.sin(it.toDouble()).toFloat() * 0.5f) })
        val positions = source.mesh!!.positions
        return WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Author original", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("canvas_glue", buildJsonObject { put("id", "Weld"); put("mesh_a", source.id.raw); put("mesh_b", other.id.raw); put("distance", 256) }),
            WorkspaceDocumentOperation("path_put", buildJsonObject { put("id", "Spine"); put("target", "mesh:${source.id.raw}"); putJsonArray("points") {
                add(buildJsonArray { add(positions[0]); add(positions[1]) }); add(buildJsonArray { add(positions[2]); add(positions[3]) })
            } }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                add(buildJsonObject { put("op", "set"); put("target", "mesh:${source.id.raw}"); putJsonObject("key") { put("Shape", 1) }
                    putJsonObject("geometry") { put("positionDeltas", deltas) } })
            } })), MutationAuthor.USER).capture
    }

    private val split = WorkspaceDocumentOperation("source_split_components", buildJsonObject {
        put("layer_id", "islands"); put("names", JsonArray(listOf("First", "Second").map(::JsonPrimitive)))
        put("piece_ids", JsonArray(listOf("first", "second").map(::JsonPrimitive)))
    })

    private fun records(document: WorkspaceDocument) = ArtPrimitiveJournal.commands(document.rigEdits)

    @Test fun flagOffWritesVersionOneUnchanged() = runBlocking<Unit> {
        val runtime = fixture(); val authored = author(runtime)
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "false")
        val document = WorkspacePartitionEdits.apply(split, authored.document, authored.model)
        val record = records(document).single()
        assertEquals(1, record.getValue("v").jsonPrimitive.int)
        assertNull(record[ArtPrimitiveV2.FALLBACK])
        assertTrue(WorkspaceArtPrimitives.recordVersion(authored.document.rigEdits, document.rigEdits).isEmpty())
    }

    @Test fun withoutAResolvedBaseTheSplitFallsBackToVersionOneWithAReason() = runBlocking<Unit> {
        val runtime = fixture(); val authored = author(runtime)
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true")
        WorkspaceArtPrimitives.baseProvider = PrimitiveBaseProvider.Unavailable
        val document = WorkspacePartitionEdits.apply(split, authored.document, authored.model)
        val record = records(document).single()
        assertEquals(1, record.getValue("v").jsonPrimitive.int)
        assertEquals(WorkspaceArtPrimitives.REASON_NO_BASE,
            record.getValue(ArtPrimitiveV2.FALLBACK).jsonObject.getValue(ArtPrimitiveV2.FALLBACK_REASON).jsonPrimitive.content)
        val result = WorkspaceArtPrimitives.recordVersion(authored.document.rigEdits, document.rigEdits)
        assertEquals(1, result.getValue(ArtPrimitiveV2.RECORD_VERSION).jsonPrimitive.int)
        assertEquals(WorkspaceArtPrimitives.REASON_NO_BASE, result.getValue(ArtPrimitiveV2.RECORD_VERSION_REASON).jsonPrimitive.content)
        // The version 1 fallback replays as any version 1 record.
        builder.build(document)
    }

    /** A filled rectangle on [layer], from and to in canvas pixels. */
    private fun paint(layer: String, from: Pair<Int, Int>, to: Pair<Int, Int>, rebuild: Boolean) = WorkspaceDocumentOperation("source_paint_shape", buildJsonObject {
        put("layer_id", layer); put("shape", "rectangle"); put("filled", true); put("rebuild_mesh", rebuild)
        put("from", buildJsonArray { add(from.first); add(from.second) }); put("to", buildJsonArray { add(to.first); add(to.second) })
        put("color", buildJsonArray { add(250); add(20); add(20); add(255) })
    })

    @Test fun paintingAVersionTwoPartKeepsItsPinnedMeshUnlessItIsRebuilt() = runBlocking<Unit> {
        val runtime = fixture(); val authored = author(runtime)
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true")
        val commands = WorkspaceDocumentCommands(runtime)
        val split = commands.execute(authored.projectId, authored.state, "Split", listOf(split), MutationAuthor.USER).capture
        val record = records(split.document).single()
        assertTrue(ArtPrimitiveV2.isV2(record), record[ArtPrimitiveV2.FALLBACK].toString())
        val part = mesh(split.model, "first")

        // A repaint inside the part changes its pixels only: the record's pinned mesh stays, the journal is untouched.
        val repainted = commands.execute(split.projectId, split.state, "Paint", listOf(paint("first", 10 to 12, 20 to 20, false)), MutationAuthor.USER).capture
        assertEquals(split.document.rigEdits.authoringJournal, repainted.document.rigEdits.authoringJournal)
        assertFalse(split.document.source.layers.single { it.id.raw == "first" }.raster.rgba
            .contentEquals(repainted.document.source.layers.single { it.id.raw == "first" }.raster.rgba))
        val kept = mesh(repainted.model, "first")
        assertEquals(part.id, kept.id)
        assertContentEquals(part.mesh!!.positions, kept.mesh!!.positions)
        assertContentEquals(part.mesh!!.indices, kept.mesh!!.indices)
        // The part's own (authored) keyforms survive the repaint.
        assertEquals(part.geometryGrid?.axes?.map { it.parameterId }, kept.geometryGrid?.axes?.map { it.parameterId })

        // Painting beyond it with a mesh rebuild re-meshes the part through a mesh record after its split record.
        val rebuilt = commands.execute(repainted.projectId, repainted.state, "Grow", listOf(paint("first", 30 to 20, 50 to 30, true)), MutationAuthor.USER).capture
        val journal = rebuilt.document.rigEdits.authoringJournal
        val after = journal.drop(journal.indexOfFirst { ArtPrimitiveJournal.isRecord(it) } + 1)
        assertTrue(after.any { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshJournal.OP && it["id"]?.jsonPrimitive?.contentOrNull == part.id.raw },
            after.map { it["op"] }.toString())
        val grown = mesh(rebuilt.model, "first")
        assertTrue(grown.mesh!!.positions.indices.step(2).maxOf { grown.mesh!!.positions[it] } >
            part.mesh!!.positions.indices.step(2).maxOf { part.mesh!!.positions[it] }, "the rebuilt mesh covers the new pixels")
        assertTrue(ArtPrimitiveV2.isV2(ArtPrimitiveJournal.commands(rebuilt.document.rigEdits).single()))
        // The user shape key moved onto the rebuilt mesh, and a cold build of the document gives the same rig.
        assertTrue(grown.geometryGrid!!.axes.any { it.parameterId == ParameterId("Shape") })
        val cold = WorkspacePreviewBuilder().build(rebuilt.document)
        assertEquals(ContentHash.of(RigIrCompiler.compile(rebuilt.model)), ContentHash.of(RigIrCompiler.compile(cold)))

        // A mesh settings change on the other part regenerates it through the materialized mesh rebuild, also after the record.
        val second = mesh(rebuilt.model, "second")
        val settings = commands.execute(rebuilt.projectId, rebuilt.state, "Mesh", listOf(WorkspaceDocumentOperation("layer_mesh_update",
            buildJsonObject { put("layer_id", "second"); putJsonObject("changes") { put("outerMargin", 9) } })), MutationAuthor.USER).capture
        val remeshed = settings.document.rigEdits.authoringJournal.drop(journal.size)
        assertTrue(remeshed.any { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshJournal.OP && it["id"]?.jsonPrimitive?.contentOrNull == second.id.raw },
            remeshed.map { it["op"] }.toString())
        assertFalse(second.mesh!!.positions.contentEquals(mesh(settings.model, "second").mesh!!.positions))
        assertEquals(ContentHash.of(RigIrCompiler.compile(settings.model)),
            ContentHash.of(RigIrCompiler.compile(WorkspacePreviewBuilder().build(settings.document))))
    }

    @Test fun aVersionTwoPartSplitsAgain() = runBlocking<Unit> {
        val runtime = fixture(); val authored = author(runtime)
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true")
        val commands = WorkspaceDocumentCommands(runtime)
        // Parts on either side: their classification differs from the original's.
        val sided = WorkspaceDocumentOperation(split.operation, JsonObject(split.request + ("sides" to JsonArray(listOf("left", "right").map(::JsonPrimitive)))))
        val split = commands.execute(authored.projectId, authored.state, "Split", listOf(sided), MutationAuthor.USER).capture
        val polygon = WorkspaceDocumentOperation("source_split_polygon", buildJsonObject {
            put("layer_id", "first"); put("names", JsonArray(listOf("Top", "Bottom").map(::JsonPrimitive)))
            put("piece_ids", JsonArray(listOf("top", "bottom").map(::JsonPrimitive)))
            putJsonArray("polygon") { listOf(0 to 0, 96 to 0, 96 to 24, 0 to 24).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
        })
        val again = commands.execute(split.projectId, split.state, "Split again", listOf(polygon), MutationAuthor.USER).capture
        val records = records(again.document)
        assertEquals(2, records.size)
        assertTrue(records.all(ArtPrimitiveV2::isV2), records.last()[ArtPrimitiveV2.FALLBACK].toString())
        assertEquals(setOf("top", "bottom", "second"), again.model.rig.layerIdByDrawableId.values.toSet() - "other")
        assertEquals(ContentHash.of(RigIrCompiler.compile(again.model)),
            ContentHash.of(RigIrCompiler.compile(WorkspacePreviewBuilder().build(again.document))))

        // A part of a part takes a mesh settings change; the document then replays cold to the same rig.
        val remeshed = commands.execute(again.projectId, again.state, "Mesh", listOf(WorkspaceDocumentOperation("layer_mesh_update",
            buildJsonObject { put("layer_id", "top"); putJsonObject("changes") { put("outerMargin", 5); put("interiorDensity", 6) } })), MutationAuthor.USER).capture
        val rebuilds = remeshed.document.rigEdits.authoringJournal.drop(again.document.rigEdits.authoringJournal.size)
            .map { it["id"]?.jsonPrimitive?.contentOrNull }
        assertEquals(listOf(mesh(again.model, "top").id.raw), rebuilds, "only the changed part is rebuilt")
        assertEquals(ContentHash.of(RigIrCompiler.compile(remeshed.model)),
            ContentHash.of(RigIrCompiler.compile(WorkspacePreviewBuilder().build(remeshed.document))))

        // A version 1 record of a version 2 part (the fallback) replays as well.
        WorkspaceArtPrimitives.baseProvider = PrimitiveBaseProvider.Unavailable
        val legacy = WorkspacePartitionEdits.apply(polygon, split.document, split.model)
        assertEquals(listOf(true, false), records(legacy).map(ArtPrimitiveV2::isV2))
        val built = builder.build(legacy, split.model)
        assertEquals(setOf("top", "bottom", "second"), built.rig.layerIdByDrawableId.values.toSet() - "other")
        // The record supersedes only its part: the base the earlier entries replay on is unchanged.
        for (layer in listOf("other", "second")) assertContentEquals(mesh(split.model, layer).mesh!!.positions, mesh(built, layer).mesh!!.positions, layer)
        for (drawable in split.model.baseRig.puppet.drawables) {
            val now = built.baseRig.puppet.drawables.singleOrNull { it.id == drawable.id } ?: continue
            assertContentEquals(drawable.mesh?.positions, now.mesh?.positions, "base ${drawable.id.raw}")
        }
        assertEquals(ContentHash.of(RigIrCompiler.compile(built)), ContentHash.of(RigIrCompiler.compile(WorkspacePreviewBuilder().build(legacy))))
    }

    @Test fun theSecondPassReportsProgressAndACancelledJobIsNotAFallback() = runBlocking<Unit> {
        val runtime = fixture(); val authored = author(runtime)
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true")
        val messages = mutableListOf<Pair<Float, String>>()
        val work = object : WorkspaceRasterWork {
            override fun checkpoint() {}
            override fun progress(fraction: Float, message: String) { messages += fraction to message }
        }
        WorkspaceArtPrimitives.baseProvider = PrimitiveBaseProvider { document, progress ->
            progress.update("Building the resolved base", 0.5)
            PipelineBaseProvider().base(document, progress)
        }
        val document = WorkspacePartitionEdits.apply(split, authored.document, authored.model, work)
        assertTrue(ArtPrimitiveV2.isV2(records(document).single()))
        val base = messages.single { it.second == "Building the resolved base" }
        assertTrue(base.first in 0.8f..0.92f, "the base build reports inside the capture's range: $base")
        assertTrue(messages.map { it.second }.containsAll(listOf("Capturing split parts", "Recording split parts")))
        // Cancelling the job while the base builds cancels the split: it does not write version 1 instead.
        WorkspaceArtPrimitives.baseProvider = PrimitiveBaseProvider { _, _ -> throw java.util.concurrent.CancellationException("cancelled") }
        assertFailsWith<java.util.concurrent.CancellationException> {
            WorkspacePartitionEdits.apply(split, authored.document, authored.model, work)
        }
    }

    @Test fun versionTwoPartsInheritOnlyAnExplicitParent() = runBlocking<Unit> {
        val runtime = fixture(); val start = runtime.capture()
        val pieces = listOf("p1", "p2").map { id ->
            (start.document.source.layers.single { it.id.raw == "islands" } as WorkspaceSourceLayer).copy(id = LayerId(id), name = id)
        }
        val sides = listOf(Side.LEFT, Side.RIGHT)
        // Version 1 pins the parts under the original's generated parent; version 2 leaves them to generation.
        val v1 = WorkspaceArtPrimitives.replaceLayer(start.document, start.model, "islands", pieces, sides)
        assertEquals(setOf("p1", "p2"), v1.parentOverrides.keys)
        val v2 = WorkspaceArtPrimitives.replaceLayer(start.document, start.model, "islands", pieces, sides, explicitParentOnly = true)
        assertTrue(v2.parentOverrides.isEmpty())
        val explicit = start.document.copy(parentOverrides = mapOf("islands" to "DeformBodyXY"))
        val pinned = WorkspaceArtPrimitives.replaceLayer(explicit, start.model, "islands", pieces, sides, explicitParentOnly = true)
        assertEquals(mapOf("islands" to "DeformBodyXY", "p1" to "DeformBodyXY", "p2" to "DeformBodyXY"), pinned.parentOverrides)
    }

    @Test fun versionTwoSplitReplaysToTheSameRigAsVersionOne() = runBlocking<Unit> {
        val runtime = fixture(); val authored = author(runtime)
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "false")
        val v1 = builder.build(WorkspacePartitionEdits.apply(split, authored.document, authored.model))
        // The application's own provider: the resolved pipeline.
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true")
        val document = WorkspacePartitionEdits.apply(split, authored.document, authored.model)
        val record = records(document).single()
        assertTrue(ArtPrimitiveV2.isV2(record), record[ArtPrimitiveV2.FALLBACK].toString())
        // Generated parents are not pinned: only an explicit override of the original carries over.
        assertTrue(document.parentOverrides.keys.none { it == "first" || it == "second" })
        val glueIds = record.getValue("glues").jsonObject.getValue("replaced").jsonArray.flatMap { g -> g.jsonArray.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull } }
        assertTrue(glueIds.all { it == null || !it.startsWith("GlueSkel__") })
        val v2 = builder.build(document)
        val parts = v1.rig.puppet.drawables.filter { v1.rig.layerIdByDrawableId[it.id.raw] in setOf("first", "second") }.map { it.id }
        assertEquals(2, parts.size)
        assertEquals(parts.toSet(), v2.rig.puppet.drawables.filter { v2.rig.layerIdByDrawableId[it.id.raw] in setOf("first", "second") }.map { it.id }.toSet())
        val evaluator = CpuDeformationEvaluator()
        for (shape in listOf(-1f, 0f, 1f)) {
            val pose = mapOf(ParameterId("Shape") to shape)
            val a = evaluator.evaluate(v1.rig.puppet, pose).worldPositions; val b = evaluator.evaluate(v2.rig.puppet, pose).worldPositions
            for (id in parts) {
                val x = a.getValue(id); val y = b.getValue(id)
                assertEquals(x.size, y.size)
                for (i in x.indices) assertTrue(abs(x[i] - y[i]) < 0.05f, "${id.raw} at Shape=$shape coordinate $i: ${x[i]} vs ${y[i]}")
            }
        }
        assertTrue(v2.rig.puppet.glues.any { it.id == "Weld" })
        assertTrue(v2.rig.puppet.deformPaths.any { it.id.endsWith("/Spine") })
        assertTrue(v2.rig.puppet.drawables.none { it.id == mesh(authored.model, "islands").id })
    }
}
