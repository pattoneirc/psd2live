package io.github.psd2live.application

import io.github.psd2live.project.WorkspaceKeyformTargetRef
import kotlinx.serialization.json.*

/**
 * Intent operations: one request that states an authoring goal and compiles, against the captured model, to the member
 * edits an agent would otherwise assemble by hand. They run as one atomic document batch, so they add no journal
 * semantics and commit exactly as workspace_apply_edits does.
 */
internal object WorkspaceIntentOperations {
    const val AXIS = "author_axis"
    const val PHYSICS = "author_physics"
    const val OVERVIEW = "workspace_overview"
    val batches = setOf(AXIS, PHYSICS)
}

internal fun registerIntentOperations(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort,
                                      reads: WorkspaceReadPort, jobs: WorkspaceJobs) {
    val members = batchMemberSchemas(registry)

    fun registerBatch(id: String, description: String, schema: JsonObject,
                      compile: (JsonObject, WorkspaceQueries) -> Pair<String, List<WorkspaceDocumentOperation>>) {
        registry.register(WorkspaceOperationDefinition(id, description, schema, WorkspaceOperationKind.DOCUMENT, jobBacked = true,
            resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(id)),
            jobResultSchema = WorkspaceJobResultSchemas.result(id))) { request, context ->
            val state = request.getValue("state").jsonPrimitive.content
            val queries = reads.captureQueries()
            val captured = queries.snapshot().state
            // The compiled edits describe the captured model, so they must come from the version the request names.
            if (captured != state) throw WorkspaceConflict(state, captured)
            val (summary, edits) = compile(request, queries)
            edits.forEachIndexed { index, edit ->
                validateOperationSchema(edit.request, members.getValue(edit.operation), "compiled[$index].${edit.operation}")
            }
            startDocumentBatch(port, reads, jobs, id, state, request["summary"]?.jsonPrimitive?.content ?: summary, edits, context.author)
        }
    }

    val deformChange = members.getValue("rig_deform").getValue("properties").jsonObject.getValue("changes").jsonObject
        .getValue("items").jsonObject.getValue("properties").jsonObject
    val parameterFields = members.getValue("parameter_create").getValue("properties").jsonObject
    registerBatch(WorkspaceIntentOperations.AXIS,
        "Author a parameter's shapes in one history step. Creates parameter.id when the model lacks it (name defaults to the ID), " +
            "keys every shape target at the parameter default so its current form stays neutral, then deforms each target at its value " +
            "with rig_deform operations. Other axes bound directly to a target take their defaults unless shape.key names them. " +
            "Compiles to parameter_create, keyform_apply seed and rig_deform members of one atomic batch; returns its job.",
        intentSchema(mapOf(
            "parameter" to strict(mapOf(
                "id" to parameterFields.getValue("parameter_id"),
                "name" to parameterFields.getValue("name"), "min" to parameterFields.getValue("min"), "max" to parameterFields.getValue("max"),
                "default" to parameterFields.getValue("default"), "kind" to parameterFields.getValue("kind"), "repeat" to parameterFields.getValue("repeat"),
            ), listOf("id"), "The parameter to author. Range fields apply only when it is created."),
            "shapes" to buildJsonObject {
                put("type", "array"); put("minItems", 1); put("maxItems", 64)
                put("items", strict(mapOf(
                    "target" to text("mesh:<id> or warp:<id>"),
                    "value" to buildJsonObject { put("type", "number"); put("description", "Parameter value this shape belongs to; not the default.") },
                    "key" to buildJsonObject {
                        put("type", "object"); put("additionalProperties", buildJsonObject { put("type", "number") })
                        put("description", "Values of other axes bound to the target, for a combination key; omitted axes use their defaults.")
                    },
                    "operations" to deformChange.getValue("operations"), "selection" to deformChange.getValue("selection"),
                ), listOf("target", "value", "operations")))
            },
        ), listOf("parameter", "shapes")),
    ) { request, queries -> compileAxis(request, queries) }

    val physicsFields = members.getValue("physics_put").getValue("properties").jsonObject
    registerBatch(WorkspaceIntentOperations.PHYSICS,
        "Create or change a physics group and fit its outputs in one history step: physics_put with the given fields, then " +
            "physics_fit to fit_target percent of each output parameter's end under the standard head sway (default 100). " +
            "Set fit_target to null to keep the given output scales. The outputs need authored forms. Returns the batch job.",
        intentSchema(physicsFields + ("fit_target" to buildJsonObject {
            put("type", JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null")))); put("minimum", 10); put("maximum", 300)
            put("description", "Percent of each output parameter's end the standard sway reaches; null skips fitting.")
        }), listOf("id")),
    ) { request, _ ->
        val put = JsonObject(request - setOf("state", "summary", "fit_target", "request_id", "project_id"))
        val id = put.getValue("id").jsonPrimitive.content
        val fit = request["fit_target"].let { if (it == null) JsonPrimitive(100) else it as? JsonPrimitive }
        val fitting = fit?.takeIf { it !is JsonNull }
        // Only an ID refits the existing group as it is.
        require(put.keys != setOf("id") || fitting != null) { "Give the fields to change, or a fit_target to refit group $id" }
        "Physics $id" to listOfNotNull(
            WorkspaceDocumentOperation("physics_put", put).takeIf { put.keys != setOf("id") },
            fitting?.let { WorkspaceDocumentOperation("physics_fit", buildJsonObject { put("id", id); put("target", it) }) },
        )
    }

    registry.register(WorkspaceOperationDefinition(WorkspaceIntentOperations.OVERVIEW,
        "Summarize the loaded model from one capture: canvas, parameters, layers, the deformer hierarchy, physics groups and " +
            "motion clips, each list capped at limit (default 200) with its total. Use it to plan; inspect one object with workspace_inspect.",
        buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") { putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 500) } }
            put("required", JsonArray(emptyList()))
        }, WorkspaceOperationKind.QUERY, resultSchema = overviewSchema)) { request, _ ->
        WorkspaceOperationOutput(reads.captureQueries().overview(request["limit"]?.jsonPrimitive?.int ?: 200))
    }
}

