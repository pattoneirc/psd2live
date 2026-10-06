package io.github.psd2live.targets.spine

import io.github.psd2live.format.model.*
import java.util.IdentityHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a deformer places its children at one pose, in canvas pixels (y down): a lattice of points or a
 * rotation frame. The rules are the editor's, as the runtime specification documents them (RUNTIME.md,
 * evaluation rules): a rotation's angle is its keyed angle plus the base angle; under a rotation, the
 * origin goes through the parent, angle and scale add up, the parent's vertical flip or negative scale add
 * a half turn and its horizontal flip none; under a warp, the frame stays rigid and turns with the lattice's
 * direction along v.
 */
internal sealed interface Frame {
	/** [scale] is that of the nearest rotation above, which rotations inside the lattice inherit. */
	class Warp(val points: FloatArray, val columns: Int, val rows: Int, val bilinear: Boolean, val scale: Float) : Frame
	class Rotation(val x: Float, val y: Float, val angle: Float, val scale: Float, val flipX: Boolean, val flipY: Boolean) : Frame

	fun apply(x: Float, y: Float): FloatArray = when (this) {
		is Warp -> Lattice(points, columns, rows, bilinear).map(x, y)
		is Rotation -> {
			val lx = (if (flipX) -x else x) * scale
			val ly = (if (flipY) -y else y) * scale
			val radians = Math.toRadians(angle.toDouble())
			val c = cos(radians).toFloat(); val s = sin(radians).toFloat()
			floatArrayOf(this.x + lx * c - ly * s, this.y + lx * s + ly * c)
		}
	}
}

/** Evaluates every deformer's frame at parameter values; the deformer geometry the bones are keyed from. */
internal class DeformerFrames(private val ir: RigIR) {
	private val parameters = ir.parameters.associateBy { it.id }
	private val byId = ir.deformers.associateBy { it.id }
	/** Parents before children. */
	val ordered: List<Deformer> = buildList {
		val seen = HashSet<String>()
		fun visit(d: Deformer, path: Set<String>) {
			if (d.id in seen || d.id in path) return
			d.parent?.let { byId[it] }?.let { visit(it, path + d.id) }
			if (seen.add(d.id)) add(d)
		}
		ir.deformers.forEach { visit(it, emptySet()) }
	}
	private val dense = IdentityHashMap<KeyGrid<*>, IntArray>()
	/** Only rotations and what they hang from are evaluated. */
	private val needed: Set<String> = buildSet {
		for (r in ir.deformers.filterIsInstance<Deformer.Rotation>()) {
			var current: String? = r.id
			while (current != null && add(current)) current = byId[current]?.parent
		}
	}

	/** Frames of every rotation and the deformers above it at [values] (missing parameters at their defaults). */
	fun at(values: Map<String, Float>): Map<String, Frame> {
		val v = HashMap<String, Float>()
		val d = HashMap<String, Float>()
		for (p in ir.parameters) { v[p.id] = normalize(p, values[p.id] ?: p.default); d[p.id] = normalize(p, p.default) }
		val frames = HashMap<String, Frame>()
		for (deformer in ordered) {
			if (deformer.id !in needed) continue
			val parent = deformer.parent?.let { frames[it] }
			frames[deformer.id] = when (deformer) {
				is Deformer.Warp -> warp(deformer, parent, v, d)
				is Deformer.Rotation -> rotation(deformer, parent, v, d)
			}
		}
		return frames
	}

	private fun normalize(p: Parameter, value: Float): Float {
		if (!value.isFinite()) return p.default
		if (p.repeat && p.max > p.min && (value < p.min || value > p.max)) return ((value - p.min).mod(p.max - p.min)) + p.min
		return value.coerceIn(minOf(p.min, p.max), maxOf(p.min, p.max))
	}

