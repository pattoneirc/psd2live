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

/** The registry describes background operations as returning a job handle; in the core profile the call waits first. */
internal const val CORE_WAIT_NOTE = "Here the call waits up to wait_ms (default $DEFAULT_CORE_WAIT_MS) and returns the finished job with its " +
    "result; a job still running returns its id for job_wait."

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
    internal val observer: AgentCallObserver? = null,
) {
    val tools: Map<String, AgentTool> = buildMap {
        val definitions = registry.definitions()
        definitions.filter { profile == AgentToolProfile.FULL || it.id in CORE_OPERATIONS }.forEach { operation ->
            put(operation.id, operationTool(operation))
        }
        if (profile == AgentToolProfile.CORE) {
            AGENT_TOOL_FAMILIES.forEach { family -> put(family.name, familyTool(family, family.members(definitions))) }
            put(CALL_TOOL, callTool())
        }
    }.mapValues { (_, tool) -> if (observer == null) tool else observed(tool, observer) }

    /** [tool] reporting each finished call to [observer]; a cancelled call is not reported. */
    private fun observed(tool: AgentTool, observer: AgentCallObserver) = AgentTool(tool.name, tool.description, tool.inputSchema,
        tool.outputSchema, tool.annotations) { arguments ->
        val started = System.nanoTime()
        val result = tool.handler(arguments)
        val structured = result.structuredContent
        val operation = (structured?.get("operation") as? JsonPrimitive)?.contentOrNull ?: tool.name
        val record = AgentCallRecord(
            tool = tool.name,
            operation = operation,
            readOnly = runCatching { registry.definition(operation).kind == WorkspaceOperationKind.QUERY }.getOrDefault(false),
            ok = result.isError != true,
            durationMs = (System.nanoTime() - started) / 1_000_000,
            request = arguments?.let { (it["request"] as? JsonObject) ?: it },
            data = structured?.get("data") as? JsonObject,
            error = structured?.get("error") as? JsonObject,
            images = result.content.filterIsInstance<ImageContent>().mapNotNull { runCatching { Base64.getDecoder().decode(it.data) }.getOrNull() },
        )
        runCatching { observer.onCall(record) }
        result
    }

    private fun operationTool(operation: WorkspaceOperationDefinition) = AgentTool(
        operation.id, if (profile == AgentToolProfile.CORE && operation.jobBacked) operation.description + " " + CORE_WAIT_NOTE else operation.description,
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
        dispatch(CALL_TOOL, "operation", arguments) { id ->
            runCatching { registry.definition(id) }.getOrNull()
                ?: throw WorkspaceValidationException("arguments.operation", "unknown operation $id; use workspace_list_operations")
        }
    }

    private fun familyTool(family: AgentToolFamily, members: List<WorkspaceOperationDefinition>): AgentTool {
        require(members.isNotEmpty()) { "Tool family ${family.name} has no operations" }
        val byOp = members.associateBy { family.op(it.id) }
        return AgentTool(family.name, family.description(members), family.inputSchema(members), outputSchema = null,
            // Members are queries or carry an explicit or derived request ID, so a repeated call recovers the first outcome.
            annotations = ToolAnnotations(readOnlyHint = members.all { it.kind == WorkspaceOperationKind.QUERY },
                destructiveHint = members.any { it.destructive }, idempotentHint = true, openWorldHint = false),
        ) { arguments ->
            dispatch(family.name, OP_FIELD, arguments) { op ->
                byOp[op] ?: throw WorkspaceValidationException("arguments.$OP_FIELD", "expected one of ${byOp.keys}")
            }
        }
    }

    /** Runs the operation that [selector] names, validating the request against that operation's exact schema. */
    private suspend fun dispatch(tool: String, selector: String, arguments: JsonObject?,
                                 resolve: (String) -> WorkspaceOperationDefinition): CallToolResult {
        val operation = try {
            val name = (arguments?.get(selector) as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw WorkspaceValidationException("arguments.$selector", "expected string")
            resolve(name)
        } catch (failure: WorkspaceValidationException) {
            val data = buildJsonObject { put("ok", false); put("operation", tool); put("error", WorkspaceFailure.from(failure).toJson()) }
            return CallToolResult(content = listOf(TextContent(data.toString())), structuredContent = data, isError = true)
        }
        return run(operation) {
            val envelope = requireNotNull(arguments)
            val unknown = envelope.keys - setOf(selector, "request", WAIT_FIELD)
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

internal fun waitSchema() = buildJsonObject {
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

internal fun compactRequest(schema: JsonObject): JsonObject {
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
