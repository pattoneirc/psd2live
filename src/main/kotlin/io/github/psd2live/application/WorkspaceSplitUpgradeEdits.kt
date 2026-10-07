package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The explicit upgrade of version 1 `art_primitive` records to version 2: a pure candidate. Records are numbered by
 * their order among the document's `art_primitive` records (version 1 and 2 alike; the overrides an upgrade adds are
 * not records, so the numbers stay). Each selected record is rewritten in journal order by
 * [WorkspaceArtPrimitives.upgrade] on the document its predecessors produced, rebuilt in between, so a record of a
 * part an earlier record created sees that part generated. Nothing is rewritten on read: only this command does it.
 */
internal object WorkspaceSplitUpgradeEdits {
    const val OP = "source_upgrade_split_records"
    val supported = setOf(OP)

    /** One record's outcome: [index] among the records, the layers it superseded, and why it stayed version 1. */
    data class RecordResult(val index: Int, val layers: List<String>, val upgraded: Boolean, val reason: String? = null, val detail: String? = null) {
        fun toJson() = buildJsonObject {
            put("index", index); put("layers", JsonArray(layers.map(::JsonPrimitive))); put("upgraded", upgraded)
            reason?.let { put("reason", it) }; detail?.let { put("detail", it) }
        }
    }

    class Result(val document: WorkspaceDocument, val records: List<RecordResult>)

    /** Whether [overlay] holds a record the command would upgrade. */
    fun upgradable(overlay: RigEditOverlay): Boolean = ArtPrimitiveJournal.commands(overlay).any { !ArtPrimitiveV2.isV2(it) }

    fun indexes(request: JsonObject): List<Int>? = request["record_indexes"]?.jsonArray?.map { it.jsonPrimitive.int }

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel,
              work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        require(operation.operation in supported)
        return upgrade(document, model, indexes(operation.request), work).document
    }

    /**
     * [document] with the records at [indexes] (all version 1 records when null) upgraded where the guard allows.
     * A record already version 2 is reported, not an error; an index past the records is.
     */
    fun upgrade(document: WorkspaceDocument, model: RigPreviewModel, indexes: List<Int>?,
                work: WorkspaceRasterWork = WorkspaceRasterWork.Direct,
                provider: PrimitiveBaseProvider = WorkspaceArtPrimitives.baseProvider,
                rebuild: (WorkspaceDocument, ProgressListener) -> RigPreviewModel = { next, progress ->
                    PSD2LivePipeline().buildPreview(next.source, next.config(), progress)
                }): Result {
        val records = ArtPrimitiveJournal.commands(document.rigEdits)
        if (indexes != null) require(indexes.isNotEmpty() && indexes.distinct().size == indexes.size && indexes.all { it in records.indices }) {
            "record_indexes must name distinct records 0..${records.size - 1}"
        }
        val selected = (indexes ?: records.indices.filterNot { ArtPrimitiveV2.isV2(records[it]) }).sorted()
        fun layers(record: JsonObject) = record.getValue("supersedes_layers").jsonArray.map { it.jsonPrimitive.content }
        var current = document; var currentModel = model; var stale = false
        // Job progress never goes back, whatever a nested build reports.
        var reached = 0f
        fun report(fraction: Float, message: String) {
            reached = maxOf(reached, fraction.coerceIn(0f, 1f)); work.progress(reached, message)
        }
        val results = ArrayList<RecordResult>()
        for ((step, index) in selected.withIndex()) {
            work.checkpoint()
            val journal = current.rigEdits.authoringJournal
            val position = journal.indices.filter { ArtPrimitiveJournal.isRecord(journal[it]) }[index]
            val record = journal[position]
            if (ArtPrimitiveV2.isV2(record)) {
                results += RecordResult(index, layers(record), false, WorkspaceArtPrimitives.REASON_ALREADY); continue
            }
            val start = step.toFloat() / selected.size; val span = 1f / selected.size
            fun progress(fraction: Float, message: String) = report(start + span * fraction, message)
            if (stale) {
                progress(0f, "Rebuilding the upgraded document")
                currentModel = rebuild(current, ProgressListener { message, fraction ->
                    work.checkpoint(); progress(0.3f * fraction.toFloat().coerceIn(0f, 1f), message)
                })
                stale = false
            }
            // The split capture reports 0.78..0.92 of its own range; map it onto this record's share.
            val inner = object : WorkspaceRasterWork {
                override fun checkpoint() = work.checkpoint()
                override fun progress(fraction: Float, message: String) = progress(0.3f + 0.7f * fraction.coerceIn(0f, 1f), message)
            }
            val outcome = WorkspaceArtPrimitives.upgrade(current, currentModel, position, inner, provider)
            val upgraded = outcome.document
            if (upgraded == null) results += RecordResult(index, layers(record), false, outcome.reason, outcome.detail)
            else {
                results += RecordResult(index, layers(record), true)
                current = upgraded; stale = true
            }
        }
        report(1f, "Upgraded split records")
        return Result(current, results)
    }
}

internal data class WorkspaceSplitUpgradeCommit(val commit: WorkspaceCommit<RigPreviewModel>, val result: JsonObject)

/** The single upgrade command: one candidate, one history node when anything was upgraded, none otherwise. */
internal class WorkspaceSplitUpgradeCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>,
                                             private val observer: WorkspaceRasterWork = WorkspaceRasterWork.Direct) {
    private val commands = WorkspaceDocumentCommands(runtime)

    suspend fun execute(projectId: String, state: String, indexes: List<Int>?, summary: String, author: MutationAuthor,
                        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceSplitUpgradeCommit {
        val context = currentCoroutineContext()
        val job = context[WorkspaceSplitUpgradeJobExecution]
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        context.ensureActive()
        var records: List<WorkspaceSplitUpgradeEdits.RecordResult> = emptyList()
        val result = commands.executeCandidate(projectId, state, summary, author, mutation = { document, model ->
            WorkspaceSplitUpgradeEdits.upgrade(document, model, indexes, observer.cancellable(context) { fraction, message ->
                // The candidate's rebuild reports 0.3..0.85 after it.
                context[WorkspaceJobContext]?.progress(0.05f + 0.25f * fraction, message)
            }).also { records = it.records }.document
        }, beforeCommit = { capture, document, model ->
            context.ensureActive(); context[WorkspaceJobContext]?.progress(0.95f, "Committing upgraded split records")
            beforeCommit(capture, document, model)
        })
        val output = buildJsonObject {
            put("state", result.capture.state); put("project_id", result.capture.projectId)
            put("history_node_id", result.capture.historyHead); put("revision", result.capture.revision)
            put("applied", result.applied)
            put("records", JsonArray(records.map { it.toJson() }))
        }
        // Retain the CAS result before host projection refresh or late cancellation can intervene.
        job?.committed(output)
        return WorkspaceSplitUpgradeCommit(result, output)
    }
}

internal class WorkspaceSplitUpgradeJobExecution(private val completion: WorkspaceJobCompletion) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceSplitUpgradeJobExecution>
    fun committed(result: JsonObject) { completion.committed(WorkspaceOperationOutput(result)) }
}
