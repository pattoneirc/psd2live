package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DrawableId
import java.util.UUID

/**
 * The base rig a candidate document generates, with the parts of its version 2 `art_primitive` records held back in
 * [BuiltRig.primitiveSkins]: what the second pass of a split's capture computes residuals against. Called off the
 * GUI thread inside the split's candidate; [progress] reports the build and throws when the originating job is
 * cancelled.
 */
fun interface PrimitiveBaseProvider {
    /** The base rig of [document], or null when no resolved base is available (the split then writes version 1). */
    fun base(document: WorkspaceDocument, progress: ProgressListener): BuiltRig?

    companion object {
        /** No resolved base: every split falls back to version 1 with reason [WorkspaceArtPrimitives.REASON_NO_BASE]. */
        val Unavailable = PrimitiveBaseProvider { _, _ -> null }
    }
}

/**
 * The resolved base through the pipeline: the candidate document built with only its version 2 records in the
 * journal (the base reads nothing else of it; the records replay on their own), and that build's base rig. Each
 * call builds on its own pipeline unless one is given, so no cache outlives the split.
 */
class PipelineBaseProvider(private val pipeline: PSD2LivePipeline? = null) : PrimitiveBaseProvider {
    override fun base(document: WorkspaceDocument, progress: ProgressListener): BuiltRig {
        val config = document.config()
        val records = config.rigEdits.authoringJournal.filter(ArtPrimitiveV2::isV2)
        return (pipeline ?: PSD2LivePipeline()).buildPreview(document.source,
            config.copy(rigEdits = config.rigEdits.copy(authoringJournal = records)), progress).baseRig
    }
}

/** Document side of materialized splits: the parts replace the original layer, and old references name the parts. */
internal object WorkspaceArtPrimitives {
    const val REASON_DISABLED = "disabled"
    const val REASON_NO_BASE = "base_unavailable"
    const val REASON_ORIGINAL = "original_not_generated"
    const val REASON_PARTS = "parts_not_generated"
    const val REASON_REFERENCE = "reference_missing"
    const val REASON_REPLAY = "replay_failed"
    const val REASON_CAPTURE = "capture_failed"

    /**
     * Where the second capture pass gets its base: the resolved pipeline for every split path - the GUI's and MCP's
     * single splits and document batches all reach [decide] through the split edits. Tests set fixtures here.
     */
    @Volatile var baseProvider: PrimitiveBaseProvider = PipelineBaseProvider()

    /** What a split captured, for its version 2 record: the original and its parts in the authored and base rigs. */
    class SplitCapture(
        val origin: String,
        val textureSource: String,
        /** The superseded drawable and its source layer. */
        val ghost: DrawableId,
        val ghostLayer: String,
        /** The authored rig (generated overrides applied) the partition ran on, and the base rig. */
        val ghostAuthored: org.umamo.runtime.model.PuppetModel,
        val ghostGenerated: org.umamo.runtime.model.PuppetModel,
        /** [ghostAuthored] with the parts in place of nothing (the partition's output), and the parts in record order. */
        val partitioned: org.umamo.runtime.model.PuppetModel,
        val partIds: List<DrawableId>,
        val partLayers: List<String>,
        val sources: List<List<org.umamo.edit.VertexSource>>,
        val sourceBounds: List<org.umamo.format.art.LayerBounds>,
        val neutralBounds: List<Bounds>,
        val classifications: List<LayerClassificationOverride>,
        val replace: Map<DrawableId, List<DrawableId>>,
        val masks: Map<DrawableId, List<DrawableId>>,
        val glueGroups: List<List<org.umamo.runtime.model.Glue>>,
        val appended: List<org.umamo.runtime.model.Glue>,
        /** Record fields beyond the common ones (a depth split's `depth`). */
        val extra: JsonObject = JsonObject(emptyMap()),
    )

    /** The authored rig a split captures: the journal replayed, with the overrides of version 2 parts' generated cells. */
    fun capturedAuthored(model: RigPreviewModel): org.umamo.runtime.model.PuppetModel {
        val overlay = model.config.rigEdits
        val authored = overlay.authored(model.baseRig)
        if (overlay.authoringJournal.none(ArtPrimitiveV2::isV2) || overlay.authoringJournal.none(GeneratedOverrides::isOverride)) return authored
        // Overrides of swings and simulations find no cell before those generators run and change nothing here.
        return GeneratedOverrides.applyAll(authored, overlay.authoringJournal).model
    }

