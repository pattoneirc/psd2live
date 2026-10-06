package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ThreeWayMerge
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.abs

/**
 * A user's edit of generator output, kept across regeneration by a three-way merge.
 *
 * Swings and baked simulations own the keyforms their parameters add: a swing those of its axes on its target
 * Warps, a simulation those of its mode parameters on the meshes it baked. They run after the journal, so
 * replaying an ordinary edit there would happen before the parameter even exists. An override instead records
 * the generated form when the user edited it (`base`) beside the user's form (`points`), and replays after
 * every generator: each control point (Warp) or vertex offset (mesh) the user left where the generator had it
 * follows the new generation, each one the generator no longer changed keeps the user's value, and one both
 * moved keeps the user's value and is reported as a conflict.
 *
 * Which generator owns a keyform comes from [DocumentGenerators]' graph.
 */
internal object GeneratedOverrides {
	const val OP = "generated_override"

	fun isOverride(command: JsonObject) = command["op"]?.jsonPrimitive?.contentOrNull == OP

	/** The generator that owns the cell at [key] of [kind] [id]: one of its parameters is keyed away from its default. */
	private fun owner(graph: io.github.psd2live.format.compile.document.GeneratorGraph, model: PuppetModel, kind: String, id: String,
					  key: Map<String, Float>): String? = key.entries.firstNotNullOfOrNull { (parameterId, value) ->
		val parameter = model.parameters.firstOrNull { it.id.raw == parameterId } ?: return@firstNotNullOfOrNull null
		if (abs(value - parameter.default) <= org.umamo.runtime.eval.EPS_KEY) return@firstNotNullOfOrNull null
		DocumentGenerators.owner(graph, DocumentGenerators.keyform(kind, id, parameterId))?.id
	}

	/** The edited target's kind as the graph names it: `warp` or `mesh`; null for anything else. */
	private fun kindOf(command: JsonObject): String? = when (val raw = command["kind"]?.jsonPrimitive?.contentOrNull) {
		"warp" -> "warp"
		null -> null
		else -> runCatching { RigTargetKind.fromString(raw) }.getOrNull()?.takeIf { it == RigTargetKind.ART_MESH }?.let { "mesh" }
	}

	/**
	 * [commands] with each keyed geometry edit of a generated cell recorded as an override against what
	 * [model] (the generated rig) holds there now. Other commands pass through unchanged.
	 */
	fun capture(model: PuppetModel, overlay: RigEditOverlay, commands: JsonArray): JsonArray {
		val graph by lazy { DocumentGenerators.graph(overlay) }
		return JsonArray(commands.map { element ->
			val command = element.jsonObject
			if (command["op"]?.jsonPrimitive?.contentOrNull != "canvas_geometry" || command["preserve_children"]?.jsonPrimitive?.booleanOrNull == true)
				return@map command
			val kind = kindOf(command) ?: return@map command
			val id = command.getValue("id").jsonPrimitive.content
			val key = command.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
			if (key.isEmpty()) return@map command
			val generator = owner(graph, model, kind, id, key) ?: return@map command
			val (base, points) = when (kind) {
				"warp" -> {
					val cell = warpCell(model, id, key) ?: return@map command
					cell.form.controlPoints to command.getValue("points").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
				}
				else -> {
					val cell = meshCell(model, id, key) ?: return@map command
					// The mesh edit's points are where the vertices show; the offsets it keys are what the user made.
					val edited = runCatching { CanvasEdits.apply(model, command) }.getOrNull()?.let { meshCell(it, id, key) } ?: return@map command
					cell.form.positionDeltas to edited.form.positionDeltas
				}
			}
			buildJsonObject {
				put("op", OP); put("generator", generator); put("target", "$kind:$id")
				put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
				put("base", JsonArray(base.map(::JsonPrimitive)))
				put("points", JsonArray(points.map(::JsonPrimitive)))
			}
		})
	}

	/** The coordinate of the cell at exactly [key] in [grid] (every axis keyed, at one of its keys), or null. */
	private fun coordinate(grid: KeyformGrid<*>, key: Map<String, Float>): IntArray? {
		// The key names exactly the grid's axes: a generator that no longer adds its axis leaves no generated cell,
		// and the override must not fall through to the rest form under it.
		if (grid.axes.map { it.parameterId.raw }.toSet() != key.keys) return null
		return IntArray(grid.axes.size) { axis ->
			val value = key.getValue(grid.axes[axis].parameterId.raw)
			grid.axes[axis].keys.indexOfFirst { abs(it - value) < org.umamo.runtime.eval.EPS_KEY }.takeIf { it >= 0 } ?: return null
		}
	}

