package io.github.psd2live.ui.state

import io.github.psd2live.i18n.tr
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceHistorySnapshot

/**
 * The log lines a state change implies, so the log follows what the editor shows wherever the change came from:
 * history the user writes or moves through, errors the window reports, the status bar and opened projects.
 * Agent commits are left to the MCP call that made them, which logs them with its request.
 */
internal fun stateChangeLogEntries(before: PSD2LiveState, after: PSD2LiveState): List<AppLogEntry> {
	val entries = mutableListOf<AppLogEntry>()
	// Lines the change already wrote itself; an error or status they already say is not repeated.
	val written = if (after.logEntries === before.logEntries) emptyList()
		else before.logEntries.lastOrNull()?.let { last -> after.logEntries.takeLastWhile { it.id != last.id } } ?: after.logEntries
	val opened = after.projectOpenGeneration != before.projectOpenGeneration
	if (opened && after.projectId != null) {
		val name = after.projectSourceName ?: after.projectFile?.substringAfterLast('\\')?.substringAfterLast('/') ?: after.projectId
		entries += AppLogEntry(source = LogSource.EDITOR, level = LogLevel.INFO, tag = "Project", message = tr("log.project.opened", name),
			detail = after.projectFile)
	}
	if (!opened && after.historySnapshot !== before.historySnapshot) {
		entries += historyLogEntries(before.historySnapshot, after.historySnapshot)
	}
	if (after.errorMessage != before.errorMessage && !after.errorMessage.isNullOrBlank() &&
		written.none { it.level == LogLevel.ERROR && after.errorMessage in it.message }) {
		entries += AppLogEntry(source = LogSource.SYSTEM, level = LogLevel.ERROR, tag = "Error", message = after.errorMessage)
	}
	if (after.statusText != before.statusText && after.statusText.isNotBlank() && written.none { it.message == after.statusText }) {
		entries += AppLogEntry(source = LogSource.SYSTEM, level = LogLevel.DEBUG, tag = "Status", message = after.statusText)
	}
	return entries
}

/** The user's new history nodes, and where HEAD moved when it moved without one: undo, redo or a checkout. */
internal fun historyLogEntries(before: WorkspaceHistorySnapshot?, after: WorkspaceHistorySnapshot?): List<AppLogEntry> {
	if (before == null || after == null || before.headNodeId == after.headNodeId) return emptyList()
	val known = before.nodes.mapTo(HashSet()) { it.id }
	val added = after.nodes.filter { it.id !in known }
	if (added.isNotEmpty()) {
		return added.filter { it.actor == MutationAuthor.USER.historyActor }.map { node ->
			AppLogEntry(source = LogSource.EDITOR, level = LogLevel.SUCCESS, tag = "Edit", message = node.summary, detail = node.id)
		}
	}
	val head = after.nodes.firstOrNull { it.id == after.headNodeId } ?: return emptyList()
	val previous = before.nodes.firstOrNull { it.id == before.headNodeId }
	val message = when {
		previous?.parentId == head.id -> tr("log.history.undo", previous.summary)
		head.parentId == before.headNodeId -> tr("log.history.redo", head.summary)
		else -> tr("log.history.checkout", head.summary)
	}
	return listOf(AppLogEntry(source = LogSource.EDITOR, level = LogLevel.INFO, tag = "History", message = message, detail = head.id))
}
