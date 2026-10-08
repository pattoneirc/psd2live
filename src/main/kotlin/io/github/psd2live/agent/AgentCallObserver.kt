package io.github.psd2live.agent

import kotlinx.serialization.json.JsonObject

/** One finished MCP tool call: what was asked, how it ended and the model renders it returned. */
class AgentCallRecord(
    /** The published tool the client called, for example `view` or `workspace_call`. */
    val tool: String,
    /** The operation it ran, or [tool] when the call never resolved one. */
    val operation: String,
    /** True for read-only operations; renders are read-only too, but carry [images]. */
    val readOnly: Boolean,
    val ok: Boolean,
    val durationMs: Long,
    val request: JsonObject?,
    /** The operation's result on success. */
    val data: JsonObject?,
    /** The failure's code and message when [ok] is false. */
    val error: JsonObject?,
    val images: List<ByteArray>,
)

/** Told of every MCP tool call once it finishes, on the calling coroutine; must return quickly and never throw. */
fun interface AgentCallObserver {
    fun onCall(record: AgentCallRecord)

    /** A client finished the MCP handshake; [client] is the name and version it reported. */
    fun onClientConnected(client: String) {}

    /** A client's session closed. */
    fun onClientDisconnected(client: String) {}
}