    /**
     * The base puppet with [original]'s generated form: a version 2 part being split again is parked in the side
     * channel ([BuiltRig.primitiveSkins]), not in the base puppet, and is generated there like any layer.
     */
    fun generated(base: BuiltRig, original: DrawableId): org.umamo.runtime.model.PuppetModel {
        val puppet = base.puppet
        if (puppet.drawables.any { it.id == original }) return puppet
        val parked = base.primitiveSkins.drawables[original] ?: return puppet
        return puppet.copy(drawables = puppet.drawables + parked)
    }

    private class Fallback(val reason: String, val detail: String) : Exception(detail)

    /**
     * The split's document: [v1] (holding [v1Record] as its last `art_primitive` record) unless version 2 is enabled
     * and safe. With the flag on, the record is built in two passes - base fields first, a candidate base from
     * [baseProvider], then residuals and overrides against the parts that base generated - and kept only when the
     * guard passes: the candidate base still has every deformer, parameter and drawable the existing journal names
     * (the original excepted) and the whole journal replays on it. Otherwise [v1] with [ArtPrimitiveV2.FALLBACK] on
     * its record. [v2Document] places a record and its overrides into the version 2 document.
     */
    fun decide(v1: WorkspaceDocument, v1Record: JsonObject, model: RigPreviewModel, capture: () -> SplitCapture,
               v2Document: (JsonObject, List<JsonObject>) -> WorkspaceDocument,
               work: WorkspaceRasterWork = WorkspaceRasterWork.Direct, provider: PrimitiveBaseProvider = baseProvider): WorkspaceDocument {
        if (!ArtPrimitiveV2.enabled) return v1
        fun fallback(reason: String, detail: String): WorkspaceDocument {
            val journal = v1.rigEdits.authoringJournal
            val index = journal.indexOfLast { it === v1Record }
            require(index >= 0) { "The split record is not in its document" }
            val marked = JsonObject(v1Record + (ArtPrimitiveV2.FALLBACK to buildJsonObject {
                put(ArtPrimitiveV2.FALLBACK_REASON, reason); put(ArtPrimitiveV2.FALLBACK_DETAIL, detail)
            }))
            return v1.copy(rigEdits = v1.rigEdits.copy(authoringJournal = journal.toMutableList().also { it[index] = marked }))
        }
        return try {
            work.progress(0.78f, "Capturing split parts")
            v2(model, capture(), v2Document, work, provider)
        } catch (failure: java.util.concurrent.CancellationException) {
            // A cancelled job is an IllegalStateException too, never a reason to write version 1.
            throw failure
        } catch (failure: Fallback) {
            fallback(failure.reason, failure.detail)
        } catch (failure: IllegalArgumentException) {
            fallback(REASON_CAPTURE, failure.message ?: failure.javaClass.simpleName)
        } catch (failure: IllegalStateException) {
            fallback(REASON_CAPTURE, failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun v2(model: RigPreviewModel, capture: SplitCapture, v2Document: (JsonObject, List<JsonObject>) -> WorkspaceDocument,
                   work: WorkspaceRasterWork, provider: PrimitiveBaseProvider): WorkspaceDocument {
        val checkpoint: () -> Unit = work::checkpoint
        if (capture.ghostGenerated.drawables.none { it.id == capture.ghost })
            throw Fallback(REASON_ORIGINAL, "The split original is not a generated mesh: ${capture.ghost.raw}")
        fun record(parked: BuiltRig?): Pair<JsonObject, List<JsonObject>> {
            val parts = capture.partIds.mapIndexed { index, id ->
                checkpoint()
                val part = capture.partitioned.drawables.single { it.id == id }
                val held = parked?.primitiveSkins?.drawables?.get(id)
                val parkedModel = held?.let { parked.puppet.copy(drawables = parked.puppet.drawables + it) }
                // A part a user Glue holds keeps its vertices.
                val glued = (capture.glueGroups.flatten() + capture.appended).any { it.meshA == id || it.meshB == id }
                ArtPrimitiveJournal.encodePrimitiveV2(capture.partitioned, part, capture.ghostAuthored, capture.ghostGenerated, capture.ghost,
                    capture.sources[index], capture.partLayers[index], capture.textureSource, capture.sourceBounds[index],
                    capture.neutralBounds[index], capture.classifications[index], glued, parkedModel, held,
                    parked?.primitiveSkins ?: PrimitiveSkins.None, checkpoint)
            }
            // User Glues only: the skeleton's welds come from the base.
            val groups = capture.glueGroups.map { group -> group.filterNot(ArtPrimitiveJournal::isSkeletonWeld) }.filter { it.isNotEmpty() }
            val encoded = ArtPrimitiveJournal.encode(capture.origin, capture.textureSource, listOf(capture.ghost), listOf(capture.ghostLayer),
                capture.replace, parts.map { it.primitive }, groups, capture.appended.filterNot(ArtPrimitiveJournal::isSkeletonWeld),
                capture.masks, version = ArtPrimitiveV2.VERSION_V2)
            return JsonObject(encoded + capture.extra) to parts.flatMap { it.overrides }
        }
        val (first, _) = record(null)
        // The second pass: the candidate's resolved base, built on this (background) thread under the split's job.
        val base = provider.base(v2Document(first, emptyList()), ProgressListener { message, fraction ->
            work.progress(0.8f + 0.12f * fraction.toFloat().coerceIn(0f, 1f), message)
        }) ?: throw Fallback(REASON_NO_BASE, "No resolved base is available to generate the parts")
        work.progress(0.92f, "Recording split parts")
        val missing = capture.partIds.filterNot { base.primitiveSkins.holds(it) }
        if (missing.isNotEmpty()) throw Fallback(REASON_PARTS, "The base did not generate ${missing.joinToString { it.raw }}")
        checkpoint()
        val (final, overrides) = record(base)
        val document = v2Document(final, overrides)
        // Guard: whatever the journal before the split names must still be generated (the original may be a stub).
        val before = model.config.rigEdits.authoringJournal
        val old = model.baseRig.puppet
        val generated = HashSet<String>().apply {
            old.drawables.forEach { add(it.id.raw) }; old.deformers.forEach { add(it.id.raw) }; old.parameters.forEach { add(it.id.raw) }
        }
        val now = HashSet<String>().apply {
            base.puppet.drawables.forEach { add(it.id.raw) }; base.puppet.deformers.forEach { add(it.id.raw) }; base.puppet.parameters.forEach { add(it.id.raw) }
        }
        val named = LinkedHashSet<String>()
        fun visit(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) -> if (key in generated) named += key; visit(child) }
                is JsonArray -> value.forEach(::visit)
                is JsonPrimitive -> if (value.isString) (listOf(value.content) + value.content.split(':').drop(1)).forEach { if (it in generated) named += it }
                else -> Unit
            }
        }
        before.forEach(::visit)
        val lost = named.filter { it !in now && it != capture.ghost.raw }
        if (lost.isNotEmpty()) throw Fallback(REASON_REFERENCE, "The resolved base no longer generates ${lost.sorted().joinToString()}")
        checkpoint()
        try {
            document.rigEdits.applyTo(base.puppet, base.primitiveSkins)
        } catch (failure: java.util.concurrent.CancellationException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw Fallback(REASON_REPLAY, failure.message ?: failure.javaClass.simpleName)
        }
        return document
    }

