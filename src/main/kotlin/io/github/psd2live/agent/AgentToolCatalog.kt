package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.project.MutationAuthor
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Which operations a session lists as tools. Every operation stays callable; the profile changes publication only. */
enum class AgentToolProfile(val key: String) {
    /**
     * A small set of tools with compact request schemas and no output schemas. Other operations go through
     * [CALL_TOOL]; request_id and project_id may be omitted, and background operations wait for their result.
     */
    CORE("core"),

    /** Every operation as its own tool, with the exact request and output schema. */
    FULL("full");

    companion object {
        fun fromKey(key: String?): AgentToolProfile? = entries.firstOrNull { it.key == key }
    }
}

internal const val CALL_TOOL = "workspace_call"
internal const val WAIT_FIELD = "wait_ms"
internal const val MAX_WAIT_MS = 30_000L
internal const val DEFAULT_CORE_WAIT_MS = 20_000L

/** The core profile's tools: discovery, observation, the atomic batch and the project lifecycle. */
internal val CORE_OPERATIONS: Set<String> = linkedSetOf(
    "workspace_inspect", "workspace_list_operations", "workspace_get_operation",
    "workspace_apply_edits", "workspace_preview_edits",
    "view_render_model", "view_render_poses", "view_compare_history",
    "rig_deform", "keyform_apply", "parameter_create",
    "history_list", "history_checkout",
    "project_open", "project_import_psd", "project_save", "project_save_as", "project_export_model",
    "job_wait", "job_get", "job_cancel",
)

private val CONTEXT_FIELDS = setOf("request_id", "project_id")

internal class AgentTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject?,
    val annotations: ToolAnnotations,
    val handler: suspend (JsonObject?) -> CallToolResult,
)

