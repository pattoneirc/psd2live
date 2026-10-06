package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ThreeWayMerge
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.abs

/**
 * A user's edit of generator output, kept across regeneration by a three-way merge.
 *
 * A swing owns the keyforms its axes add to a Warp; replaying an ordinary lattice edit there would happen
 * before the swing regenerates and be overwritten. An override instead records the lattice the generator
 * produced when the user edited it (`base`) beside the user's lattice (`points`), and replays after every
 * generator: each control point the user left where the generator had it follows the new generation, each
 * point the generator no longer changed keeps the user's position, and a point both moved keeps the user's
 * position and is reported as a conflict.
 */
internal object GeneratedOverrides {
	const val OP = "generated_override"

	/** A swing parameter of a Warp, away from its default: a cell the swing generates. */
	private fun owner(swings: List<RigSwingEdit>, model: PuppetModel, warpId: String, key: Map<String, Float>): RigSwingEdit? =
		swings.firstOrNull { swing ->
			!swing.baked && warpId in swing.targets && swing.parameterIds.any { id ->
				val value = key[id] ?: return@any false
				val parameter = model.parameters.firstOrNull { it.id.raw == id } ?: return@any false
				abs(value - parameter.default) > org.umamo.runtime.eval.EPS_KEY
			}
		}

	/**
	 * [commands] with each lattice edit of a generated swing cell recorded as an override against what
	 * [model] (the generated rig) holds there now. Other commands pass through unchanged.
	 */
	fun capture(model: PuppetModel, swings: List<RigSwingEdit>, commands: JsonArray): JsonArray = JsonArray(commands.map { element ->
		val command = element.jsonObject
		if (command["op"]?.jsonPrimitive?.contentOrNull != "canvas_geometry" || command["kind"]?.jsonPrimitive?.contentOrNull != "warp" ||
			command["preserve_children"]?.jsonPrimitive?.booleanOrNull == true) return@map command
		val id = command.getValue("id").jsonPrimitive.content
		val key = command.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
		val swing = owner(swings, model, id, key) ?: return@map command
		val cell = cell(model, id, key) ?: return@map command
		buildJsonObject {
			put("op", OP); put("generator", "swing:${swing.id}"); put("target", "warp:$id")
			put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
			put("base", JsonArray(cell.form.controlPoints.map(::JsonPrimitive)))
			put("points", command.getValue("points"))
		}
	})

	private fun warp(model: PuppetModel, id: String) = model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Warp

	/** The grid cell at exactly [key] (every axis keyed, at one of its keys), or null when there is none. */
	private fun cell(model: PuppetModel, id: String, key: Map<String, Float>): KeyformCell<WarpLatticeForm>? {
		val grid = warp(model, id)?.geometryGrid ?: return null
		// The key names exactly the grid's axes: a swing that no longer adds its axis leaves no generated cell,
		// and the override must not fall through to the rest lattice under it.
		if (grid.axes.map { it.parameterId.raw }.toSet() != key.keys) return null
		val coordinate = IntArray(grid.axes.size) { axis ->
			val value = key.getValue(grid.axes[axis].parameterId.raw)
			grid.axes[axis].keys.indexOfFirst { abs(it - value) < org.umamo.runtime.eval.EPS_KEY }.takeIf { it >= 0 } ?: return null
		}
		return grid.cells.firstOrNull { it.coordinate.contentEquals(coordinate) }
	}

	class Outcome(val model: PuppetModel, val conflicts: List<String>)

	/**
	 * Applies [command] to a generated [model]. A model without the generated cell (before the generators ran,
	 * or after the swing was removed or reshaped) is left unchanged; the override then reports as orphaned.
	 */
	fun apply(model: PuppetModel, command: JsonObject): Outcome {
		val id = command.getValue("target").jsonPrimitive.content.removePrefix("warp:")
		val key = command.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
		val base = command.getValue("base").jsonArray.map { it.jsonPrimitive.float }
		val points = command.getValue("points").jsonArray.map { it.jsonPrimitive.float }
		require(base.size == points.size && points.size % 2 == 0 && points.all(Float::isFinite) && base.all(Float::isFinite)) { "Invalid override lattice" }
		val generated = cell(model, id, key) ?: return Outcome(model, listOf("$id: override at $key has no generated keyform"))
		val current = generated.form.controlPoints
		if (current.size != points.size) return Outcome(model, listOf("$id: override at $key no longer matches the lattice"))
		fun pairs(values: List<Float>) = (0 until values.size / 2).associateWith { values[it * 2] to values[it * 2 + 1] }
		val merged = ThreeWayMerge.merge(pairs(base), pairs(current.toList()), pairs(points)) { a, b ->
			a != null && b != null && abs(a.first - b.first) <= EPS && abs(a.second - b.second) <= EPS
		}
		val result = FloatArray(current.size)
		for ((index, point) in merged.merged) { result[index * 2] = point.first; result[index * 2 + 1] = point.second }
		val warp = warp(model, id)!!
		val grid = warp.geometryGrid!!
		val next = KeyformGrid(grid.axes, grid.cells.map { if (it === generated) KeyformCell(it.coordinate, WarpLatticeForm(result)) else it })
		return Outcome(model.withReplacedGeometryGrid(KeyformOwner.Deformer(warp.id), next),
			merged.conflicts.map { "$id: control point $it at $key changed in both the generator and the override; the override is kept" })
	}

	/** Every override in [journal], applied in order to the generated [model]. */
	fun applyAll(model: PuppetModel, journal: List<JsonObject>): Outcome {
		var current = model
		val conflicts = ArrayList<String>()
		for (command in journal) if (command["op"]?.jsonPrimitive?.contentOrNull == OP) {
			val outcome = apply(current, command)
			current = outcome.model; conflicts += outcome.conflicts
		}
		return Outcome(current, conflicts)
	}

	private const val EPS = 1e-4f
}
