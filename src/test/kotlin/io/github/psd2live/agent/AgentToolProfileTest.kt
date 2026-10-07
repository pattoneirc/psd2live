package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.project.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.ServerSocket
import kotlin.test.*

class AgentToolProfileTest {
    private val connection = java.lang.reflect.Proxy.newProxyInstance(ClientConnection::class.java.classLoader,
        arrayOf(ClientConnection::class.java)) { _, method, _ ->
        if (method.name == "getSessionId") "test" else error("Unexpected client notification")
    } as ClientConnection

    private class Backend(private val failures: Int = 0) : WorkspaceBackendStub() {
        var release = CompletableDeferred(Unit)
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

    private suspend fun Server.call(tool: String, arguments: JsonObject): CallToolResult =
        tools.getValue(tool).handler.invoke(connection, CallToolRequest(CallToolRequestParams(tool, arguments)))

    private fun CallToolResult.data() = structuredContent!!.getValue("data").jsonObject
    private fun CallToolResult.errorCode() = structuredContent!!.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private val export = buildJsonObject { putJsonObject("request") { put("state", "generation:0"); put("output_directory", "/out") } }

    @Test fun corePublishesFewCompactToolsWithoutOutputSchemas() {
        val backend = Backend()
        WorkspaceOperations(backend).use { operations ->
            val catalog = AgentToolCatalog(operations.registry, backend, AgentToolProfile.CORE)
            assertEquals(CORE_OPERATIONS + CALL_TOOL, catalog.tools.keys)
            assertTrue(catalog.tools.values.all { it.outputSchema == null })
            // The stdio proxy retries a call after a transport error only when the tool says that is safe.
            assertTrue(catalog.tools.values.all { it.annotations.readOnlyHint == true || it.annotations.idempotentHint == true })
            val listing = buildJsonObject {
                put("jsonrpc", "2.0")
                putJsonObject("result") { putJsonArray("tools") {
                    catalog.tools.keys.forEach { name -> add(buildJsonObject { put("name", name); putJsonObject("outputSchema") {} }) }
                } }
            }.withExactToolPublication(catalog)
            val published = listing.getValue("result").jsonObject.getValue("tools").jsonArray.map { it.jsonObject }
            assertTrue(published.none { "outputSchema" in it })
            // The whole core listing stays far below one full-profile batch schema.
            assertTrue(published.sumOf { it.getValue("inputSchema").toString().length } < 48_000)
            val batch = catalog.tools.getValue("workspace_apply_edits").inputSchema.toString()
            assertTrue(batch.length < 6_000, "batch schema is ${batch.length} characters")
            assertTrue("\"rig_deform\"" in batch)
            val request = catalog.tools.getValue("project_export_model").inputSchema.getValue("properties").jsonObject
            assertEquals(listOf("state", "output_directory"), request.getValue("request").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(WAIT_FIELD in request)
            assertFalse(WAIT_FIELD in catalog.tools.getValue("rig_deform").inputSchema.getValue("properties").jsonObject)
        }
    }

    @Test fun coreDerivesContextWaitsForJobsAndRecoversIdenticalRetries() = runBlocking {
        val backend = Backend()
        WorkspaceOperations(backend).use { operations ->
            val server = createAgentMcpServer(backend, operations, AgentToolProfile.CORE)
            val first = server.call("project_export_model", export)
            assertNull(first.isError?.takeIf { it }, first.structuredContent.toString())
            assertEquals("completed", first.data().getValue("status").jsonPrimitive.content)
            assertEquals("/out/model.cmo3", first.data().getValue("result").jsonObject.getValue("files").jsonArray.single()
                .jsonObject.getValue("path").jsonPrimitive.content)
            val again = server.call("project_export_model", export)
            assertEquals(first.data().getValue("id"), again.data().getValue("id"))
            assertEquals(1, backend.exports)
        }
    }

    @Test fun anIdenticalCallAfterAFailedJobIsANewAttempt() = runBlocking {
        val backend = Backend(failures = 1)
        WorkspaceOperations(backend).use { operations ->
            val server = createAgentMcpServer(backend, operations, AgentToolProfile.CORE)
            assertEquals("failed", server.call("project_export_model", export).data().getValue("status").jsonPrimitive.content)
            assertEquals("completed", server.call("project_export_model", export).data().getValue("status").jsonPrimitive.content)
            assertEquals(2, backend.exports)
        }
    }

    @Test fun zeroWaitReturnsTheRunningJob() = runBlocking {
        val backend = Backend().apply { release = CompletableDeferred() }
        WorkspaceOperations(backend).use { operations ->
            val server = createAgentMcpServer(backend, operations, AgentToolProfile.CORE)
            val started = server.call("project_export_model", JsonObject(export + (WAIT_FIELD to JsonPrimitive(0))))
            assertEquals(false, started.data().getValue("terminal").jsonPrimitive.boolean)
            backend.release.complete(Unit)
            val waited = server.call("job_wait", buildJsonObject { putJsonObject("request") { put("id", started.data().getValue("id")) } })
            assertEquals("completed", waited.data().getValue("status").jsonPrimitive.content)
            val invalid = server.call("rig_deform", buildJsonObject { putJsonObject("request") {}; put(WAIT_FIELD, 10) })
            assertEquals("invalid_request", invalid.errorCode())
        }
    }

    @Test fun workspaceCallReachesUnpublishedOperationsWithTheExactSchema() = runBlocking {
        val backend = Backend()
        WorkspaceOperations(backend).use { operations ->
            val server = createAgentMcpServer(backend, operations, AgentToolProfile.CORE)
            assertFalse("job_list" in server.tools)
            val listed = server.call(CALL_TOOL, buildJsonObject { put("operation", "job_list"); putJsonObject("request") {} })
            assertEquals("job_list", listed.structuredContent!!.getValue("operation").jsonPrimitive.content)
            assertEquals(0, listed.data().getValue("total").jsonPrimitive.int)
            val exported = server.call(CALL_TOOL, buildJsonObject { put("operation", "project_export_model"); put("request", export.getValue("request")) })
            assertEquals("completed", exported.data().getValue("status").jsonPrimitive.content)
            assertEquals("invalid_request", server.call(CALL_TOOL, buildJsonObject {
                put("operation", "job_list"); putJsonObject("request") { put("unexpected", 1) } }).errorCode())
            assertEquals("invalid_request", server.call(CALL_TOOL, buildJsonObject {
                put("operation", "no_such_operation"); putJsonObject("request") {} }).errorCode())
        }
    }

    @Test fun controllerRestartsTheEndpointWithSavedSettings() {
        val store = object : AgentMcpSettingsStore {
            var saved = AgentMcpSettings(port = freePort(), token = AgentMcpCredentials.generateToken())
            override fun load() = saved
            override fun save(settings: AgentMcpSettings) { saved = settings }
        }
        AgentMcpController(Backend(), store).use { controller ->
            val running = assertIs<AgentMcpStatus.Running>(controller.start())
            assertEquals(store.saved.port, running.connection.port)
            assertEquals(AgentToolProfile.CORE, running.connection.profile)
            val changed = store.saved.copy(port = freePort(), profile = AgentToolProfile.FULL)
            val restarted = assertIs<AgentMcpStatus.Running>(controller.apply(changed))
            assertEquals(changed.port, restarted.connection.port)
            assertEquals(changed, store.saved)
            assertEquals(AgentMcpStatus.Stopped, controller.apply(changed.copy(enabled = false)))
            assertFailsWith<IllegalArgumentException> { controller.apply(changed.copy(token = "short")) }
            assertEquals(changed.copy(enabled = false), store.saved)
        }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
}
