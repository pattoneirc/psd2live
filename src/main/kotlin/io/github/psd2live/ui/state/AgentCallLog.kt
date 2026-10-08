package io.github.psd2live.ui.state

import io.github.psd2live.agent.AgentCallRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Request fields every write repeats; the log leaves them out of a call's detail. */
private val ENVELOPE_FIELDS = setOf("state", "request_id", "project_id")
private const val DETAIL_LIMIT = 600

/** Failures an Agent recovers from by reading the workspace again or waiting. */
private val RETRYABLE_FAILURES = setOf("state_conflict", "workspace_busy", "project_conflict")

/**
 * The log lines one MCP tool call leaves: one line for the call, with the first returned render on it and a line of its
 * own for each further render. Writes are the Agent's, reads and renders the MCP server's; a plain read sits at DEBUG.
 */
internal fun agentCallLogEntries(record: AgentCallRecord): List<AppLogEntry> {
	val source = if (record.readOnly) LogSource.MCP_SERVER else LogSource.AGENT
	val tag = record.operation.substringBefore('_')
	val status = record.data?.text("status")
	val failureCode = record.error?.text("code")
	val level = when {
		!record.ok -> if (failureCode in RETRYABLE_FAILURES) LogLevel.WARNING else LogLevel.ERROR
		status == "failed" -> LogLevel.ERROR
		status == "cancelled" -> LogLevel.WARNING
		!record.readOnly -> LogLevel.SUCCESS
		record.images.isNotEmpty() -> LogLevel.INFO
		else -> LogLevel.DEBUG
	}
	val outcome = when {
		!record.ok -> "${failureCode ?: "failed"}: ${record.error?.text("message").orEmpty()}"
		else -> record.data?.text("summary")
			?: record.data?.text("history_node_id")?.let { "history $it" }
			?: status?.let { "job $it" }
	}
	val message = buildString {
		append(record.operation)
		if (record.tool != record.operation) append(" (").append(record.tool).append(')')
		if (!outcome.isNullOrBlank()) append(" · ").append(outcome)
		append(" · ").append(record.durationMs).append(" ms")
	}
	val detail = record.request
		?.let { JsonObject(it - ENVELOPE_FIELDS) }
		?.takeIf { it.isNotEmpty() }
		?.toString()
		?.let { if (it.length > DETAIL_LIMIT) it.take(DETAIL_LIMIT) + "…" else it }
	val label = record.data?.text("view_id") ?: record.data?.text("id")
	fun imageLabel(index: Int) = buildString {
		append(record.operation)
		if (label != null) append(": ").append(label)
		if (record.images.size > 1) append(" (").append(index + 1).append('/').append(record.images.size).append(')')
	}
	val first = AppLogEntry(
		source = source, level = level, tag = tag, message = message, detail = detail,
		imageBytes = record.images.firstOrNull(), imageLabel = record.images.firstOrNull()?.let { imageLabel(0) },
	)
	return listOf(first) + record.images.drop(1).mapIndexed { index, image ->
		AppLogEntry(source = source, level = level, tag = tag, message = imageLabel(index + 1),
			imageBytes = image, imageLabel = imageLabel(index + 1))
	}
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
