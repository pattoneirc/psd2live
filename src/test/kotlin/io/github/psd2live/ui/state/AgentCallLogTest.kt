package io.github.psd2live.ui.state

import io.github.psd2live.agent.AgentCallRecord
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentCallLogTest {
    private fun record(operation: String, readOnly: Boolean, ok: Boolean = true, data: JsonObject? = null,
                       error: JsonObject? = null, images: List<ByteArray> = emptyList(), tool: String = operation) =
        AgentCallRecord(tool, operation, readOnly, ok, 12, buildJsonObject { put("state", "s"); put("request_id", "r"); put("layer_id", "eye") },
            data, error, images)

    @Test fun writesAreTheAgentsAndReadsSitAtDebug() {
        val write = agentCallLogEntries(record("rig_deform", readOnly = false,
            data = buildJsonObject { put("history_node_id", "n1") })).single()
        assertEquals(LogSource.AGENT, write.source)
        assertEquals(LogLevel.SUCCESS, write.level)
        assertEquals("rig", write.tag)
        assertEquals("rig_deform · history n1 · 12 ms", write.message)
        // The envelope every write repeats stays out of the detail.
        assertEquals("""{"layer_id":"eye"}""", write.detail)

        val read = agentCallLogEntries(record("workspace_inspect", readOnly = true, data = buildJsonObject {})).single()
        assertEquals(LogSource.MCP_SERVER, read.source)
        assertEquals(LogLevel.DEBUG, read.level)
    }

    @Test fun rendersShowAtInfoWithOneLinePerImage() {
        val images = listOf(byteArrayOf(1), byteArrayOf(2))
        val entries = agentCallLogEntries(record("view_render", readOnly = true, tool = "view",
            data = buildJsonObject { put("view_id", "v7") }, images = images))
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.level == LogLevel.INFO && it.source == LogSource.MCP_SERVER })
        assertEquals("view_render (view) · 12 ms", entries[0].message)
        assertContentEquals(images[0], entries[0].imageBytes)
        assertEquals("view_render: v7 (1/2)", entries[0].imageLabel)
        assertEquals("view_render: v7 (2/2)", entries[1].imageLabel)
    }

    @Test fun failuresAreErrorsUnlessARetryRecovers() {
        fun failure(code: String) = agentCallLogEntries(record("rig_deform", readOnly = false, ok = false,
            error = buildJsonObject { put("code", code); put("message", "boom") })).single()
        assertEquals(LogLevel.ERROR, failure("invalid_request").level)
        assertEquals("rig_deform · invalid_request: boom · 12 ms", failure("invalid_request").message)
        assertEquals(LogLevel.WARNING, failure("state_conflict").level)
        val failedJob = agentCallLogEntries(record("project_export_model", readOnly = false,
            data = buildJsonObject { put("status", "failed") })).single()
        assertEquals(LogLevel.ERROR, failedJob.level)
    }
}
