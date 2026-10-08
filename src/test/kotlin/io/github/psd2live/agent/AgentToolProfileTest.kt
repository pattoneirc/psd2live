package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.ServerSocket
import kotlin.test.*

class AgentToolProfileTest {
    private val export = buildJsonObject { putJsonObject("request") { put("state", "generation:0"); put("output_directory", "/out") } }
    private val exportCall = JsonObject(export + (OP_FIELD to JsonPrimitive("export_model")))

    @Test fun corePublishesFewCompactToolsWithoutOutputSchemas() {
        val backend = ExportingBackend()
        WorkspaceOperations(backend).use { operations ->
            val catalog = AgentToolCatalog(operations.registry, backend, AgentToolProfile.CORE)
            assertEquals(CORE_OPERATIONS + AGENT_TOOL_FAMILIES.map { it.name } + CALL_TOOL, catalog.tools.keys)
            assertEquals(CORE_TOOL_COUNT, catalog.tools.size)
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
            val listed = published.sumOf { it.getValue("inputSchema").toString().length } + catalog.tools.values.sumOf { it.description.length }
            assertTrue(listed < 75_000, "core listing is $listed characters")
            val batch = catalog.tools.getValue("workspace_apply_edits").inputSchema.toString()
            assertTrue(batch.length < 6_000, "batch schema is ${batch.length} characters")
            assertTrue("\"rig_deform\"" in batch)
            val project = catalog.tools.getValue("project")
            assertTrue(WAIT_FIELD in project.inputSchema.getValue("properties").jsonObject)
            assertTrue("\n- export_model: state, output_directory [job]" in project.description, project.description)
            // Background tools say that the core call waits, not only that it returns a job handle.
            assertTrue(CORE_WAIT_NOTE in project.description && CORE_WAIT_NOTE in catalog.tools.getValue("workspace_apply_edits").description)
            assertFalse(CORE_WAIT_NOTE in catalog.tools.getValue("rig_deform").description || CORE_WAIT_NOTE in catalog.tools.getValue("motion").description)
            assertFalse(CORE_WAIT_NOTE in AgentToolCatalog(operations.registry, backend, AgentToolProfile.FULL).tools.getValue("workspace_apply_edits").description)
            assertFalse(WAIT_FIELD in catalog.tools.getValue("rig_deform").inputSchema.getValue("properties").jsonObject)
        }
    }

    @Test fun coreDerivesContextWaitsForJobsAndRecoversIdenticalRetries() = runBlocking {
        val backend = ExportingBackend()
        WorkspaceOperations(backend).use { operations ->
            val server = createAgentMcpServer(backend, operations, AgentToolProfile.CORE)
            val first = server.call("project", exportCall)
            assertNull(first.isError?.takeIf { it }, first.structuredContent.toString())
            assertEquals("completed", first.data().getValue("status").jsonPrimitive.content)
            assertEquals("/out/model.cmo3", first.data().getValue("result").jsonObject.getValue("files").jsonArray.single()
                .jsonObject.getValue("path").jsonPrimitive.content)
            val again = server.call("project", exportCall)
            assertEquals(first.data().getValue("id"), again.data().getValue("id"))
            assertEquals(1, backend.exports)
        }
    }

    @Test fun anIdenticalCallAfterAFailedJobIsANewAttempt() = runBlocking {
        val backend = ExportingBackend(failures = 1)
        WorkspaceOperations(backend).use { operations ->
            val server = createAgentMcpServer(backend, operations, AgentToolProfile.CORE)
            assertEquals("failed", server.call("project", exportCall).data().getValue("status").jsonPrimitive.content)
            assertEquals("completed", server.call("project", exportCall).data().getValue("status").jsonPrimitive.content)
            assertEquals(2, backend.exports)
        }
    }

    @Test fun zeroWaitReturnsTheRunningJob() = runBlocking {
        val backend = ExportingBackend(release = CompletableDeferred())
        WorkspaceOperations(backend).use { operations ->
            val server = createAgentMcpServer(backend, operations, AgentToolProfile.CORE)
            val started = server.call("project", JsonObject(exportCall + (WAIT_FIELD to JsonPrimitive(0))))
            assertEquals(false, started.data().getValue("terminal").jsonPrimitive.boolean)
            backend.release.complete(Unit)
            val waited = server.call("job", buildJsonObject { put(OP_FIELD, "wait"); putJsonObject("request") { put("id", started.data().getValue("id")) } })
            assertEquals("completed", waited.data().getValue("status").jsonPrimitive.content)
            val invalid = server.call("rig_deform", buildJsonObject { putJsonObject("request") {}; put(WAIT_FIELD, 10) })
            assertEquals("invalid_request", invalid.errorCode())
        }
    }

    @Test fun familiesPublishFlatSchemasAndRunTheNamedOperationExactly() = runBlocking {
        val backend = ExportingBackend()
        WorkspaceOperations(backend).use { operations ->
            val catalog = AgentToolCatalog(operations.registry, backend, AgentToolProfile.CORE)
            val definitions = operations.registry.definitions()
            AGENT_TOOL_FAMILIES.forEach { family ->
                val schema = catalog.tools.getValue(family.name).inputSchema.toString()
                // Strict hosts reject oneOf and const, and references would leave the members' definitions behind.
                listOf("\"oneOf\"", "\"const\"", "\"\$ref\"", "\"\$defs\"").forEach { keyword -> assertFalse(keyword in schema, "${family.name} uses $keyword") }
                assertTrue(schema.length < 12_000, "${family.name} schema is ${schema.length} characters")
                assertTrue(family.members(definitions).isNotEmpty())
            }
            assertEquals(listOf("put", "delete"), AGENT_TOOL_FAMILIES.single { it.name == "swing" }.members(definitions).map { it.id.removePrefix("swing_") })
            val server = createAgentMcpServer(backend, operations, AgentToolProfile.CORE)
            val listed = server.call("job", buildJsonObject { put(OP_FIELD, "list"); putJsonObject("request") {} })
            assertEquals("job_list", listed.structuredContent!!.getValue("operation").jsonPrimitive.content)
            val unknownOp = server.call("job", buildJsonObject { put(OP_FIELD, "explode"); putJsonObject("request") {} })
            assertEquals("invalid_request", unknownOp.errorCode())
            assertEquals("job", unknownOp.structuredContent!!.getValue("operation").jsonPrimitive.content)
            val foreignField = server.call("project", buildJsonObject {
                put(OP_FIELD, "export_model"); putJsonObject("request") { put("state", "generation:0"); put("output_directory", "/out"); put("path", "/x.psd") } })
            assertEquals("invalid_request", foreignField.errorCode())
            assertEquals(0, backend.exports)
        }
    }

    @Test fun workspaceCallReachesUnpublishedOperationsWithTheExactSchema() = runBlocking {
        val backend = ExportingBackend()
        WorkspaceOperations(backend).use { operations ->
            val server = createAgentMcpServer(backend, operations, AgentToolProfile.CORE)
            assertFalse("job_list" in server.tools || "physics_preset_list" in server.tools)
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
        AgentMcpController(ExportingBackend(), store).use { controller ->
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
