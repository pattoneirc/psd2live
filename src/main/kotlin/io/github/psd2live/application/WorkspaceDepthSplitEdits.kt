package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Depth slices. A generated model gets two new primitives, back and front, that replace the source mesh and its
 * layer; an imported CMO3 model keeps the legacy copy, which clones the source in journal order.
 */
internal object WorkspaceDepthSplitEdits {
    val supported = setOf("source_split_depth")

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: io.github.psd2live.core.RigPreviewModel,
              work: WorkspaceRasterWork): WorkspaceDocument {
        val request = operation.request
        work.progress(0.05f, "Preparing depth slices")
        val frontId = request["front_layer_id"]?.jsonPrimitive?.content ?: "depth:${UUID.randomUUID()}"
        require(document.source.layers.none { it.id.raw == frontId }) { "Front layer ID already exists: $frontId" }
        val decoded = document.config()
        val config = if ("drawOrderOverrides" in document.settings) decoded else decoded.copy(drawOrderOverrides = model.config.drawOrderOverrides)
        val sourceId = request.getValue("source_id").jsonPrimitive.content
        val middleIds = request.getValue("middle_ids").jsonArray.map { it.jsonPrimitive.content }
        val frontMeshId = request["front_mesh_id"]?.jsonPrimitive?.content ?: "ArtMeshDepth_${UUID.randomUUID()}"
        val glueId = request["glue_id"]?.jsonPrimitive?.content ?: "GlueDepth_${UUID.randomUUID()}"
        val names = request["names"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (config.rigEdits.importedCmo3 == null) {
            val backId = request["back_layer_id"]?.jsonPrimitive?.content ?: "depth-back:${UUID.randomUUID()}"
            require(document.source.layers.none { it.id.raw == backId }) { "Back layer ID already exists: $backId" }
            val backMeshId = request["back_mesh_id"]?.jsonPrimitive?.content ?: "ArtMeshDepthBack_${UUID.randomUUID()}"
            val slices = DepthSplit.materialize(model, config, sourceId, middleIds, backId, backMeshId, frontId, frontMeshId, glueId,
                names, work::checkpoint)
            work.progress(0.75f, "Preserving depth slice bindings")
            val frozen = WorkspaceLayerInsertionEdits.freeze(document, model)
            val placed = WorkspaceArtPrimitives.replaceLayer(frozen, model, slices.sourceLayerId, listOf(slices.front, slices.back),
                listOf(Side.NONE, Side.NONE))
            val overlay = MeshGenerationBaseline.preserve(frozen.rigEdits.copy(simEdits = slices.simulations.simEdits), model.config)
            val layers = listOf(slices.back.id.raw, slices.front.id.raw)
            fun documentWith(placed: WorkspaceDocument, rigEdits: RigEditOverlay, parents: Map<String, String?>) = placed.copy(
                layerOverrides = placed.layerOverrides + layers.associateWith { slices.classification },
                layerVisibility = placed.layerVisibility + layers.associateWith {
                    document.layerVisibility[slices.sourceLayerId] ?: slices.visible
                },
                parentOverrides = placed.parentOverrides + parents,
                settings = JsonObject(placed.settings + ("drawOrderOverrides" to JsonObject(slices.drawOrderOverrides.mapValues { JsonPrimitive(it.value) }))),
                rigEdits = rigEdits.copy(splitDrawableIds = rigEdits.splitDrawableIds + mapOf(slices.back.id.raw to backMeshId, slices.front.id.raw to frontMeshId)))
            val v1 = documentWith(placed, overlay.copy(authoringJournal = overlay.authoringJournal + slices.record), layers.associateWith { slices.parent })
            return WorkspaceArtPrimitives.decide(v1, slices.record, model, capture = {
                val staged = requireNotNull(slices.staged); val authored = requireNotNull(slices.authored); val source = requireNotNull(slices.source)
                val count = requireNotNull(authored.drawables.single { it.id == source }.mesh).vertexCount
                val identity = List(count) { org.umamo.edit.VertexSource.FromOld(it) }
                WorkspaceArtPrimitives.SplitCapture("depth", PuppetSourceAtlas.SOURCE_ID_RAW, source, slices.sourceLayerId, authored,
                    model.baseRig.puppet, staged, slices.sliceIds, layers, listOf(identity, identity),
                    listOf(requireNotNull(slices.coverage), slices.coverage), listOf(requireNotNull(slices.neutral), slices.neutral),
                    listOf(slices.classification, slices.classification), mapOf(source to slices.sliceIds), mapOf(source to listOf(slices.sliceIds.first())),
                    slices.replacedGlues, listOfNotNull(slices.weld), JsonObject(mapOf("depth" to slices.record.getValue("depth"))))
            }, v2Document = { record, overrides ->
                val parent = model.config.parentOverrides.takeIf { slices.sourceLayerId in it }?.get(slices.sourceLayerId)
                val explicit = if (slices.sourceLayerId in model.config.parentOverrides) layers.associateWith { parent } else emptyMap()
                val v2Placed = WorkspaceArtPrimitives.replaceLayer(frozen, model, slices.sourceLayerId, listOf(slices.front, slices.back),
                    listOf(Side.NONE, Side.NONE), explicitParentOnly = true)
                val moved = SourcePartitionJournal.migrateBones(overlay, requireNotNull(slices.source).raw, slices.sliceIds.take(1).map { it.raw })
                documentWith(v2Placed, moved.copy(authoringJournal = moved.authoringJournal + record + overrides), explicit)
            }, checkpoint = work::checkpoint)
        }
        val prepared = DepthSplit.prepare(model, config, sourceId, middleIds, frontId, frontMeshId, glueId, names, work::checkpoint)
        work.progress(0.75f, "Preserving depth slice bindings")
        val front = prepared.source.layers.single { it.id.raw == frontId }
        return document.copy(source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
            document.source.layers + front, document.source.groups), layerVisibility = prepared.config.layerVisibility,
            layerOverrides = prepared.config.layerOverrides, parentOverrides = prepared.config.parentOverrides,
            meshOverrides = prepared.config.meshOverrides, rigEdits = RigLayerDeletion.preserve(model, prepared.config),
            generationSource = if (prepared.config.rigEdits.importedCmo3 == null) document.generationSource else document.generationSource ?: document.source,
            settings = JsonObject(document.settings + ("drawOrderOverrides" to JsonObject(prepared.config.drawOrderOverrides.mapValues { JsonPrimitive(it.value) }))))
    }
}