	private fun warp(w: Deformer.Warp, parent: Frame?, v: Map<String, Float>, d: Map<String, Float>): Frame.Warp {
		val size = (w.columns + 1) * (w.rows + 1) * 2
		val points = lattice(w.lattice, size, v)
		if (w.shapes.isNotEmpty()) {
			val base = lattice(w.lattice, size, d)
			for (binding in w.shapes) for ((shape, weight) in blend(binding, v)) {
				for (i in 0 until minOf(size, shape.points.size)) points[i] += (shape.points[i] - base[i]) * weight
			}
		}
		if (parent != null) for (i in 0 until size step 2) {
			val q = parent.apply(points[i], points[i + 1]); points[i] = q[0]; points[i + 1] = q[1]
		}
		val scale = when (parent) { is Frame.Warp -> parent.scale; is Frame.Rotation -> parent.scale; null -> 1f }
		return Frame.Warp(points, w.columns, w.rows, w.bilinear, scale)
	}

	private fun rotation(r: Deformer.Rotation, parent: Frame?, v: Map<String, Float>, d: Map<String, Float>): Frame.Rotation {
		val p = pivot(r.pivot, v)
		if (r.shapes.isNotEmpty()) {
			val b = pivot(r.pivot, d)
			for (binding in r.shapes) for ((shape, weight) in blend(binding, v)) {
				p[0] += (shape.x - b[0]) * weight; p[1] += (shape.y - b[1]) * weight
				p[2] += (shape.angle - b[2]) * weight; p[3] += (shape.scale - b[3]) * weight
			}
		}
		var flipX = r.flipX; var flipY = r.flipY
		r.channels[Channel.FLIP_X]?.let { grid -> flag(grid, v)?.let { flipX = it } }
		r.channels[Channel.FLIP_Y]?.let { grid -> flag(grid, v)?.let { flipY = it } }
		val angle = p[2] + r.baseAngle
		return when (parent) {
			null -> Frame.Rotation(p[0], p[1], angle, p[3], flipX, flipY)
			is Frame.Rotation -> {
				val origin = parent.apply(p[0], p[1])
				val half = (if (parent.flipY) 180f else 0f) + (if (parent.scale < 0f) 180f else 0f)
				Frame.Rotation(origin[0], origin[1], parent.angle + angle + half, parent.scale * p[3], flipX, flipY)
			}
			is Frame.Warp -> {
				val origin = parent.apply(p[0], p[1])
				val above = parent.apply(p[0], p[1] - 0.1f)
				val turn = Math.toDegrees(atan2((above[1] - origin[1]).toDouble(), (above[0] - origin[0]).toDouble())).toFloat() + 90f
				Frame.Rotation(origin[0], origin[1], angle + turn, p[3] * parent.scale, flipX, flipY)
			}
		}
	}

	private fun lattice(grid: KeyGrid<LatticePoints>?, size: Int, values: Map<String, Float>): FloatArray {
		val points = FloatArray(size)
		if (grid != null) for ((cell, weight) in weights(grid, values)) {
			val form = grid.cells[cell].form.points
			for (i in 0 until minOf(size, form.size)) points[i] += form[i] * weight
		}
		return points
	}

	/** x, y, angle, scale; without keyforms the identity at the origin. */
	private fun pivot(grid: KeyGrid<Pivot>?, values: Map<String, Float>): FloatArray {
		if (grid == null) return floatArrayOf(0f, 0f, 0f, 1f)
		val p = FloatArray(4)
		for ((cell, weight) in weights(grid, values)) {
			val form = grid.cells[cell].form
			p[0] += form.x * weight; p[1] += form.y * weight; p[2] += form.angle * weight; p[3] += form.scale * weight
		}
		return p
	}

	private class Span(val lo: Int, val hi: Int, val t: Float)

	private fun spanExact(keys: Floats, value: Float): Span {
		val last = keys.size - 1
		if (last <= 0 || value <= keys[0]) return Span(0, 0, 0f)
		if (value >= keys[last]) return Span(last, last, 0f)
		var j = 0
		while (j + 1 < last && keys[j + 1] <= value) j++
		val width = keys[j + 1] - keys[j]
		return Span(j, j + 1, if (width > 0f) (value - keys[j]) / width else 0f)
	}