/** One publication of the registry: the tools a session lists, their exact published schemas and their handlers. */
internal class AgentToolCatalog(
    private val registry: WorkspaceOperationRegistry,
    private val workspace: WorkspaceStatePort,
    val profile: AgentToolProfile,
) {
    val tools: Map<String, AgentTool> = buildMap {
        registry.definitions().filter { profile == AgentToolProfile.FULL || it.id in CORE_OPERATIONS }.forEach { operation ->
            put(operation.id, operationTool(operation))
        }
        if (profile == AgentToolProfile.CORE) put(CALL_TOOL, callTool())
    }

    private fun operationTool(operation: WorkspaceOperationDefinition) = AgentTool(
        operation.id, operation.description,
        inputSchema = if (profile == AgentToolProfile.CORE) operation.compactEnvelope() else operation.requestEnvelope(),
        outputSchema = if (profile == AgentToolProfile.CORE) null else operation.responseEnvelope(),
        annotations = ToolAnnotations(readOnlyHint = operation.kind == WorkspaceOperationKind.QUERY,
            destructiveHint = operation.destructive, idempotentHint = operation.idempotent, openWorldHint = false),
    ) { arguments ->
        run(operation) {
            val envelope = arguments ?: throw WorkspaceValidationException("request", "Required object")
            if (profile == AgentToolProfile.FULL) {
                validateOperationSchema(envelope, operation.requestEnvelope(), "arguments")
                invoke(operation, Prepared(envelope.getValue("request").jsonObject, null), wait = 0)
            } else {
                val wait = waitOf(operation, envelope)
                val request = envelope["request"] as? JsonObject ?: throw WorkspaceValidationException("arguments.request", "expected object")
                val prepared = prepare(operation, request)
                validateOperationSchema(JsonObject(envelope - WAIT_FIELD + ("request" to prepared.request)), operation.requestEnvelope(), "arguments")
                invoke(operation, prepared, wait)
            }
        }
    }

    private fun callTool() = AgentTool(CALL_TOOL,
        "Call any operation listed by workspace_list_operations, including those not published as tools. Read its request " +
            "fields with workspace_get_operation. request_id and project_id may be omitted; background operations wait up to " +
            "wait_ms (default $DEFAULT_CORE_WAIT_MS) and return the job with its result, or the running job to pass to job_wait.",
        inputSchema = buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("operation") { put("type", "string"); put("minLength", 1); put("description", "Operation ID, for example motion_set_key.") }
                putJsonObject("request") { put("type", "object"); put("description", "The operation's request fields.") }
                put(WAIT_FIELD, waitSchema())
            }
            put("required", JsonArray(listOf(JsonPrimitive("operation"), JsonPrimitive("request"))))
        },
        outputSchema = null,
        // Every call carries an explicit or derived request ID, so a repeated call recovers the first outcome.
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false),
    ) { arguments ->
        val id = (arguments?.get("operation") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val operation = id?.let { runCatching { registry.definition(it) }.getOrNull() }
        if (operation == null) {
            val failure = WorkspaceValidationException("arguments.operation",
                if (id == null) "expected string" else "unknown operation $id; use workspace_list_operations")
            val data = buildJsonObject { put("ok", false); put("operation", CALL_TOOL); put("error", WorkspaceFailure.from(failure).toJson()) }
            CallToolResult(content = listOf(TextContent(data.toString())), structuredContent = data, isError = true)
        } else run(operation) {
            val envelope = requireNotNull(arguments)
            val unknown = envelope.keys - setOf("operation", "request", WAIT_FIELD)
            if (unknown.isNotEmpty()) throw WorkspaceValidationException("arguments.${unknown.first()}", "unknown field")
            val wait = waitOf(operation, envelope)
            val request = envelope["request"] as? JsonObject ?: throw WorkspaceValidationException("arguments.request", "expected object")
            val prepared = prepare(operation, request)
            validateOperationSchema(prepared.request, operation.requestSchema, "arguments.request")
            invoke(operation, prepared, wait)
        }
    }

    private suspend fun invoke(operation: WorkspaceOperationDefinition, prepared: Prepared, wait: Long): CallToolResult {
        val context = WorkspaceOperationContext(MutationAuthor.AGENT)
        val derived = prepared.derived
        val request = if (derived == null) prepared.request
            else JsonObject(prepared.request + ("request_id" to JsonPrimitive(derivedId(derived, context))))
        var output = try { registry.invoke(operation.id, request, context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { derived?.let(::retire); throw failure }
        val job = output.data
        if (operation.jobBacked && wait > 0 && job["terminal"] == JsonPrimitive(false)) {
            // The job snapshot satisfies the starting operation's own output contract, so it replaces the handle.
            output = registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")); put("timeout_ms", wait) }, context)
        }
        if (derived != null && operation.jobBacked) {
            derivedJobs[request.getValue("request_id").jsonPrimitive.content] = job.getValue("id").jsonPrimitive.content
            if (output.data["status"]?.jsonPrimitive?.content in RETRYABLE_JOB_STATUS) retire(derived)
        }
        val result = buildJsonObject { put("ok", true); put("operation", operation.id); put("data", output.data) }
        validateWorkspaceResult(operation.id, operation.responseEnvelope(), result)
        return CallToolResult(content = listOf(TextContent(result.toString())) + output.images.map {
            ImageContent(Base64.getEncoder().encodeToString(it), "image/png")
        }, structuredContent = result)
    }

    private suspend fun run(operation: WorkspaceOperationDefinition, action: suspend () -> CallToolResult): CallToolResult =
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { operationError(operation, failure) }

    private fun waitOf(operation: WorkspaceOperationDefinition, envelope: JsonObject): Long {
        val raw = envelope[WAIT_FIELD] ?: return if (operation.jobBacked) DEFAULT_CORE_WAIT_MS else 0
        val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: throw WorkspaceValidationException("arguments.$WAIT_FIELD", "expected integer")
        if (value !in 0..MAX_WAIT_MS) throw WorkspaceValidationException("arguments.$WAIT_FIELD", "expected 0..$MAX_WAIT_MS")
        if (!operation.jobBacked) throw WorkspaceValidationException("arguments.$WAIT_FIELD", "only background operations wait")
        return value
    }

    private class Prepared(val request: JsonObject, val derived: String?)

    // Executed requests keep their outcome, failures included. An identical call after a failed or cancelled attempt
    // is a new attempt with the next derived ID; a retry of a call whose outcome was never seen recovers that outcome.
    private val attempts = ConcurrentHashMap<String, Int>()
    private val derivedJobs = ConcurrentHashMap<String, String>()

    private fun retire(digest: String) { attempts.merge(digest, 1, Int::plus) }

    private fun currentId(digest: String) = attempts[digest]?.let { "auto-$digest-$it" } ?: "auto-$digest"

    private suspend fun derivedId(digest: String, context: WorkspaceOperationContext): String {
        val id = currentId(digest)
        val job = derivedJobs[id] ?: return id
        val status = runCatching {
            registry.invoke("job_get", buildJsonObject { put("id", job) }, context).data["status"]?.jsonPrimitive?.content
        }.getOrNull()
        if (status != null && status !in RETRYABLE_JOB_STATUS) return id
        retire(digest)
        return currentId(digest)
    }

    /**
     * Fills omitted request context. project_id is the loaded project: a request whose state belongs to another load still
     * conflicts on state. request_id is derived from the operation and its arguments, so an identical retry, which
     * includes the same state, recovers the original result exactly as a repeated explicit ID does.
     */
    private fun prepare(operation: WorkspaceOperationDefinition, request: JsonObject): Prepared {
        val fields = operation.requestSchema["properties"]?.jsonObject.orEmpty()
        var prepared = request
        if ("project_id" in fields && "project_id" !in prepared && operation.workspaceBound) {
            val project = workspace.snapshot().projectId
            if (project != null || operation.kind == WorkspaceOperationKind.PROJECT) prepared = JsonObject(prepared + ("project_id" to JsonPrimitive(project)))
        }
        if ("request_id" !in fields || "request_id" in prepared) return Prepared(prepared, null)
        val digest = MessageDigest.getInstance("SHA-256").digest("${operation.id}\n${canonical(prepared)}".encodeToByteArray())
        val key = digest.take(16).joinToString("") { "%02x".format(it) }
        // Validation sees an ID of the same shape; the attempt's actual ID is chosen when it runs.
        return Prepared(JsonObject(prepared + ("request_id" to JsonPrimitive("auto-$key"))), key)
    }

    private fun operationError(operation: WorkspaceOperationDefinition, failure: Exception): CallToolResult {
        val data = buildJsonObject {
            put("ok", false); put("operation", operation.id)
            put("error", WorkspaceFailure.from(failure).toJson())
        }
        validateWorkspaceResult(operation.id, operation.responseEnvelope(), data)
        return CallToolResult(content = listOf(TextContent(data.toString())), structuredContent = data, isError = true)
    }
}

private val RETRYABLE_JOB_STATUS = setOf("failed", "cancelled")

private fun waitSchema() = buildJsonObject {
    put("type", "integer"); put("minimum", 0); put("maximum", MAX_WAIT_MS)
    put("description", "Background operations only: wait this long for the job to finish (default $DEFAULT_CORE_WAIT_MS). " +
        "A finished job returns its result; otherwise pass the returned id to job_wait.")
}

/** Key order does not change a request, so it does not change the derived ID either. */
private fun canonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonical(value.getValue(it)) }
    is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
    else -> value.toString()
}

