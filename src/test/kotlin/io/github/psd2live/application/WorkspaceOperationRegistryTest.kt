package io.github.psd2live.application

import io.github.psd2live.project.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceOperationRegistryTest {
    private val s = WorkspaceResultSchema
    private fun ref(name: String) = buildJsonObject { put("\$ref", "#/\$defs/$name") }
    private val empty = WorkspaceResultSchema.obj(emptyMap())
    private val data = WorkspaceResultSchema.obj(mapOf("value" to WorkspaceResultSchema.number()))
    private val context = WorkspaceOperationContext(MutationAuthor.AGENT)

    private fun objectSchema(properties: JsonObject, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object"); put("properties", properties); put("additionalProperties", false)
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }

    @Test fun publicationAndExecutionUseTheExactSameFieldsAndConstraints() = runBlocking {
        val schema = objectSchema(buildJsonObject {
            put("radius", buildJsonObject { put("type", "number"); put("minimum", 1); put("maximum", 8); put("description", "Canvas pixels") })
        }, listOf("radius"))
        var calls = 0
        val registry = WorkspaceOperationRegistry()
        registry.register(WorkspaceOperationDefinition("paint_brush", "Paint one gesture", schema, WorkspaceOperationKind.DOCUMENT, resultSchema = schema)) { request, context ->
            calls++
            assertEquals(MutationAuthor.USER, context.author)
            WorkspaceOperationOutput(request)
        }
        val published = registry.definitions().single().toJson(includeSchema = true).getValue("request_schema").jsonObject
        assertEquals(schema, published)
        for (invalid in listOf(buildJsonObject {}, buildJsonObject { put("radius", 0) },
            buildJsonObject { put("radius", 3); put("raduis", 3) }, buildJsonObject { put("radius", "3") })) {
            assertFailsWith<WorkspaceValidationException> { registry.invoke("paint_brush", invalid, WorkspaceOperationContext(MutationAuthor.USER)) }
        }
        assertEquals(0, calls)
        val valid = buildJsonObject { put("radius", 3) }
        assertEquals(valid, registry.invoke("paint_brush", valid, WorkspaceOperationContext(MutationAuthor.USER)).data)
        assertEquals(1, calls)
    }

    @Test fun oneOfRequiresExactlyOneFullMatchAndRetainsUsefulDiscriminatorErrors() {
        val create = objectSchema(buildJsonObject {
            put("op", buildJsonObject { put("const", "create") })
            put("id", buildJsonObject { put("type", "string") })
        }, listOf("op", "id"))
        val remove = objectSchema(buildJsonObject {
            put("op", buildJsonObject { put("const", "remove") })
            put("target", buildJsonObject { put("type", "string") })
        }, listOf("op", "target"))
        val schema = buildJsonObject { put("oneOf", JsonArray(listOf(create, remove))) }
        validateOperationSchema(buildJsonObject { put("op", "create"); put("id", "new") }, schema)
        val missing = assertFailsWith<WorkspaceValidationException> { validateOperationSchema(buildJsonObject { put("op", "create") }, schema) }
        assertTrue(missing.message!!.contains("id"))
        val ambiguous = buildJsonObject { put("oneOf", JsonArray(listOf(create, create))) }
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(buildJsonObject { put("op", "create"); put("id", "new") }, ambiguous) }
    }

    @Test fun nullableFieldsAndArrayItemPathsHavePublishedSemantics() {
        val nullable = buildJsonObject { put("type", buildJsonArray { add("integer"); add("null") }); put("minimum", 0) }
        validateOperationSchema(JsonNull, nullable)
        validateOperationSchema(JsonPrimitive(3), nullable)
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonPrimitive(-1), nullable) }
        val array = buildJsonObject { put("type", "array"); put("items", nullable); put("minItems", 1) }
        val failure = assertFailsWith<WorkspaceValidationException> { validateOperationSchema(buildJsonArray { add(1); add("bad") }, array, "request.colors") }
        assertEquals("request.colors[1]", failure.fieldPath)
    }

    @Test fun exclusiveNumericBoundsAreEnforcedAsPublished() {
        val schema = buildJsonObject { put("type", "number"); put("exclusiveMinimum", 0); put("exclusiveMaximum", 1) }
        checkOperationSchema(schema)
        validateOperationSchema(JsonPrimitive(0.5), schema)
        for (invalid in listOf(0.0, 1.0, -0.1, 1.1)) {
            assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonPrimitive(invalid), schema) }
        }
    }

    @Test fun uniqueArrayItemsUseStructuralJsonEqualityAndReportTheDuplicateItemPath() {
        val schema = buildJsonObject { put("type", "array"); put("uniqueItems", true) }
        checkOperationSchema(schema)
        for (duplicate in listOf("[1,1.0]", "[{\"a\":1,\"b\":2},{\"b\":2.0,\"a\":1.0}]", "[[1,2],[1.0,2]]", "[null,null]", "[\"id\",\"id\"]")) {
            val failure = assertFailsWith<WorkspaceValidationException> { validateOperationSchema(Json.parseToJsonElement(duplicate), schema, "request.ids") }
            assertEquals("request.ids[1]", failure.fieldPath)
        }
        validateOperationSchema(Json.parseToJsonElement("[1,\"1\",true,null,[1,2],[2,1]]"), schema)
        validateOperationSchema(Json.parseToJsonElement("[1,1]"), JsonObject(schema + ("uniqueItems" to JsonPrimitive(false))))
        assertFailsWith<IllegalArgumentException> { checkOperationSchema(JsonObject(schema + ("uniqueItems" to JsonPrimitive("true")))) }
    }

    @Test fun unsupportedConstraintsAndDuplicateDefinitionsCannotBePublished() {
        val unsupported = objectSchema(buildJsonObject { put("field", buildJsonObject { put("type", "string"); put("format", "uri") }) })
        assertFailsWith<IllegalArgumentException> { WorkspaceOperationDefinition("asset_import", "Import", unsupported, WorkspaceOperationKind.DOCUMENT, resultSchema = objectSchema(buildJsonObject {})) }
        val schema = objectSchema(buildJsonObject {})
        val definition = WorkspaceOperationDefinition("project_inspect", "Read state", schema, WorkspaceOperationKind.QUERY, resultSchema = schema)
        val registry = WorkspaceOperationRegistry()
        registry.register(definition) { request, _ -> WorkspaceOperationOutput(request) }
        assertFailsWith<IllegalArgumentException> { registry.register(definition) { request, _ -> WorkspaceOperationOutput(request) } }
        registry.definitions()
        assertFailsWith<IllegalStateException> { registry.register(definition.copy(id = "project_list")) { request, _ -> WorkspaceOperationOutput(request) } }
    }

    @Test fun cancellationIsNeverConvertedToABusinessResult() = runBlocking {
        val registry = WorkspaceOperationRegistry()
        registry.register(WorkspaceOperationDefinition("view_render", "Render", objectSchema(buildJsonObject {}), WorkspaceOperationKind.QUERY, resultSchema = objectSchema(buildJsonObject {}))) { _, _ ->
            throw CancellationException("Cancelled")
        }
        assertFailsWith<CancellationException> { registry.invoke("view_render", buildJsonObject {}, WorkspaceOperationContext(MutationAuthor.AGENT)) }
        Unit
    }

    @Test fun invalidResultIsAnOutputFailureAndRetriesDoNotRepeatTheMutation() = runBlocking<Unit> {
        val host = object : WorkspaceStatePort {
            override fun snapshot() = WorkspaceProjectSnapshot("project", "revision", "head", true,
                null, 1, 1, false, "ready", null, emptyList(), emptyList(), state = "load:0:0")
        }
        WorkspaceRequestExecutor(host).use { executor ->
            val registry = WorkspaceOperationRegistry(executor)
            var calls = 0
            registry.register(WorkspaceOperationDefinition("session_probe", "Probe a result contract", empty,
                WorkspaceOperationKind.SESSION, resultSchema = data)) { _, _ ->
                calls++
                WorkspaceOperationOutput(buildJsonObject { put("value", "wrong") })
            }
            val request = buildJsonObject { put("request_id", "same"); put("project_id", "project"); put("state", "load:0:0") }
            val first = assertFailsWith<WorkspaceOutputContractFailure> { registry.invoke("session_probe", request, context) }
            val second = assertFailsWith<WorkspaceOutputContractFailure> { registry.invoke("session_probe", request, context) }
            assertSame(first, second)
            assertEquals(1, calls)
            val error = WorkspaceFailure.from(first).toJson()
            assertEquals("output_contract", error.getValue("code").jsonPrimitive.content)
            assertEquals("result.value", error.getValue("field").jsonPrimitive.content)
            assertEquals("session_probe", error.getValue("operation").jsonPrimitive.content)
            validateOperationSchema(error, WorkspaceResultSchema.failure)
        }
    }

    @Test fun publishedEnvelopeRequiresExactlyOneTypedOutcomeAndRejectsExtraFields() = runBlocking<Unit> {
        val definition = WorkspaceOperationDefinition("model_probe", "Read a typed result", empty, WorkspaceOperationKind.QUERY, resultSchema = data)
        val registry = WorkspaceOperationRegistry()
        registry.register(definition) { _, _ -> WorkspaceOperationOutput(buildJsonObject { put("value", 3) }) }
        val schema = definition.responseEnvelope()
        assertEquals(schema, definition.toJson(true).getValue("output_schema"))
        val success = buildJsonObject { put("ok", true); put("operation", definition.id); put("data", registry.invoke(definition.id, JsonObject(emptyMap()), context).data) }
        validateOperationSchema(success, schema)
        val failure = buildJsonObject { put("ok", false); put("operation", definition.id); put("error", WorkspaceFailure.from(IllegalStateException("Unavailable")).toJson()) }
        validateOperationSchema(failure, schema)
        for (bad in listOf(JsonObject(success + ("extra" to JsonPrimitive(true))),
            JsonObject(success + ("error" to failure.getValue("error"))), JsonObject(success - "data"),
            JsonObject(success + ("operation" to JsonPrimitive("other_probe"))),
            JsonObject(success + ("data" to buildJsonObject { put("value", 3); put("unpublished", 4) })))) {
            assertFailsWith<WorkspaceValidationException> { validateOperationSchema(bad, schema) }
        }
    }

    @Test fun backgroundDefinitionsAndCapabilityDetailsCannotOmitOrInventTerminalContracts() {
        assertFailsWith<IllegalArgumentException> {
            WorkspaceOperationDefinition("project_probe", "Probe a job", empty, WorkspaceOperationKind.PROJECT,
                jobBacked = true, resultSchema = empty)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceOperationDefinition("project_probe", "Probe a job", empty, WorkspaceOperationKind.PROJECT,
                resultSchema = empty, jobResultSchema = data)
        }
        val job = WorkspaceOperationDefinition("project_probe", "Probe a job", empty, WorkspaceOperationKind.PROJECT,
            jobBacked = true, resultSchema = empty, jobResultSchema = data).toJson(true)
        validateOperationSchema(job, WorkspaceCapabilityResultSchemas.detail)
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(job - "job_result_schema"), WorkspaceCapabilityResultSchemas.detail) }
        val immediate = WorkspaceOperationDefinition("model_probe", "Probe a query", empty, WorkspaceOperationKind.QUERY, resultSchema = data).toJson(true)
        validateOperationSchema(immediate, WorkspaceCapabilityResultSchemas.detail)
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(immediate + ("job_result_schema" to data)), WorkspaceCapabilityResultSchemas.detail) }
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(immediate - "output_schema"), WorkspaceCapabilityResultSchemas.detail) }
    }

    @Test fun auxiliaryContractsDescribeNullableAnnotationsAndBoundedSnapshotSummaries() {
        val identity = buildJsonObject { put("project_id", "project"); put("state", "load:0:0"); put("history_node_id", "head") }
        val annotation = JsonObject(identity + mapOf("node_id" to JsonPrimitive("head"), "annotation" to JsonNull))
        validateOperationSchema(annotation, WorkspaceAuxiliaryResultSchemas.forOperation("history_annotation_get"))
        val page = JsonObject(identity + buildJsonObject {
            put("total", 1); putJsonArray("items") { add(buildJsonObject { put("id", "pose"); put("number", 1); put("name", "Pose") }) }
        })
        validateOperationSchema(page, WorkspaceAuxiliaryResultSchemas.forOperation("snapshot_list"))
        assertFailsWith<WorkspaceValidationException> {
            validateOperationSchema(JsonObject(page + ("total" to JsonPrimitive(-1))), WorkspaceAuxiliaryResultSchemas.forOperation("snapshot_list"))
        }
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(annotation + ("annotation" to buildJsonObject { put("title", "Only one field") })),
            WorkspaceAuxiliaryResultSchemas.forOperation("history_annotation_get")) }
    }

    @Test fun capabilityDetailsValidateNestedSchemasAndTheirOwnPublishedEnvelope() {
        val request = s.obj(mapOf("nested" to s.obj(mapOf("value" to s.nullable(s.integer(0))))))
        val definition = WorkspaceOperationDefinition("workspace_get_operation", "Discover an exact contract", request,
            WorkspaceOperationKind.QUERY, resultSchema = WorkspaceCapabilityResultSchemas.detail)
        checkOperationSchema(definition.resultSchema)
        val detail = definition.toJson(true)
        validateOperationSchema(detail, definition.resultSchema)
        for (invalid in listOf(JsonObject(detail + ("unpublished" to JsonPrimitive(true))),
            JsonObject(detail + ("request_schema" to s.obj(mapOf("nested" to buildJsonObject { put("format", "uri") })))),
            JsonObject(detail + ("request_schema" to buildJsonObject { put("required", "one field") })))) {
            assertFailsWith<WorkspaceValidationException> { validateOperationSchema(invalid, definition.resultSchema) }
        }
    }

    @Test fun localRecursiveDefinitionsRetainTheirRootInRequestAndResponseEnvelopes() {
        val node = s.obj(mapOf("value" to s.integer(), "children" to s.array(ref("node"))))
        val schema = JsonObject(s.obj(mapOf("node" to ref("node"))) + ("\$defs" to buildJsonObject { put("node", node) }))
        val definition = WorkspaceOperationDefinition("model_read_tree", "Read a recursive tree", schema,
            WorkspaceOperationKind.QUERY, resultSchema = schema)
        val value = buildJsonObject { putJsonObject("node") {
            put("value", 1); putJsonArray("children") { add(buildJsonObject { put("value", 2); put("children", JsonArray(emptyList())) }) }
        } }
        validateOperationSchema(value, schema)
        validateOperationSchema(buildJsonObject { put("request", value) }, definition.requestEnvelope())
        validateOperationSchema(buildJsonObject { put("ok", true); put("operation", definition.id); put("data", value) }, definition.responseEnvelope())
        val invalid = buildJsonObject { putJsonObject("node") {
            put("value", 1); putJsonArray("children") { add(buildJsonObject { put("value", "wrong"); put("children", JsonArray(emptyList())) }) }
        } }
        val failure = assertFailsWith<WorkspaceValidationException> { validateOperationSchema(buildJsonObject { put("request", invalid) }, definition.requestEnvelope(), "arguments") }
        assertEquals("arguments.request.node.children[0].value", failure.fieldPath)
    }

    @Test fun missingExternalAndUnproductiveReferencesCannotBeRegistered() {
        for (reference in listOf("https://example.invalid/schema", "#/missing", "#/\$defs/missing")) {
            val schema = JsonObject(s.obj(mapOf("value" to buildJsonObject { put("\$ref", reference) })) + ("\$defs" to JsonObject(emptyMap())))
            assertFailsWith<IllegalArgumentException> { checkOperationSchema(schema) }
        }
        val cycle = JsonObject(s.obj(mapOf("value" to ref("loop"))) + ("\$defs" to buildJsonObject { put("loop", ref("loop")) }))
        assertFailsWith<IllegalArgumentException> { checkOperationSchema(cycle) }
    }
}