private fun compileAxis(request: JsonObject, queries: WorkspaceQueries): Pair<String, List<WorkspaceDocumentOperation>> {
    val spec = request.getValue("parameter").jsonObject
    val parameterId = spec.getValue("id").jsonPrimitive.content
    val snapshot = queries.snapshot()
    val defaults = snapshot.parameters.associate { it.id to it.default }
    val existing = snapshot.parameters.firstOrNull { it.id == parameterId }
    val edits = mutableListOf<WorkspaceDocumentOperation>()
    val neutral = if (existing != null) {
        val conflicting = listOf("min" to existing.min, "max" to existing.max, "default" to existing.default)
            .filter { (field, value) -> spec[field]?.jsonPrimitive?.float?.let { it != value } == true }.map { it.first }
        require(conflicting.isEmpty()) { "Parameter $parameterId exists with other ${conflicting.joinToString("/")}; change it with parameter_update first" }
        existing.default
    } else {
        edits += WorkspaceDocumentOperation("parameter_create", buildJsonObject {
            put("parameter_id", parameterId); put("name", spec["name"] ?: JsonPrimitive(parameterId))
            listOf("min", "max", "default", "kind", "repeat").forEach { field -> spec[field]?.let { put(field, it) } }
        })
        spec["default"]?.jsonPrimitive?.float ?: 0f
    }

    val shapes = request.getValue("shapes").jsonArray.map { it.jsonObject }
    val axesByTarget = shapes.map { it.getValue("target").jsonPrimitive.content }.distinct().associateWith { target ->
        val kind = target.substringBefore(':', "")
        require(kind in setOf("mesh", "warp")) { "Shape target $target must be mesh:<id> or warp:<id>" }
        queries.getObject(WorkspaceKeyformTargetRef(kind, target.substringAfter(':'))).geometry?.axes.orEmpty()
    }
    fun key(target: String, explicit: JsonObject?, value: Float) = buildJsonObject {
        axesByTarget.getValue(target).filter { it.parameterId != parameterId }.forEach { axis ->
            put(axis.parameterId, explicit?.get(axis.parameterId) ?: JsonPrimitive(requireNotNull(defaults[axis.parameterId]) {
                "Axis ${axis.parameterId} of $target has no parameter" }))
        }
        explicit?.forEach { (axis, at) -> if (axis != parameterId) put(axis, at) }
        put(parameterId, value)
    }
    val seeds = shapes.map { it.getValue("target").jsonPrimitive.content to it["key"]?.jsonObject }.distinct()
        .filterNot { (target, explicit) ->
            // An axis already keyed at the default keeps that form as its neutral.
            explicit == null && axesByTarget.getValue(target).any { it.parameterId == parameterId && neutral in it.keys }
        }
        .map { (target, explicit) -> buildJsonObject { put("op", "seed"); put("target", target); put("key", key(target, explicit, neutral)) } }
    if (seeds.isNotEmpty()) edits += WorkspaceDocumentOperation("keyform_apply", buildJsonObject { put("changes", JsonArray(seeds)) })
    edits += WorkspaceDocumentOperation("rig_deform", buildJsonObject {
        putJsonArray("changes") {
            shapes.forEach { shape ->
                val target = shape.getValue("target").jsonPrimitive.content
                val value = shape.getValue("value").jsonPrimitive.float
                require(value != neutral) { "Shape of $target at $value is the parameter default; its neutral form is the current shape" }
                add(buildJsonObject {
                    put("target", target); put("key", key(target, shape["key"]?.jsonObject, value))
                    put("operations", shape.getValue("operations")); shape["selection"]?.let { put("selection", it) }
                })
            }
        }
    })
    return "Author $parameterId on ${axesByTarget.size} target(s)" to edits
}