	/** Keyform grids take a key when the value is within 0.001 of it. */
	private fun span(keys: Floats, value: Float): Span {
		for (i in 0 until keys.size) if (kotlin.math.abs(value - keys[i]) < KEY_SNAP) return spanExact(keys, keys[i])
		return spanExact(keys, value)
	}

	private fun value(parameter: String, values: Map<String, Float>) = values[parameter] ?: parameters[parameter]?.default ?: 0f

	/** Cell index per dense grid position, or -1 where the sparse grid has none. */
	private fun denseOf(grid: KeyGrid<*>): IntArray = dense.getOrPut(grid) {
		val sizes = grid.axes.map { it.keys.size }
		val out = IntArray(sizes.fold(1) { a, b -> a * b }) { -1 }
		grid.cells.forEachIndexed { index, cell ->
			var at = 0; var stride = 1
			for (i in grid.axes.indices) { at += cell.coordinate[i] * stride; stride *= sizes[i] }
			if (at in out.indices && out[at] < 0) out[at] = index
		}
		out
	}

	/** The cells contributing at [values] and their weights; a missing cell contributes a zero form. */
	private fun weights(grid: KeyGrid<*>, values: Map<String, Float>): List<Pair<Int, Float>> {
		if (grid.axes.isEmpty()) return if (grid.cells.isEmpty()) emptyList() else listOf(0 to 1f)
		val table = denseOf(grid)
		val spans = grid.axes.map { span(it.keys, value(it.parameter, values)) }
		val strides = IntArray(spans.size).also { var s = 1; for (i in it.indices) { it[i] = s; s *= grid.axes[i].keys.size } }
		val out = ArrayList<Pair<Int, Float>>()
		for (corner in 0 until (1 shl spans.size)) {
			var weight = 1f; var index = 0
			for ((i, s) in spans.withIndex()) {
				val upper = (corner shr i) and 1 == 1
				if (upper && s.hi == s.lo) { weight = 0f; break }
				weight *= if (upper) s.t else 1f - s.t
				index += (if (upper) s.hi else s.lo) * strides[i]
			}
			if (weight <= 0f) continue
			val cell = table[index]
			if (cell >= 0) out += cell to weight
		}
		return out
	}

	/** A flag holds the key at or below the value on every axis. */
	private fun flag(grid: KeyGrid<ChannelValue>, values: Map<String, Float>): Boolean? {
		if (grid.axes.isEmpty()) return (grid.cells.firstOrNull()?.form as? ChannelValue.Flag)?.value
		val table = denseOf(grid)
		var index = 0; var stride = 1
		for (axis in grid.axes) { index += span(axis.keys, value(axis.parameter, values)).lo * stride; stride *= axis.keys.size }
		val cell = table[index]
		return if (cell < 0) null else (grid.cells[cell].form as? ChannelValue.Flag)?.value
	}

	/** The shapes a binding mixes at [values] with their weights; the neutral key and empty keys add nothing. */
	private fun <S> blend(binding: BlendBinding<S>, values: Map<String, Float>): List<Pair<S, Float>> {
		val s = spanExact(binding.keys, value(binding.parameter, values))
		val limit = binding.limits.fold(1f) { acc, l -> acc * piecewise(l.points, value(l.parameter, values)) }
		if (limit == 0f) return emptyList()
		val out = ArrayList<Pair<S, Float>>()
		for ((i, kw) in listOf(s.lo to 1f - s.t, s.hi to s.t).withIndex()) {
			val (key, weight) = kw
			if (weight <= 0f || key == binding.neutralIndex || (i == 1 && s.hi == s.lo)) continue
			binding.shapes.getOrNull(key)?.let { out += it to weight * limit }
		}
		return out
	}

	private fun piecewise(points: List<BlendLimitPoint>, x: Float): Float {
		if (points.isEmpty()) return 1f
		if (points.size == 1 || x <= points[0].value) return points[0].weight
		for (i in 1 until points.size) if (x <= points[i].value) {
			val width = points[i].value - points[i - 1].value
			val t = if (width > 0f) (x - points[i - 1].value) / width else 1f
			return points[i - 1].weight + (points[i].weight - points[i - 1].weight) * t
		}
		return points.last().weight
	}

