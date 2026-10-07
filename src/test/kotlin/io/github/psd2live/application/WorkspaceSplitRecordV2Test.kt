package io.github.psd2live.application

import io.github.psd2live.core.*
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

    @AfterEach fun reset() {
        System.clearProperty(ArtPrimitiveV2.FLAG_PROPERTY)
        WorkspaceArtPrimitives.baseProvider = PrimitiveBaseProvider.Unavailable
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
        val document = WorkspacePartitionEdits.apply(split, authored.document, authored.model)
        val record = records(document).single()
        assertEquals(1, record.getValue("v").jsonPrimitive.int)
        assertNull(record[ArtPrimitiveV2.FALLBACK])
        assertTrue(WorkspaceArtPrimitives.recordVersion(authored.document.rigEdits, document.rigEdits).isEmpty())
    }

    @Test fun withoutAResolvedBaseTheSplitFallsBackToVersionOneWithAReason() = runBlocking<Unit> {
        val runtime = fixture(); val authored = author(runtime)
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true")
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

    // Passes once RigBuilder's baseLayers also drops stub layers from analysis.layers (a frozen generation source
    // restores the superseded layer beside its passenger, so the base builds it twice).
    @org.junit.jupiter.api.Disabled("Integration: the resolved base builds a superseded layer twice when the document has a generation source")
    @Test fun versionTwoSplitReplaysToTheSameRigAsVersionOne() = runBlocking<Unit> {
        val runtime = fixture(); val authored = author(runtime)
        val v1 = builder.build(WorkspacePartitionEdits.apply(split, authored.document, authored.model))
        System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true")
        WorkspaceArtPrimitives.baseProvider = PipelineBaseProvider()
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
