package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.project.WorkspaceProjectSnapshot
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import java.io.IOException

/** A client session that only answers its id; any notification sent to it fails the test. */
internal val testClientConnection = java.lang.reflect.Proxy.newProxyInstance(ClientConnection::class.java.classLoader,
    arrayOf(ClientConnection::class.java)) { _, method, _ ->
    if (method.name == "getSessionId") "test" else error("Unexpected client notification")
} as ClientConnection

/** Exports wait for [release]; the first [failures] exports fail as a full disk would. */
internal class ExportingBackend(
    private val failures: Int = 0,
    var release: CompletableDeferred<Unit> = CompletableDeferred(Unit),
) : WorkspaceBackendStub() {
    var exports = 0
    override fun snapshot() = WorkspaceProjectSnapshot("project", "revision", "head", true, "artwork",
        4, 4, false, "ready", null, emptyList(), emptyList(), state = "generation:0")
    override suspend fun exportModel(state: String, outputDirectory: String): JsonObject {
        exports++
        release.await()
        if (exports <= failures) throw IOException("disk full")
        return buildJsonObject {
            put("state", state); put("revision", "revision"); put("warnings", JsonArray(emptyList()))
            putJsonArray("files") { add(buildJsonObject { put("path", "$outputDirectory/model.cmo3"); put("bytes", 42) }) }
        }
    }
}

internal suspend fun Server.call(tool: String, arguments: JsonObject): CallToolResult =
    tools.getValue(tool).handler.invoke(testClientConnection, CallToolRequest(CallToolRequestParams(tool, arguments)))

internal fun CallToolResult.data() = structuredContent!!.getValue("data").jsonObject
internal fun CallToolResult.errorCode() = structuredContent!!.getValue("error").jsonObject.getValue("code").jsonPrimitive.content
