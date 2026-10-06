package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DrawableId
import java.util.UUID

/** Document side of materialized splits: the parts replace the original layer, and old references name the parts. */
internal object WorkspaceArtPrimitives {
    /** Rejects a reference to a layer or mesh a split removed, naming what replaced it. */
    fun requireCurrent(overlay: RigEditOverlay, id: String) {
        val layers = ArtPrimitiveJournal.replacementLayers(overlay)[id]
        if (layers != null) throw IllegalArgumentException("Layer $id was split and no longer exists; use its parts: ${layers.joinToString()}")
        val meshes = ArtPrimitiveJournal.replacementDrawables(overlay)[id]
        if (meshes != null) throw IllegalArgumentException("Mesh $id was split and no longer exists; use its parts: ${meshes.joinToString()}")
    }

    /** Superseded layer and mesh ids, each with the current ids that replaced it. */
    fun supersededBy(overlay: RigEditOverlay): Map<String, List<String>> =
        ArtPrimitiveJournal.replacementDrawables(overlay) + ArtPrimitiveJournal.replacementLayers(overlay)

    /** Request fields that name an existing layer or object; new ids and display names are not checked. */
    private val referenceFields = setOf("layer_id", "layer_ids", "target", "targets", "destination", "source_id", "middle_ids",
        "mesh_a", "mesh_b", "meshes", "mesh_id", "mesh_ids", "drawable_id", "drawable_ids", "object_id", "object_ids")

    /** Every reference field in [request] naming a superseded layer or mesh, alone or as `kind:id`. */
    fun requireCurrentReferences(overlay: RigEditOverlay, request: JsonObject) {
        val superseded = supersededBy(overlay)
        if (superseded.isEmpty()) return
        fun visit(value: JsonElement, reference: Boolean) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) -> visit(child, key in referenceFields) }
                is JsonArray -> value.forEach { visit(it, reference) }
                is JsonPrimitive -> if (reference && value.isString) {
                    val text = value.content
                    val id = if (text in superseded) text else text.substringAfter(':', "").takeIf { it in superseded }
                    if (id != null) requireCurrent(overlay, id)
                }
                else -> Unit
            }
        }
        visit(request, false)
    }

    /** Mesh ids for new parts, named as the generator names layers and distinct from every id in use. */
    fun allocate(document: WorkspaceDocument, model: RigPreviewModel, layers: List<WorkspaceSourceLayer>): List<String> {
        val reserved = LinkedHashSet<String>()
        for (puppet in listOf(model.rig.puppet, model.baseRig.puppet)) {
            puppet.drawables.forEach { reserved += it.id.raw }; puppet.deformers.forEach { reserved += it.id.raw }
        }
        reserved += document.rigEdits.splitDrawableIds.values
        ArtPrimitiveJournal.commands(document.rigEdits).forEach { command ->
            ArtPrimitiveJournal.primitives(command).forEach { reserved += it.getValue("id").jsonPrimitive.content }
            command.getValue("supersedes").jsonArray.forEach { reserved += it.jsonPrimitive.content }
        }
        val art = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx, layers, document.source.groups)
        val analysis = CharacterAnalyzer.analyze(art, document.config())
        val existing = reserved.withIndex().associate { (index, id) -> "\u0000reserved:$index" to DrawableId(id) }
        val assigned = RigBuilder.assignSplitDrawableIds(analysis, existing)
        return layers.map { layer ->
            assigned[layer.id.raw]?.raw ?: run {
                val base = "ArtMeshPart" + UUID.nameUUIDFromBytes(layer.id.raw.toByteArray(Charsets.UTF_8)).toString().replace("-", "")
                var candidate = base; var suffix = 2
                while (candidate in reserved) candidate = "$base${suffix++}"
                candidate
            }.also { reserved += it }
        }
    }

    /**
     * [document] with the source layer [id] replaced by [parts] at its place in the stack. The parts inherit the
     * original's classification, visibility, parent, mesh settings and draw order override; the original's own
     * settings stay, because the frozen generation input still builds it for the journal before the split.
     */
    fun replaceLayer(document: WorkspaceDocument, model: RigPreviewModel, id: String, parts: List<WorkspaceSourceLayer>,
                     sides: List<Side>): WorkspaceDocument {
        val original = document.source.layers.firstOrNull { it.id.raw == id }
            ?: model.analysis.layers.first { it.source.id.raw == id }.source
        val classification = document.layerOverrides[id] ?: model.analysis.layers.firstOrNull { it.source.id.raw == id }?.semantic?.let {
            LayerClassificationOverride(it.type, it.tag, it.side, it.parameter, it.switchId)
        } ?: LayerClassificationOverride()
        val ids = parts.map { it.id.raw }
        val parent = if (id in document.parentOverrides) document.parentOverrides[id] else model.rig.puppet.drawables.firstOrNull {
            it.id.raw == id || model.rig.layerIdByDrawableId[it.id.raw] == id
        }?.parentDeformerId?.raw
        val owner = if (document.source.layers.any { it.id.raw == id }) id else document.source.layers
            .map { it.id.raw }.filter { id.startsWith("$it:") }.maxByOrNull(String::length)
        val stacked = document.source.layers.flatMap { if (it.id.raw == owner) listOf(it) + parts else listOf(it) }
            .let { if (owner == null) it + parts else it }
        // Number the stack with the original in it, so the parts take its place and every other layer keeps its rank.
        val layers = stacked.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, stacked.size - index) }
            .filterNot { it.id.raw == id }
        val drawOrders = document.settings["drawOrderOverrides"]?.jsonObject.orEmpty() + ids.mapNotNull { next ->
            document.settings["drawOrderOverrides"]?.jsonObject?.get(id)?.let { next to it }
        }.toMap()
        return document.copy(source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx, layers, document.source.groups),
            layerOverrides = document.layerOverrides + ids.mapIndexed { index, next ->
                next to classification.copy(side = sides[index].takeUnless { it == Side.NONE } ?: classification.side)
            },
            layerVisibility = document.layerVisibility + ids.associateWith { document.layerVisibility[id] ?: original.visible },
            parentOverrides = document.parentOverrides + ids.associateWith { parent },
            meshOverrides = document.meshOverrides + ids.mapNotNull { next -> document.meshOverrides[id]?.let { next to it } }.toMap(),
            settings = if (drawOrders.isEmpty()) document.settings else JsonObject(document.settings + ("drawOrderOverrides" to JsonObject(drawOrders))))
    }
}