	private fun warp(model: PuppetModel, id: String) = model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Warp

	private fun warpCell(model: PuppetModel, id: String, key: Map<String, Float>): KeyformCell<WarpLatticeForm>? {
		val grid = warp(model, id)?.geometryGrid ?: return null
		val coordinate = coordinate(grid, key) ?: return null
		return grid.cells.firstOrNull { it.coordinate.contentEquals(coordinate) }
	}

	private fun meshCell(model: PuppetModel, id: String, key: Map<String, Float>): KeyformCell<MeshDeltaForm>? {
		val grid = model.drawables.firstOrNull { it.id.raw == id }?.geometryGrid ?: return null
		val coordinate = coordinate(grid, key) ?: return null
		return grid.cells.firstOrNull { it.coordinate.contentEquals(coordinate) }
	}

	class Outcome(val model: PuppetModel, val conflicts: List<String>)

	/**
	 * Applies [command] to a generated [model]. A model without the generated cell (before the generators ran,
	 * or after the generator was removed or reshaped) is left unchanged; the override then reports as orphaned.
	 */
	fun apply(model: PuppetModel, command: JsonObject): Outcome {
		val target = command.getValue("target").jsonPrimitive.content
		val kind = target.substringBefore(':')
		val id = target.substringAfter(':')
		val key = command.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
		val base = command.getValue("base").jsonArray.map { it.jsonPrimitive.float }
		val points = command.getValue("points").jsonArray.map { it.jsonPrimitive.float }
		require(kind == "warp" || kind == "mesh") { "Unknown override target: $target" }
		require(base.size == points.size && points.size % 2 == 0 && points.all(Float::isFinite) && base.all(Float::isFinite)) { "Invalid override form" }
		val current = (if (kind == "warp") warpCell(model, id, key)?.form?.controlPoints else meshCell(model, id, key)?.form?.positionDeltas)
			?: return Outcome(model, listOf("$id: override at $key has no generated keyform"))
		if (current.size != points.size) return Outcome(model, listOf("$id: override at $key no longer matches the ${if (kind == "warp") "lattice" else "mesh"}"))
		fun pairs(values: List<Float>) = (0 until values.size / 2).associateWith { values[it * 2] to values[it * 2 + 1] }
		val merged = ThreeWayMerge.merge(pairs(base), pairs(current.toList()), pairs(points)) { a, b ->
			a != null && b != null && abs(a.first - b.first) <= EPS && abs(a.second - b.second) <= EPS
		}
		val result = FloatArray(current.size)
		for ((index, point) in merged.merged) { result[index * 2] = point.first; result[index * 2 + 1] = point.second }
		val conflicts = merged.conflicts.map { "$id: ${if (kind == "warp") "control point" else "vertex"} $it at $key changed in both the generator and the override; the override is kept" }
		val next = if (kind == "warp") {
			val warp = warp(model, id)!!
			val grid = warp.geometryGrid!!
			val cell = warpCell(model, id, key)!!
			model.withReplacedGeometryGrid(KeyformOwner.Deformer(warp.id),
				KeyformGrid(grid.axes, grid.cells.map { if (it === cell) KeyformCell(it.coordinate, WarpLatticeForm(result)) else it }))
		} else {
			val drawable = model.drawables.first { it.id.raw == id }
			val grid = drawable.geometryGrid!!
			val cell = meshCell(model, id, key)!!
			model.withReplacedGeometryGrid(KeyformOwner.Drawable(drawable.id),
				KeyformGrid(grid.axes, grid.cells.map { if (it === cell) KeyformCell(it.coordinate, MeshDeltaForm(result)) else it }))
		}
		return Outcome(next, conflicts)
	}

	/** Every override in [journal], applied in order to the generated [model]. */
	fun applyAll(model: PuppetModel, journal: List<JsonObject>): Outcome {
		var current = model
		val conflicts = ArrayList<String>()
		for (command in journal) if (isOverride(command)) {
			val outcome = apply(current, command)
			current = outcome.model; conflicts += outcome.conflicts
		}
		return Outcome(current, conflicts)
	}

	private const val EPS = 1e-4f
}
