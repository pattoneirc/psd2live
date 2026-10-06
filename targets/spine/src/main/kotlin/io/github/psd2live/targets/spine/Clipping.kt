package io.github.psd2live.targets.spine

import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.Mesh

/** A clipping polygon: mask mesh vertices in order, as (mesh, vertex) pairs; [hull] when approximated. */
internal class Clipping(val points: List<Pair<String, Int>>, val hull: Boolean) {
	/** Canvas positions of the points at [pose], taking meshes the pose lacks from [rest]. */
	fun positions(pose: PoseGeometry, rest: PoseGeometry = pose): FloatArray {
		val out = FloatArray(points.size * 2)
		points.forEachIndexed { i, (mesh, v) ->
			val p = pose.positions[mesh] ?: rest.positions.getValue(mesh)
			out[i * 2] = p[v * 2]; out[i * 2 + 1] = p[v * 2 + 1]
		}
		return out
	}

	companion object {
		/** Boundary loops of a triangle mesh (edges of one triangle, chained); empty where outlines touch. */
		fun loops(indices: Ints): List<List<Int>> {
			val count = HashMap<Pair<Int, Int>, Int>()
			val directed = ArrayList<Pair<Int, Int>>()
			for (t in 0 until indices.size / 3) for (k in 0 until 3) {
				val a = indices[t * 3 + k]; val b = indices[t * 3 + (k + 1) % 3]
				count.merge(if (a < b) a to b else b to a, 1, Int::plus)
				directed += a to b
			}
			val next = HashMap<Int, Int>()
			for ((a, b) in directed) if (count.getValue(if (a < b) a to b else b to a) == 1) {
				if (a in next) return emptyList()
				next[a] = b
			}
			val loops = ArrayList<List<Int>>()
			val seen = HashSet<Int>()
			for (start in next.keys.sorted()) {
				if (start in seen) continue
				val loop = ArrayList<Int>()
				var v = start
				while (v !in seen) { seen += v; loop += v; v = next[v] ?: return emptyList() }
				if (v != start) return emptyList()
				loops += loop
			}
			return loops
		}

		/** The outline of a single mask, or the convex hull of every mask's outline; null without geometry. */
		fun polygon(masks: List<Mesh>, rest: PoseGeometry): Clipping? {
			val shaped = masks.filter { it.geometry != null && rest.positions.containsKey(it.id) }
			if (shaped.isEmpty()) return null
			val outlines = shaped.associateWith { loops(it.geometry!!.indices) }
			val single = shaped.singleOrNull()
			if (single != null && outlines.getValue(single).size == 1) return Clipping(outlines.getValue(single)[0].map { single.id to it }, hull = false)
			val candidates = shaped.flatMap { mesh ->
				val loops = outlines.getValue(mesh)
				(if (loops.isEmpty()) (0 until mesh.geometry!!.positions.size / 2).toList() else loops.flatten()).map { mesh.id to it }
			}.distinct()
			fun x(p: Pair<String, Int>) = rest.positions.getValue(p.first)[p.second * 2]
			fun y(p: Pair<String, Int>) = rest.positions.getValue(p.first)[p.second * 2 + 1]
			val sorted = candidates.sortedWith(compareBy({ x(it) }, { y(it) }))
			if (sorted.size < 3) return null
			fun cross(o: Pair<String, Int>, a: Pair<String, Int>, b: Pair<String, Int>) =
				(x(a) - x(o)) * (y(b) - y(o)) - (y(a) - y(o)) * (x(b) - x(o))
			// Monotone chain: lower then upper hull.
			val hull = ArrayList<Pair<String, Int>>()
			for (points in listOf(sorted, sorted.asReversed())) {
				val start = hull.size
				for (p in points) {
					while (hull.size >= start + 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f) hull.removeAt(hull.size - 1)
					hull += p
				}
				hull.removeAt(hull.size - 1)
			}
			return if (hull.size >= 3) Clipping(hull, hull = true) else null
		}
	}
}
