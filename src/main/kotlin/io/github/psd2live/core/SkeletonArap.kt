package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 2D vertex-spokes ARAP, Sorkine & Alexa (2007), equations 3, 6 and 9.
 * https://igl.ethz.ch/projects/ARAP/arap_web.pdf
 * The joint band is free; rigid skin regions are Dirichlet handles. The topology and cotangent
 * Laplacian are reused for every bake sample. Local rotations use the closed-form 2D polar fit.
 *
 * The global SPD system (the free vertices' Laplacian plus the pose's guide penalty on its diagonal) is the
 * same for every local/global iteration of one solve and for both axes, so each solve factors it once, by an
 * envelope (skyline) Cholesky factorization in reverse Cuthill-McKee order (George & Liu, Computer Solution of
 * Large Sparse Positive Definite Systems, 1981, chapters 4 and 5), and every global step is two triangular
 * solves. The order and the envelope depend only on the mesh and the free set, so they are found once per
 * mesh. [factored] false solves with diagonally preconditioned conjugate gradients instead, the reference the
 * factorization is checked against.
 */
internal class SkeletonArap(private val rest: FloatArray, private val triangles: IntArray, movable: BooleanArray,
							private val factored: Boolean = true) {
	private class Edge(val a: Int, val b: Int, val weight: Double)
	private val count = rest.size / 2
	private val edges: List<Edge>
	private val free = movable.copyOf()
	private val diagonal = DoubleArray(count)
	// The edges as arrays, with each edge's rest vector, read by every iteration.
	private val edgeA: IntArray
	private val edgeB: IntArray
	private val edgeWeight: DoubleArray
	private val edgeX: DoubleArray
	private val edgeY: DoubleArray
	/** The order and envelope of the free system, and the weights of its off-diagonal entries in [Envelope]'s link order. */
	private val envelope: Envelope?
	private val linkWeights: DoubleArray

	init {
		val weights = HashMap<Long, Double>()
		fun add(a: Int, b: Int, opposite: Int) {
			val ux = (rest[a * 2] - rest[opposite * 2]).toDouble()
			val uy = (rest[a * 2 + 1] - rest[opposite * 2 + 1]).toDouble()
			val vx = (rest[b * 2] - rest[opposite * 2]).toDouble()
			val vy = (rest[b * 2 + 1] - rest[opposite * 2 + 1]).toDouble()
			val area = abs(ux * vy - uy * vx)
			val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
			weights[key] = (weights[key] ?: 0.0) + if (area > 1e-8) (ux * vx + uy * vy) / (2.0 * area) else 0.0
		}
		for (i in triangles.indices step 3) {
			val a = triangles[i]; val b = triangles[i + 1]; val c = triangles[i + 2]
			add(a, b, c); add(b, c, a); add(c, a, b)
		}
		// Non-Delaunay/degenerate input can have nonpositive edge weights. Positive weights keep
		// the local energy and global system well defined without changing the artwork's topology.
		edges = weights.map { (key, weight) -> Edge((key ushr 32).toInt(), key.toInt(), weight.coerceAtLeast(1e-6)) }
		val neighbors = Array(count) { ArrayList<Int>() }
		for (e in edges) {
			diagonal[e.a] += e.weight; diagonal[e.b] += e.weight
			neighbors[e.a] += e.b; neighbors[e.b] += e.a
		}
		val seen = BooleanArray(count)
		for (start in 0 until count) if (!seen[start]) {
			val component = ArrayList<Int>()
			component += start; seen[start] = true
			var i = 0
			while (i < component.size) for (next in neighbors[component[i++]]) if (!seen[next]) {
				seen[next] = true; component += next
			}
			// An island with no rigid region has no positional constraints. Keep its skinning result
			// rather than inventing an attachment or solving a singular translation mode.
			if (component.all { free[it] }) for (vertex in component) free[vertex] = false
		}
		edgeA = IntArray(edges.size) { edges[it].a }
		edgeB = IntArray(edges.size) { edges[it].b }
		edgeWeight = DoubleArray(edges.size) { edges[it].weight }
		edgeX = DoubleArray(edges.size) { (rest[edges[it].a * 2] - rest[edges[it].b * 2]).toDouble() }
		edgeY = DoubleArray(edges.size) { (rest[edges[it].a * 2 + 1] - rest[edges[it].b * 2 + 1]).toDouble() }
		val links = edges.filter { free[it.a] && free[it.b] }
		linkWeights = DoubleArray(links.size) { links[it].weight }
		envelope = if (factored && free.any { it }) Envelope(count, free, links.map { it.a to it.b }) else null
	}

	/**
	 * The free vertices in reverse Cuthill-McKee order and the envelope of the system's lower triangle in that
	 * order: row k holds columns first[k]..k, packed from offset[k].
	 */
	private class Envelope(count: Int, free: BooleanArray, links: List<Pair<Int, Int>>) {
		val order: IntArray
		private val first: IntArray
		private val offset: IntArray
		private val size: Int
		private val linkSlot: IntArray

		init {
			val neighbors = Array(count) { ArrayList<Int>() }
			for ((a, b) in links) { neighbors[a] += b; neighbors[b] += a }
			val degree = IntArray(count) { neighbors[it].size }
			for (list in neighbors) list.sortWith(compareBy<Int> { degree[it] }.thenBy { it })
			val visited = BooleanArray(count)
			val sequence = ArrayList<Int>()
			// Each component breadth first from a vertex of least degree, neighbours by increasing degree, reversed.
			val starts = (0 until count).filter { free[it] }.sortedWith(compareBy<Int> { degree[it] }.thenBy { it })
			for (start in starts) if (!visited[start]) {
				val component = ArrayList<Int>()
				component += start; visited[start] = true
				var i = 0
				while (i < component.size) for (next in neighbors[component[i++]]) if (!visited[next]) { visited[next] = true; component += next }
				sequence += component.asReversed()
			}
			order = sequence.toIntArray()
			val position = IntArray(count) { -1 }
			for ((k, vertex) in order.withIndex()) position[vertex] = k
			first = IntArray(order.size) { k -> minOf(k, neighbors[order[k]].minOfOrNull { position[it] } ?: k) }
			offset = IntArray(order.size)
			var total = 0
			for (k in order.indices) { offset[k] = total - first[k]; total += k - first[k] + 1 }
			size = total
			linkSlot = IntArray(links.size) {
				val a = position[links[it].first]; val b = position[links[it].second]
				offset[maxOf(a, b)] + minOf(a, b)
			}
		}

		/** The Cholesky factor of the system with [diagonal] (by vertex) and the links' -[weights], or null when it is not positive definite. */
		fun factor(diagonal: (Int) -> Double, weights: DoubleArray): DoubleArray? {
			val l = DoubleArray(size)
			for (k in order.indices) l[offset[k] + k] = diagonal(order[k])
			for (i in weights.indices) l[linkSlot[i]] -= weights[i]
			for (i in order.indices) {
				val rowI = offset[i]
				for (j in first[i] until i) {
					val rowJ = offset[j]
					var sum = l[rowI + j]
					for (k in maxOf(first[i], first[j]) until j) sum -= l[rowI + k] * l[rowJ + k]
					l[rowI + j] = sum / l[rowJ + j]
				}
				var pivot = l[rowI + i]
				for (k in first[i] until i) pivot -= l[rowI + k] * l[rowI + k]
				if (!(pivot > 1e-300)) return null
				l[rowI + i] = kotlin.math.sqrt(pivot)
			}
			return l
		}

		/** Solves L Lᵀ x = [rhs] (by vertex) into [out] (by vertex), writing only the free vertices. */
		fun solve(l: DoubleArray, rhs: DoubleArray, out: DoubleArray, scratch: DoubleArray) {
			for (i in order.indices) {
				val row = offset[i]
				var sum = rhs[order[i]]
				for (k in first[i] until i) sum -= l[row + k] * scratch[k]
				scratch[i] = sum / l[row + i]
			}
			for (i in order.indices.reversed()) {
				val row = offset[i]
				val value = scratch[i] / l[row + i]
				scratch[i] = value
				for (k in first[i] until i) scratch[k] -= l[row + k] * value
			}
			for (i in order.indices) out[order[i]] = scratch[i]
		}
	}

	fun solve(target: FloatArray, seed: FloatArray = target, guide: FloatArray = seed, guideWeights: DoubleArray = DoubleArray(count), folding: BooleanArray = BooleanArray(count)): FloatArray {
		if (free.none { it }) return target
		val penalty = DoubleArray(count) { if (free[it]) diagonal[it] * guideWeights[it] else 0.0 }
		val x = DoubleArray(count) { (if (free[it]) seed else target)[it * 2].toDouble() }
		val y = DoubleArray(count) { (if (free[it]) seed else target)[it * 2 + 1].toDouble() }
		val c = DoubleArray(count)
		val s = DoubleArray(count)
		val reflected = BooleanArray(count)
		val dot = DoubleArray(count)
		val cross = DoubleArray(count)
		val mirrorDot = DoubleArray(count)
		val mirrorCross = DoubleArray(count)
		val bx = DoubleArray(count)
		val by = DoubleArray(count)
		val nextX = DoubleArray(count)
		val nextY = DoubleArray(count)
		val factor = envelope?.factor({ diagonal[it] + penalty[it] }, linkWeights)
		val scratch = DoubleArray(envelope?.order?.size ?: 0)
		repeat(30) {
			dot.fill(0.0); cross.fill(0.0); mirrorDot.fill(0.0); mirrorCross.fill(0.0); bx.fill(0.0); by.fill(0.0)
			for (e in edgeA.indices) {
				val a = edgeA[e]; val b = edgeB[e]; val weight = edgeWeight[e]
				val rx = edgeX[e]; val ry = edgeY[e]
				val dx = x[a] - x[b]; val dy = y[a] - y[b]
				val d = weight * (rx * dx + ry * dy)
				val t = weight * (rx * dy - ry * dx)
				dot[a] += d; dot[b] += d; cross[a] += t; cross[b] += t
				val md = weight * (rx * dx - ry * dy)
				val mt = weight * (ry * dx + rx * dy)
				mirrorDot[a] += md; mirrorDot[b] += md; mirrorCross[a] += mt; mirrorCross[b] += mt
			}
			for (i in 0 until count) {
				val rotationLength = hypot(dot[i], cross[i])
				val reflectionLength = hypot(mirrorDot[i], mirrorCross[i])
				// Orthogonal Procrustes in O(2), restricted to intentional folding. Merely
				// disabling the area gate leaves the SO(2) fit fighting reflected material,
				// introducing opposing rotations and wrinkles around the contact line.
				reflected[i] = folding[i] && reflectionLength > rotationLength + 1e-10
				val length = if (reflected[i]) reflectionLength else rotationLength
				c[i] = if (length > 1e-12) (if (reflected[i]) mirrorDot[i] else dot[i]) / length else 1.0
				s[i] = if (length > 1e-12) (if (reflected[i]) mirrorCross[i] else cross[i]) / length else 0.0
			}
			for (e in edgeA.indices) {
				val a = edgeA[e]; val b = edgeB[e]; val weight = edgeWeight[e]
				val rx = edgeX[e]; val ry = edgeY[e]
				val sa = if (reflected[a]) -1 else 1; val sb = if (reflected[b]) -1 else 1
				val dx = weight * 0.5 * ((c[a] + c[b]) * rx - (sa * s[a] + sb * s[b]) * ry)
				val dy = weight * 0.5 * ((s[a] + s[b]) * rx + (sa * c[a] + sb * c[b]) * ry)
				bx[a] += dx; bx[b] -= dx; by[a] += dy; by[b] -= dy
				if (!free[b]) { bx[a] += weight * x[b]; by[a] += weight * y[b] }
				if (!free[a]) { bx[b] += weight * x[a]; by[b] += weight * y[a] }
			}
			for (i in 0 until count) {
				bx[i] += penalty[i] * guide[i * 2]; by[i] += penalty[i] * guide[i * 2 + 1]
			}
			x.copyInto(nextX); y.copyInto(nextY)
			if (factor != null) { envelope!!.solve(factor, bx, nextX, scratch); envelope.solve(factor, by, nextY, scratch) }
			else { global(nextX, bx, penalty); global(nextY, by, penalty) }
			val step = safeStep(x, y, nextX, nextY, folding)
			var change = 0.0
			for (i in 0 until count) {
				val dx = step * (nextX[i] - x[i]); val dy = step * (nextY[i] - y[i])
				x[i] += dx; y[i] += dy; change = maxOf(change, abs(dx), abs(dy))
			}
			if (change < 1e-4) return pack(x, y)
		}
		return pack(x, y)
	}

	private fun pack(x: DoubleArray, y: DoubleArray) = FloatArray(rest.size) { if (it % 2 == 0) x[it / 2].toFloat() else y[it / 2].toFloat() }

	/** libigl's flip-avoiding line-search principle: cap the step before the first area zero.
	 * https://libigl.github.io/dox/flip__avoiding__line__search_8h.html
	 * The fixed-rotation ARAP global energy is quadratic, so a capped descent to its minimizer
	 * also decreases that energy. Keep a small area margin for float keyform storage.
	 */
	private fun safeStep(x: DoubleArray, y: DoubleArray, nx: DoubleArray, ny: DoubleArray, folding: BooleanArray): Double {
		var step = 1.0
		for (i in triangles.indices step 3) {
			val a = triangles[i]; val b = triangles[i + 1]; val c = triangles[i + 2]
			// Include faces bridging the fold and its transition. Even one contact vertex
			// can cross an adjacent face during overlap; requiring two prevented closure
			// and capped the global solve for every vertex, pushing the joint open again.
			if (folding[a] || folding[b] || folding[c]) continue
			val ux = x[b] - x[a]; val uy = y[b] - y[a]; val vx = x[c] - x[a]; val vy = y[c] - y[a]
			val dux = nx[b] - nx[a] - ux; val duy = ny[b] - ny[a] - uy
			val dvx = nx[c] - nx[a] - vx; val dvy = ny[c] - ny[a] - vy
			val restArea = (rest[b * 2] - rest[a * 2]).toDouble() * (rest[c * 2 + 1] - rest[a * 2 + 1]) -
				(rest[b * 2 + 1] - rest[a * 2 + 1]).toDouble() * (rest[c * 2] - rest[a * 2])
			if (abs(restArea) < 1e-8) continue
			val sign = if (restArea > 0) 1.0 else -1.0
			val q = sign * (dux * dvy - duy * dvx)
			val l = sign * (ux * dvy + dux * vy - uy * dvx - duy * vx)
			val constant = sign * (ux * vy - uy * vx) - abs(restArea) * 0.01
			if (constant <= 0.0) continue // a pre-existing invalid seed is not repaired by line search
			fun cap(root: Double) { if (root > 0.0) step = minOf(step, root * 0.8) }
			if (abs(q) < 1e-12) { if (l < 0.0) cap(-constant / l) } else {
				val discriminant = l * l - 4.0 * q * constant
				if (discriminant >= 0.0) {
					val numerator = -0.5 * (l + Math.copySign(kotlin.math.sqrt(discriminant), l))
					cap(numerator / q)
					if (abs(numerator) > 1e-20) cap(constant / numerator)
				}
			}
		}
		return step.coerceIn(0.0, 1.0)
	}

	private fun multiply(input: DoubleArray, out: DoubleArray, penalty: DoubleArray) {
		for (i in 0 until count) out[i] = if (free[i]) (diagonal[i] + penalty[i]) * input[i] else 0.0
		for (e in edges) if (free[e.a] && free[e.b]) {
			out[e.a] -= e.weight * input[e.b]; out[e.b] -= e.weight * input[e.a]
		}
	}

	private fun global(position: DoubleArray, rhs: DoubleArray, penalty: DoubleArray) {
		val product = DoubleArray(count)
		multiply(position, product, penalty)
		val r = DoubleArray(count) { if (free[it]) rhs[it] - product[it] else 0.0 }
		val z = DoubleArray(count) { if (free[it]) r[it] / (diagonal[it] + penalty[it]) else 0.0 }
		val direction = z.copyOf()
		fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
		var rz = dot(r, z)
		for (iteration in 0 until minOf(count * 2, 200)) {
			if (rz < 1e-12) break
			multiply(direction, product, penalty)
			val denominator = dot(direction, product)
			if (denominator <= 1e-20) break
			val alpha = rz / denominator
			for (i in 0 until count) if (free[i]) {
				position[i] += alpha * direction[i]; r[i] -= alpha * product[i]; z[i] = r[i] / (diagonal[i] + penalty[i])
			}
			val next = dot(r, z)
			val beta = next / rz
			for (i in 0 until count) direction[i] = z[i] + beta * direction[i]
			rz = next
		}
	}
}
