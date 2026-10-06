package io.github.psd2live.format.compile

import io.github.psd2live.format.model.*
import kotlin.math.abs
import kotlin.math.max

/** A parameter, or a pair of linked parameters sampled together, with the keys each axis is sampled at. */
public class BakeAxis(public val x: Parameter, public val xKeys: List<Float>, public val y: Parameter?, public val yKeys: List<Float>) {
	public val name: String get() = if (y == null) x.id else "${x.id}|${y.id}"

	/** The pose of this axis at key indices ([xi], [yi]); every other parameter stays at its default. */
	public fun pose(xi: Int, yi: Int): Map<String, Float> = buildMap { put(x.id, xKeys[xi]); y?.let { put(it.id, yKeys[yi]) } }
}

/**
 * The lowering step for targets whose deformation adds up per parameter (Spine additive
 * tracks): samples the rig at every key of every parameter with the others at their defaults, and measures
 * what summing those samples misses at poses that combine parameters.
 */
public class ParameterBake(private val ir: RigIR, private val session: GeometrySession, linkedPairs: Boolean) {
	public val rest: PoseGeometry = session.evaluate(emptyMap())
	public val axes: List<BakeAxis> = axes(ir, linkedPairs)
	/** Per axis, the evaluated rig at each sampled key: [x index][y index]. */
	public val samples: Map<BakeAxis, List<List<PoseGeometry>>> = axes.associateWith { axis ->
		axis.xKeys.indices.map { xi -> axis.yKeys.indices.map { yi -> session.evaluate(axis.pose(xi, yi)) } }
	}

	/** Offsets of [mesh] from rest at a sample, in canvas pixels (y down). */
	public fun offsets(axis: BakeAxis, xi: Int, yi: Int, mesh: String): FloatArray {
		val rest = rest.positions.getValue(mesh)
		val posed = samples.getValue(axis)[xi][yi].positions[mesh] ?: rest
		return FloatArray(rest.size) { posed[it] - rest[it] }
	}

	/**
	 * The largest deviation, per mesh, between the rig and the sum of its per-axis samples, over [count] poses
	 * that combine two axes at sampled keys. Meshes within half a pixel are left out.
	 */
	public fun crossTermLosses(count: Int): List<LossEntry> {
		if (axes.size < 2 || count <= 0) return emptyList()
		var seed = 0x2545F491L
		fun next(bound: Int): Int { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed ushr 33) % bound).toInt() }
		val worst = HashMap<String, Float>()
		repeat(count) {
			val a = axes[next(axes.size)]
			val b = axes[next(axes.size)].let { if (it === a) axes[(axes.indexOf(a) + 1) % axes.size] else it }
			val picks = listOf(a, b).map { axis -> Triple(axis, next(axis.xKeys.size), next(axis.yKeys.size)) }
			val actual = session.evaluate(picks.fold(emptyMap()) { pose, (axis, xi, yi) -> pose + axis.pose(xi, yi) })
			for ((mesh, positions) in rest.positions) {
				val evaluated = actual.positions[mesh] ?: continue
				var error = 0f
				for (i in positions.indices) {
					var predicted = positions[i]
					for ((axis, xi, yi) in picks) predicted += (samples.getValue(axis)[xi][yi].positions[mesh]?.get(i) ?: positions[i]) - positions[i]
					error = max(error, abs(predicted - evaluated[i]))
				}
				worst[mesh] = max(worst[mesh] ?: 0f, error)
			}
		}
		return worst.filterValues { it > 0.5f }.toSortedMap().map { (mesh, error) ->
			LossEntry(mesh, Feature.PARAMETER_GRID, Handling.APPROXIMATED, error,
				"Parameters combining on this mesh are summed per parameter (max ${"%.1f".format(error)} px at sampled poses)")
		}
	}

	public companion object {
		/** Every parameter that keys something, with its keys plus its range ends and default; optionally linked pairs merged. */
		public fun axes(ir: RigIR, linkedPairs: Boolean): List<BakeAxis> {
			val keys = HashMap<String, MutableSet<Float>>()
			fun grid(grid: KeyGrid<*>?) { grid?.axes?.forEach { axis -> val set = keys.getOrPut(axis.parameter) { HashSet() }; for (i in 0 until axis.keys.size) set += axis.keys[i] } }
			fun channels(channels: Channels) = channels.values.forEach(::grid)
			fun shapes(bindings: List<BlendBinding<*>>) = bindings.forEach { b -> val set = keys.getOrPut(b.parameter) { HashSet() }; for (i in 0 until b.keys.size) set += b.keys[i] }
			for (mesh in ir.meshes) { grid(mesh.offsets); channels(mesh.channels); shapes(mesh.shapes) }
			for (deformer in ir.deformers) {
				channels(deformer.channels)
				when (deformer) {
					is Deformer.Warp -> { grid(deformer.lattice); shapes(deformer.shapes) }
					is Deformer.Rotation -> { grid(deformer.pivot); shapes(deformer.shapes) }
				}
			}
			for (part in ir.parts) { channels(part.channels); shapes(part.shapes) }
			for (glue in ir.glues) channels(glue.channels)
			val byId = ir.parameters.associateBy { it.id }
			fun keysOf(p: Parameter) = (keys[p.id].orEmpty() + listOf(p.min, p.default, p.max)).filter { it in p.min..p.max }.distinct().sorted()
			val used = ir.parameters.filter { it.id in keys && it.max > it.min }
			val result = ArrayList<BakeAxis>()
			val taken = HashSet<String>()
			if (linkedPairs) for (link in ir.parameterLinks) {
				val x = byId[link.horizontal] ?: continue; val y = byId[link.vertical] ?: continue
				if (x !in used || y !in used || x.id in taken || y.id in taken) continue
				taken += x.id; taken += y.id
				result += BakeAxis(x, keysOf(x), y, keysOf(y))
			}
			for (p in used) if (p.id !in taken) result += BakeAxis(p, keysOf(p), null, listOf(p.default))
			return result
		}
	}
}