/**
 * The core publication of a request: request_id and project_id are optional, background operations accept wait_ms,
 * and batch members name their operation instead of repeating every member schema. Execution still validates the exact one.
 */
internal fun WorkspaceOperationDefinition.compactEnvelope(): JsonObject {
    val exact = requestEnvelope()
    val request = compactRequest(exact.getValue("properties").jsonObject.getValue("request").jsonObject)
    return JsonObject(exact + mapOf("properties" to buildJsonObject {
        put("request", request)
        if (jobBacked) put(WAIT_FIELD, waitSchema())
    }))
}

private fun compactRequest(schema: JsonObject): JsonObject {
    val next = schema.toMutableMap()
    schema["required"]?.jsonArray?.let { required -> next["required"] = JsonArray(required.filter { it.jsonPrimitive.content !in CONTEXT_FIELDS }) }
    schema["oneOf"]?.jsonArray?.let { branches -> next["oneOf"] = JsonArray(branches.map { compactRequest(it.jsonObject) }) }
    schema["properties"]?.jsonObject?.let { properties -> next["properties"] = JsonObject(properties.mapValues { (_, field) -> compactMembers(field) }) }
    return JsonObject(next)
}

/** An array of `{operation, request}` members publishes the operation names; each member schema is one lookup away. */
private fun compactMembers(field: JsonElement): JsonElement {
    val items = (field as? JsonObject)?.get("items") as? JsonObject ?: return field
    val branches = items["oneOf"]?.jsonArray ?: return field
    val operations = branches.map {
        it.jsonObject["properties"]?.jsonObject?.get("operation")?.jsonObject?.get("const")?.jsonPrimitive?.content ?: return field
    }
    return JsonObject(field.jsonObject + ("items" to buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("operation") { put("type", "string"); put("enum", JsonArray(operations.map(::JsonPrimitive))) }
            putJsonObject("request") {
                put("type", "object")
                put("description", "The member operation's business fields, without request_id, project_id or state; workspace_get_operation describes them.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("operation"), JsonPrimitive("request"))))
    }))
}