	private companion object {
		const val KEY_SNAP = 0.001f
	}
}

/**
 * A lattice mapping its normalized space to its points' space: bilinear cells or two triangles split along
 * (1, 0)–(0, 1) inside the unit square; outside, within [-2, 3]², a coarser virtual grid whose extra points
 * follow the lattice's mean affine frame, as triangles; beyond, the affine frame alone.
 */
internal class Lattice(private val points: FloatArray, private val columns: Int, private val rows: Int, private val bilinear: Boolean) {
	private fun point(c: Int, r: Int): FloatArray { val i = (r * (columns + 1) + c) * 2; return floatArrayOf(points[i], points[i + 1]) }

	private val c = FloatArray(2); private val ex = FloatArray(2); private val ey = FloatArray(2)
	init {
		val c00 = point(0, 0); val c10 = point(columns, 0); val c01 = point(0, rows); val c11 = point(columns, rows)
		for (k in 0..1) {
			c[k] = (c00[k] + c10[k] + c01[k] + c11[k]) / 4f
			ex[k] = ((c10[k] - c00[k]) + (c11[k] - c01[k])) / 2f
			ey[k] = ((c01[k] - c00[k]) + (c11[k] - c10[k])) / 2f
		}
	}

	private fun frame(u: Float, v: Float) = floatArrayOf(c[0] + (u - 0.5f) * ex[0] + (v - 0.5f) * ey[0], c[1] + (u - 0.5f) * ex[1] + (v - 0.5f) * ey[1])

	private fun triangle(q00: FloatArray, q10: FloatArray, q01: FloatArray, q11: FloatArray, s: Float, t: Float): FloatArray =
		if (s + t <= 1f) FloatArray(2) { q00[it] + s * (q10[it] - q00[it]) + t * (q01[it] - q00[it]) }
		else FloatArray(2) { q11[it] + (1f - s) * (q01[it] - q11[it]) + (1f - t) * (q10[it] - q11[it]) }

	private fun bilinear(q00: FloatArray, q10: FloatArray, q01: FloatArray, q11: FloatArray, s: Float, t: Float) =
		FloatArray(2) { (1f - s) * (1f - t) * q00[it] + s * (1f - t) * q10[it] + (1f - s) * t * q01[it] + s * t * q11[it] }

	fun map(u: Float, v: Float): FloatArray {
		if (u in 0f..1f && v in 0f..1f) {
			val fx = u * columns; val fy = v * rows
			val i = minOf(fx.toInt(), columns - 1); val j = minOf(fy.toInt(), rows - 1)
			val s = fx - i; val t = fy - j
			val q = arrayOf(point(i, j), point(i + 1, j), point(i, j + 1), point(i + 1, j + 1))
			return if (bilinear) bilinear(q[0], q[1], q[2], q[3], s, t) else triangle(q[0], q[1], q[2], q[3], s, t)
		}
		if (u !in -2f..3f || v !in -2f..3f) return frame(u, v)
		fun line(i: Int, n: Int): Float = when (i) { 0 -> -2f; n + 2 -> 3f; else -> (i - 1).toFloat() / n }
		fun cell(x: Float, n: Int): Int {
			if (x >= 3f) return n + 1
			var i = 0
			while (i + 1 < n + 2 && line(i + 1, n) <= x) i++
			return i
		}
		val ci = cell(u, columns); val ri = cell(v, rows)
		fun virtual(c: Int, r: Int): FloatArray =
			if (c in 1..columns + 1 && r in 1..rows + 1) point(c - 1, r - 1) else frame(line(c, columns), line(r, rows))
		val u0 = line(ci, columns); val u1 = line(ci + 1, columns)
		val v0 = line(ri, rows); val v1 = line(ri + 1, rows)
		return triangle(virtual(ci, ri), virtual(ci + 1, ri), virtual(ci, ri + 1), virtual(ci + 1, ri + 1), (u - u0) / (u1 - u0), (v - v0) / (v1 - v0))
	}
}
