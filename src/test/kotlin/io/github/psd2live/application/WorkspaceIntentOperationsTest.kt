package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Intent operations compile against the real captured model and commit through the real atomic batch. */
class WorkspaceIntentOperationsTest {
    @TempDir lateinit var temporary: Path
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private val builder = WorkspacePreviewBuilder()

    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>) : WorkspaceBackendStub() {
        val commands = WorkspaceDocumentCommands(runtime, WorkspaceSimulationWork.Direct)
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        override fun captureQueries(): WorkspaceQueries = WorkspaceReadSession(runtime.read())
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture()
            return WorkspaceDocumentCommands.mutationResult(before, commands.execute(before.projectId, state, summary, edits, author), summary, edits)
        }
    }

    private suspend fun host(): Host {
        val (png, _) = writeSourceImportFixture(temporary)
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        WorkspaceSourceImporter(runtime).createArtwork(sourceImportArguments(png), null, runtime.state.value.state,
            initialConfig = PipelineConfig(atlasSize = 256, meshOnly = true, exportMoc3 = false))
        return Host(runtime)
    }

    private suspend fun WorkspaceOperations.call(id: String, request: JsonObject) = registry.invoke(id, request, agent).data
    private suspend fun WorkspaceOperations.run(id: String, host: Host, fields: JsonObject): JsonObject {
        val snapshot = host.snapshot()
        val started = call(id, JsonObject(fields + mapOf("request_id" to JsonPrimitive("$id-${snapshot.state}"),
            "project_id" to JsonPrimitive(snapshot.projectId), "state" to JsonPrimitive(snapshot.state))))
        return call("job_wait", buildJsonObject { put("id", started.getValue("id")) })
    }

    private fun axis(mesh: String, fields: JsonObject.() -> JsonObject = { this }) = buildJsonObject {
        putJsonObject("parameter") { put("id", "ParamIntent"); put("name", "Intent") }
        putJsonArray("shapes") { add(buildJsonObject {
            put("target", mesh); put("value", 1)
            putJsonArray("operations") { add(buildJsonObject { put("type", "translate"); putJsonArray("delta") { add(0.1); add(0) } }) }
        }) }
    }.fields()

    @Test fun axisCreatesTheParameterKeysTheNeutralAndDeformsTheEndInOneHistoryStep() = runBlocking {
        val host = host()
        WorkspaceOperations(host).use { operations ->
            val read = WorkspaceReadSession(host.runtime.read())
            val mesh = read.listRigObjects().first { it.kind == "mesh" }
            val history = host.runtime.history().selections.size
            val done = operations.run("author_axis", host, axis("mesh:${mesh.id}"))
            assertEquals("completed", done.getValue("status").jsonPrimitive.content, done.toString())
            assertEquals(3, done.getValue("result").jsonObject.getValue("edit_count").jsonPrimitive.int)
            assertEquals(history + 1, host.runtime.history().selections.size)
            val after = WorkspaceReadSession(host.runtime.read())
            assertTrue(after.snapshot().parameters.any { it.id == "ParamIntent" })
            val keys = after.getObject(mesh).geometry!!.axes.single { it.parameterId == "ParamIntent" }.keys
            assertTrue(0f in keys && 1f in keys, keys.toString())

            // The same parameter again reuses it and its neutral key: only the deformation remains to compile.
            val reused = operations.run("author_axis", host, axis("mesh:${mesh.id}") {
                JsonObject(this + ("shapes" to JsonArray(listOf(getValue("shapes").jsonArray.single().jsonObject + ("value" to JsonPrimitive(-1)))
                    .map(::JsonObject))))
            })
            assertEquals(1, reused.getValue("result").jsonObject.getValue("edit_count").jsonPrimitive.int)
        }
    }

    @Test fun compileShowsTheMembersThatCommitTheSameAsTheIntent() = runBlocking<Unit> {
        val host = host()
        WorkspaceOperations(host).use { operations ->
            val mesh = "mesh:" + WorkspaceReadSession(host.runtime.read()).listRigObjects().first { it.kind == "mesh" }.id
            val state = host.snapshot().state
            val history = host.runtime.history().selections.size
            val compiled = operations.call("author_compile", buildJsonObject {
                put("intent", "author_axis"); put("request", JsonObject(axis(mesh) + ("state" to JsonPrimitive(state))))
            })
            val edits = compiled.getValue("edits").jsonArray
            assertEquals(listOf("parameter_create", "keyform_apply", "rig_deform"), edits.map { it.jsonObject.getValue("operation").jsonPrimitive.content })
            assertEquals(history, host.runtime.history().selections.size)
            val applied = operations.run("workspace_apply_edits", host, buildJsonObject { put("edits", edits) })
            assertEquals("completed", applied.getValue("status").jsonPrimitive.content, applied.toString())
            assertTrue(host.snapshot().parameters.any { it.id == "ParamIntent" })
            // A capture of another version cannot describe what the request's state would commit.
            assertFailsWith<WorkspaceConflict> {
                operations.call("author_compile", buildJsonObject {
                    put("intent", "author_axis"); put("request", JsonObject(axis(mesh) + ("state" to JsonPrimitive(state))))
                })
            }
        }
    }

    @Test fun axisRefusesARangeThatDiffersFromTheExistingParameterAndAShapeAtTheDefault() = runBlocking {
        val host = host()
        WorkspaceOperations(host).use { operations ->
            val mesh = "mesh:" + WorkspaceReadSession(host.runtime.read()).listRigObjects().first { it.kind == "mesh" }.id
            assertEquals("completed", operations.run("author_axis", host, axis(mesh)).getValue("status").jsonPrimitive.content)
            val history = host.runtime.history().selections.size
            assertFailsWith<IllegalArgumentException> {
                operations.run("author_axis", host, axis(mesh) {
                    JsonObject(this + ("parameter" to buildJsonObject { put("id", "ParamIntent"); put("min", -30); put("max", 30) }))
                })
            }
            assertFailsWith<IllegalArgumentException> {
                operations.run("author_axis", host, axis(mesh) {
                    JsonObject(this + ("shapes" to JsonArray(listOf(JsonObject(getValue("shapes").jsonArray.single().jsonObject + ("value" to JsonPrimitive(0)))))))
                })
            }
            assertEquals(history, host.runtime.history().selections.size)
        }
    }

    @Test fun physicsPutsTheGroupAndOverviewSummarizesOneCapture() = runBlocking {
        val host = host()
        WorkspaceOperations(host).use { operations ->
            val mesh = "mesh:" + WorkspaceReadSession(host.runtime.read()).listRigObjects().first { it.kind == "mesh" }.id
            operations.run("author_axis", host, axis(mesh))
            val physics = operations.run("author_physics", host, buildJsonObject {
                put("id", "IntentSway"); put("name", "Intent sway"); put("fit_target", JsonNull)
                putJsonArray("outputs") { add(buildJsonObject { put("parameter", "ParamIntent") }) }
            })
            assertEquals("completed", physics.getValue("status").jsonPrimitive.content, physics.toString())
            assertEquals(1, physics.getValue("result").jsonObject.getValue("edit_count").jsonPrimitive.int)
            // An ID with a fit target refits the group as it is; changed fields and the fit commit together.
            val refit = operations.run("author_physics", host, buildJsonObject { put("id", "IntentSway"); put("fit_target", 80) })
            assertEquals(1, refit.getValue("result").jsonObject.getValue("edit_count").jsonPrimitive.int, refit.toString())
            // The fit target is a percent, not an object: only the group is reported changed.
            assertEquals(listOf("IntentSway"), refit.getValue("result").jsonObject.getValue("changed").jsonArray.map { it.jsonPrimitive.content })
            val history = host.runtime.history().selections.size
            val changed = operations.run("author_physics", host, buildJsonObject { put("id", "IntentSway"); put("mobility", 0.6) })
            assertEquals(2, changed.getValue("result").jsonObject.getValue("edit_count").jsonPrimitive.int, changed.toString())
            assertEquals(history + 1, host.runtime.history().selections.size)
            assertFailsWith<IllegalArgumentException> {
                operations.run("author_physics", host, buildJsonObject { put("id", "IntentSway"); put("fit_target", JsonNull) })
            }

            val overview = operations.call("workspace_overview", buildJsonObject { put("limit", 1) })
            assertEquals(host.snapshot().state, overview.getValue("state").jsonPrimitive.content)
            val parameters = overview.getValue("parameters").jsonObject
            assertTrue(parameters.getValue("total").jsonPrimitive.int >= 1)
            assertTrue(parameters.getValue("items").jsonArray.size <= 1)
            assertTrue(overview.getValue("physics").jsonObject.getValue("items").jsonArray.isNotEmpty() ||
                overview.getValue("physics").jsonObject.getValue("total").jsonPrimitive.int > 0)
            assertTrue(overview.getValue("meshes").jsonPrimitive.int >= 1)
        }
    }
}
