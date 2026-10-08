package io.github.psd2live.core

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Journal encoding of `canvas_geometry` commands.
 *
 * Producers (canvas gestures, MCP strokes, tools) send the target's displayed points as an absolute `points`
 * array; that is version 1, and every journal written before version 2 holds it. [RigAuthoringJournal.compile]
 * rewrites each such command into version 2 before it reaches the journal: the change from the geometry the
 * target shows at the command's position (the [reference]), quantized to integer steps of `2^q` and kept only
 * for the vertices that moved. `n` keeps the point count so a size check needs no model.
 *
 * Replay resolves a version 2 command against the model at its journal position, which is the model compile
 * encoded it against, so the committed and replayed rigs are the same: compile applies the decoded command,
 * not the producer's absolute points. A version 1 command still replays its absolute points unchanged.
 */
internal object CanvasGeometryJournal {
    const val VERSION = 2

    /** Quantum relative to the largest coordinate: about four float ulps, far below a pixel in any space. */
    private const val RELATIVE_BITS = 20

    fun isEncoded(command: JsonObject) = command["v"]?.jsonPrimitive?.intOrNull == VERSION

    /** The point array's length (two floats per point) without resolving against a model. */
    fun size(command: JsonObject): Int? =
        if (isEncoded(command)) command["n"]?.jsonPrimitive?.intOrNull else command["points"]?.jsonArray?.size

    /** The pose the command's geometry is shown at; [CanvasEdits] reads it the same way. */
    private fun referencePose(command: JsonObject): Map<String, Float> {
        val key = command.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
        val pose = command["pose"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float } ?: key
        return if (pose.isEmpty()) key else pose
    }

    /** What the target shows before [command] applies: the geometry its deltas are relative to. */
    fun reference(model: org.umamo.runtime.model.PuppetModel, command: JsonObject): FloatArray =
        RigGeometryTools.geometry(model, command.getValue("kind").jsonPrimitive.content,
            command.getValue("id").jsonPrimitive.content, referencePose(command)).points

    /** The absolute points of [command] given the target's [reference] geometry. */
    fun points(command: JsonObject, reference: FloatArray): FloatArray {
        if (!isEncoded(command)) return command.getValue("points").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        val n = command.getValue("n").jsonPrimitive.int
        require(n == reference.size) { "Geometry edit no longer matches its target (${n / 2} points, target has ${reference.size / 2})" }
        val step = Math.scalb(1f, command.getValue("q").jsonPrimitive.int)
        val deltas = command.getValue("d").jsonArray.map { it.jsonPrimitive.long }
        val indices = command["i"]?.jsonArray?.map { it.jsonPrimitive.int }
        val result = reference.copyOf()
        if (indices == null) {
            require(deltas.size == n) { "Invalid geometry delta" }
            for (j in 0 until n) if (deltas[j] != 0L) result[j] = reference[j] + deltas[j] * step
        } else {
            require(deltas.size == indices.size * 2) { "Invalid geometry delta" }
            for ((k, vertex) in indices.withIndex()) {
                require(vertex in 0 until n / 2) { "Invalid geometry delta vertex" }
                val dx = deltas[k * 2]; val dy = deltas[k * 2 + 1]
                if (dx != 0L) result[vertex * 2] = reference[vertex * 2] + dx * step
                if (dy != 0L) result[vertex * 2 + 1] = reference[vertex * 2 + 1] + dy * step
            }
        }
        require(result.all(Float::isFinite)) { "Non-finite geometry delta" }
        return result
    }

    fun points(model: org.umamo.runtime.model.PuppetModel, command: JsonObject): FloatArray =
        points(command, if (isEncoded(command)) reference(model, command) else FloatArray(0))

    /**
     * [command] with its viewing pose reduced to what the target's geometry reads ([RigGeometryTools.referencePose]),
     * which shows the same geometry. An empty pose reads the key instead; a key it empties holds only defaults, the
     * same geometry again.
     */
    private fun withReferencePose(model: org.umamo.runtime.model.PuppetModel, command: JsonObject): JsonObject {
        val pose = command["pose"] as? JsonObject ?: return command
        val kind = command["kind"]?.jsonPrimitive?.contentOrNull ?: return command
        val id = command["id"]?.jsonPrimitive?.contentOrNull ?: return command
        val key = (command["key"] as? JsonObject)?.let { raw -> runCatching { raw.mapValues { it.value.jsonPrimitive.float } }.getOrNull() } ?: return command
        val values = runCatching { pose.mapValues { it.value.jsonPrimitive.float } }.getOrNull() ?: return command
        val reduced = RigGeometryTools.referencePose(model, kind, id, key, values)
        if (reduced.size == values.size) return command
        return JsonObject(command + ("pose" to JsonObject(reduced.mapValues { JsonPrimitive(it.value) })))
    }

    /**
     * [command] as version 2 against [model], or [command] itself when it is already encoded or cannot be
     * resolved (apply then reports the same error it always did).
     */
    fun encode(model: org.umamo.runtime.model.PuppetModel, shown: JsonObject): JsonObject {
        if (isEncoded(shown)) return shown
        val command = withReferencePose(model, shown)
        val raw = command["points"] as? JsonArray ?: return command
        val points = runCatching { raw.map { it.jsonPrimitive.float }.toFloatArray() }.getOrNull() ?: return command
        if (points.isEmpty() || points.size % 2 != 0 || !points.all(Float::isFinite)) return command
        val reference = runCatching { reference(model, command) }.getOrNull() ?: return command
        if (reference.size != points.size || !reference.all(Float::isFinite)) return command
        var magnitude = 0f
        for (i in points.indices) magnitude = max(magnitude, max(abs(points[i]), abs(reference[i])))
        val exponent = Math.getExponent(max(magnitude, 1e-30f)) - RELATIVE_BITS
        val step = Math.scalb(1.0, exponent)
        val quantized = LongArray(points.size) { ((points[it].toDouble() - reference[it].toDouble()) / step).roundToLong() }
        val moved = (0 until points.size / 2).filter { quantized[it * 2] != 0L || quantized[it * 2 + 1] != 0L }
        // Indices cost one number per moved vertex; listing every value costs one per unmoved vertex's pair.
        val sparse = moved.size * 3 < points.size
        return buildJsonObject {
            command.forEach { (name, value) -> if (name != "points") put(name, value) }
            put("v", VERSION); put("n", points.size); put("q", exponent)
            if (sparse) {
                put("i", JsonArray(moved.map(::JsonPrimitive)))
                put("d", JsonArray(moved.flatMap { listOf(JsonPrimitive(quantized[it * 2]), JsonPrimitive(quantized[it * 2 + 1])) }))
            } else put("d", JsonArray(quantized.map(::JsonPrimitive)))
        }
    }
}