private val overviewSchema: JsonObject by lazy {
    val s = WorkspaceResultSchema
    fun page(item: JsonObject) = s.obj(mapOf("total" to s.integer(0), "items" to s.array(item)))
    s.obj(linkedMapOf(
        "project_id" to s.nullable(s.handle()), "state" to s.handle(), "history_node_id" to s.handle(), "loaded" to s.boolean(),
        "canvas" to s.array(s.integer(0), 0, 2),
        "parameters" to page(s.obj(mapOf("id" to s.handle(), "name" to s.string(), "min" to s.number(), "max" to s.number(),
            "default" to s.number(), "kind" to s.string()))),
        "layers" to page(s.obj(mapOf("id" to s.handle(), "name" to s.string(), "role" to s.string(), "side" to s.string(), "visible" to s.boolean()))),
        "meshes" to s.integer(0),
        "deformers" to page(s.obj(mapOf("target" to s.handle(), "name" to s.string(), "parent" to s.nullable(s.handle()),
            "children" to s.integer(0)))),
        "physics" to page(s.obj(mapOf("id" to s.handle(), "name" to s.string(), "origin" to s.string(), "enabled" to s.boolean(),
            "inputs" to s.array(s.handle()), "outputs" to s.array(s.handle())))),
        "motions" to page(s.obj(mapOf("id" to s.handle(), "name" to s.string(), "duration" to s.number(), "loop" to s.boolean()))),
    ), setOf("project_id", "state", "loaded", "canvas", "parameters", "layers", "meshes", "deformers", "physics", "motions"))
}

private fun WorkspaceQueries.overview(limit: Int): JsonObject {
    val snapshot = snapshot()
    fun page(items: List<JsonObject>) = buildJsonObject { put("total", items.size); put("items", JsonArray(items.take(limit))) }
    val objects = if (snapshot.loaded) listRigObjectSummaries() else emptyList()
    fun ref(kind: String?, id: String?) = if (kind == null || id == null) null else "$kind:$id"
    val kinds = objects.associate { it.getValue("id").jsonPrimitive.content to it.getValue("kind").jsonPrimitive.content }
    val deformers = objects.filter { it.getValue("kind").jsonPrimitive.content in setOf("warp", "rotation") }
    return buildJsonObject {
        put("project_id", snapshot.projectId?.let(::JsonPrimitive) ?: JsonNull); put("state", snapshot.state)
        snapshot.historyHeadNodeId?.let { put("history_node_id", it) }
        put("loaded", snapshot.loaded)
        putJsonArray("canvas") { listOfNotNull(snapshot.canvasWidth, snapshot.canvasHeight).forEach { add(it) } }
        put("parameters", page(snapshot.parameters.map { p -> buildJsonObject {
            put("id", p.id); put("name", p.name); put("min", p.min); put("max", p.max); put("default", p.default); put("kind", p.kind)
        } }))
        put("layers", page(snapshot.layers.filterNot { it.deleted }.map { layer -> buildJsonObject {
            put("id", layer.id); put("name", layer.sourceName); put("role", layer.semanticTag); put("side", layer.side); put("visible", layer.visible)
        } }))
        put("meshes", objects.count { it.getValue("kind").jsonPrimitive.content == "mesh" })
        put("deformers", page(deformers.map { row ->
            val id = row.getValue("id").jsonPrimitive.content
            val parent = row["parentId"]?.jsonPrimitive?.contentOrNull
            buildJsonObject {
                put("target", "${row.getValue("kind").jsonPrimitive.content}:$id"); put("name", row.getValue("name"))
                put("parent", ref(parent?.let(kinds::get), parent)?.let(::JsonPrimitive) ?: JsonNull)
                put("children", objects.count { it["parentId"]?.jsonPrimitive?.contentOrNull == id && it.getValue("kind").jsonPrimitive.content != "part" })
            }
        }))
        put("physics", page(if (snapshot.loaded) listPhysics().map { group -> buildJsonObject {
            put("id", group.id); put("name", group.setting.name); put("origin", group.origin.name.lowercase()); put("enabled", group.enabled)
            putJsonArray("inputs") { group.setting.inputs.map { it.parameter }.distinct().forEach { add(it) } }
            putJsonArray("outputs") { group.setting.outputs.map { it.parameter }.distinct().forEach { add(it) } }
        } } else emptyList()))
        put("motions", page(if (snapshot.loaded) motionClips().map { clip -> buildJsonObject {
            put("id", clip.id); put("name", clip.name); put("duration", clip.duration); put("loop", clip.loop)
        } } else emptyList()))
    }
}

private fun text(description: String) = buildJsonObject { put("type", "string"); put("minLength", 1); put("description", description) }

private fun strict(fields: Map<String, JsonElement>, required: List<String>, description: String? = null) = buildJsonObject {
    put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
    put("required", JsonArray(required.map(::JsonPrimitive)))
    description?.let { put("description", it) }
}

private fun intentSchema(fields: Map<String, JsonElement>, required: List<String>) = strict(
    linkedMapOf<String, JsonElement>(
        "state" to buildJsonObject { put("type", "string"); put("minLength", 1) },
        "summary" to buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 512) },
    ) + fields, listOf("state") + required)
