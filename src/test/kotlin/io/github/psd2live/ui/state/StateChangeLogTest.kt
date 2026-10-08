package io.github.psd2live.ui.state

import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceHistoryNodeSnapshot
import io.github.psd2live.project.WorkspaceHistorySnapshot
import kotlin.test.*

class StateChangeLogTest {
    private fun node(id: String, parent: String?, summary: String, actor: String = "user") =
        WorkspaceHistoryNodeSnapshot(id, parent, "r-$id", summary, actor, null, "2026-10-09T00:00:00Z", false)

    private val root = node("a", null, "Open")
    private val edit = node("b", "a", "Move eye")

    private fun history(head: String, vararg nodes: WorkspaceHistoryNodeSnapshot) = WorkspaceHistorySnapshot(head, nodes.toList())

    @Test fun theUsersCommitsAreLoggedAndTheAgentsLeftToItsCall() {
        val added = historyLogEntries(history("a", root), history("b", root, edit)).single()
        assertEquals(LogSource.EDITOR, added.source)
        assertEquals(LogLevel.SUCCESS, added.level)
        assertEquals("Move eye", added.message)
        assertTrue(historyLogEntries(history("a", root), history("b", root, node("b", "a", "Agent edit", actor = "agent"))).isEmpty())
    }

    @Test fun headMovesReadAsUndoRedoOrCheckout() {
        val both = arrayOf(root, edit, node("c", "a", "Other branch"))
        assertEquals(tr("log.history.undo", "Move eye"), historyLogEntries(history("b", *both), history("a", *both)).single().message)
        assertEquals(tr("log.history.redo", "Move eye"), historyLogEntries(history("a", *both), history("b", *both)).single().message)
        assertEquals(tr("log.history.checkout", "Other branch"), historyLogEntries(history("b", *both), history("c", *both)).single().message)
        assertTrue(historyLogEntries(history("b", *both), history("b", *both)).isEmpty())
    }

    @Test fun errorsAreLoggedOnceAndStatusAtDebug() {
        val before = PSD2LiveState()
        val error = stateChangeLogEntries(before, before.copy(errorMessage = "disk full")).single()
        assertEquals(LogLevel.ERROR, error.level)
        assertEquals("disk full", error.message)
        // A change that already logged the failure is not repeated by the error it shows.
        val logged = before.copy(errorMessage = "disk full",
            logEntries = listOf(AppLogEntry(level = LogLevel.ERROR, message = "Export failed: disk full")))
        assertTrue(stateChangeLogEntries(before, logged).isEmpty())
        val status = stateChangeLogEntries(before, before.copy(statusText = "Ready")).single()
        assertEquals(LogLevel.DEBUG, status.level)
        assertTrue(stateChangeLogEntries(before, before).isEmpty())
    }
}