    /**
     * The record version a split wrote, for its result: `{record_version, record_version_reason?}` from the
     * `art_primitive` records [after] has beyond [before]; empty when none was written or version 2 is disabled.
     */
    fun recordVersion(before: RigEditOverlay, after: RigEditOverlay): Map<String, JsonElement> {
        if (!ArtPrimitiveV2.enabled) return emptyMap()
        val written = after.authoringJournal.drop(before.authoringJournal.size).filter(ArtPrimitiveJournal::isRecord)
        if (written.isEmpty()) return emptyMap()
        val version = written.minOf { it["v"]?.jsonPrimitive?.intOrNull ?: ArtPrimitiveJournal.VERSION }
        val reason = written.firstNotNullOfOrNull { (it[ArtPrimitiveV2.FALLBACK] as? JsonObject)?.get(ArtPrimitiveV2.FALLBACK_REASON)?.jsonPrimitive?.contentOrNull }
        return buildMap {
            put(ArtPrimitiveV2.RECORD_VERSION, JsonPrimitive(version))
            if (version == ArtPrimitiveJournal.VERSION) put(ArtPrimitiveV2.RECORD_VERSION_REASON, JsonPrimitive(reason ?: REASON_CAPTURE))
        }
    }

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
                     sides: List<Side>, explicitParentOnly: Boolean = false): WorkspaceDocument {
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
            // Version 2 parts are generated: pinning them under the original's generated parent would keep them out
            // of pair warps, so only an explicit override of the original carries over.
            parentOverrides = if (!explicitParentOnly) document.parentOverrides + ids.associateWith { parent }
                else if (id in document.parentOverrides) document.parentOverrides + ids.associateWith { document.parentOverrides[id] }
                else document.parentOverrides,
            meshOverrides = document.meshOverrides + ids.mapNotNull { next -> document.meshOverrides[id]?.let { next to it } }.toMap(),
            settings = if (drawOrders.isEmpty()) document.settings else JsonObject(document.settings + ("drawOrderOverrides" to JsonObject(drawOrders))))
    }
}
