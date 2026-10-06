package io.github.psd2live.format.compile.render

import io.github.psd2live.format.model.*

/**
 * Multiply and screen colors of each mesh at a pose, evaluated from the IR the way the editor evaluates
 * channels: a keyed channel replaces the static color, interpolating over its grid (values within 0.001 of
 * a key take it, a missing cell of a sparse grid counts as a zero color), and blend shapes move it toward
 * their color by their weight from the color at the default pose.
 */
public class IrColors(private val ir: RigIR) {
	public class MeshColor(public val multiply: Rgb, public val screen: Rgb)

	private val parameters = ir.parameters.associateBy { it.id }

	private fun value(values: Map<String, Float>, id: String): Float {
		val p = parameters[id] ?: return 0f
		val v = values[id]?.takeIf { it.isFinite() } ?: p.default
		if (p.repeat && p.max > p.min && (v < p.min || v > p.max)) return ((v - p.min).mod(p.max - p.min)) + p.min
		return v.coerceIn(minOf(p.min, p.max), maxOf(p.min, p.max))
	}

	/** The bracketing keys of [keys] at [v] and the weight of the upper one; [snap] takes keys within 0.001. */
	private fun span(keys: Floats, v0: Float, snap: Boolean): Triple<Int, Int, Float> {
		var v = v0
		if (snap) for (i in 0 until keys.size) if (kotlin.math.abs(v - keys[i]) < 0.001f) { v = keys[i]; break }
		val last = keys.size - 1
		if (last == 0 || v <= keys[0]) return Triple(0, 0, 0f)
		if (v >= keys[last]) return Triple(last, last, 0f)
		var j = 0
		while (j + 1 < last && keys[j + 1] <= v) j++
		val width = keys[j + 1] - keys[j]
		return Triple(j, j + 1, if (width > 0f) (v - keys[j]) / width else 0f)
	}

	private fun color(grid: KeyGrid<ChannelValue>, values: Map<String, Float>): Rgb {
		if (grid.axes.isEmpty()) return (grid.cells.firstOrNull()?.form as? ChannelValue.Color)?.color ?: Rgb.Black
		val spans = grid.axes.map { span(it.keys, value(values, it.parameter), snap = true) }
		val cells = grid.cells.associateBy { cell -> (0 until cell.coordinate.size).map { cell.coordinate[it] } }
		var r = 0f; var g = 0f; var b = 0f
		for (corner in 0 until (1 shl spans.size)) {
			var weight = 1f
			val coordinate = ArrayList<Int>(spans.size)
			for ((i, s) in spans.withIndex()) {
				val upper = corner shr i and 1 == 1
				if (upper && s.first == s.second) { weight = 0f; break }
				weight *= if (upper) s.third else 1f - s.third
				coordinate += if (upper) s.second else s.first
			}
			if (weight <= 0f) continue
			val c = (cells[coordinate]?.form as? ChannelValue.Color)?.color ?: continue
			r += c.red * weight; g += c.green * weight; b += c.blue * weight
		}
		return Rgb(r, g, b)
	}

	private fun base(mesh: Mesh, values: Map<String, Float>): MeshColor = MeshColor(
		mesh.channels[Channel.MULTIPLY_COLOR]?.let { color(it, values) } ?: mesh.multiply,
		mesh.channels[Channel.SCREEN_COLOR]?.let { color(it, values) } ?: mesh.screen)

	private fun limit(limit: BlendLimit, values: Map<String, Float>): Float {
		val x = value(values, limit.parameter)
		val points = limit.points
		if (points.isEmpty()) return 1f
		if (points.size == 1 || x <= points[0].value) return points[0].weight
		for (i in 1 until points.size) if (x <= points[i].value) {
			val (a, b) = points[i - 1] to points[i]
			val width = b.value - a.value
			return a.weight + (b.weight - a.weight) * (if (width > 0f) (x - a.value) / width else 1f)
		}
		return points.last().weight
	}

	/** Colors of every mesh at [values]; parameters left out sit at their defaults. */
	public fun at(values: Map<String, Float>): Map<String, MeshColor> = ir.meshes.associate { mesh ->
		var color = base(mesh, values)
		if (mesh.shapes.isNotEmpty()) {
			val rest = base(mesh, emptyMap())
			var m = floatArrayOf(color.multiply.red, color.multiply.green, color.multiply.blue)
			var s = floatArrayOf(color.screen.red, color.screen.green, color.screen.blue)
			for (binding in mesh.shapes) {
				// Blend shapes interpolate between their keys without snapping; the neutral key adds nothing.
				val (lo, hi, t) = span(binding.keys, value(values, binding.parameter), snap = false)
				val factor = binding.limits.fold(1f) { acc, l -> acc * limit(l, values) }
				for ((key, w) in listOf(lo to 1f - t, hi to t)) {
					if (w <= 0f || key == binding.neutralIndex) continue
					val shape = binding.shapes[key] ?: continue
					val weight = w * factor
					m = floatArrayOf(m[0] + (shape.multiply.red - rest.multiply.red) * weight, m[1] + (shape.multiply.green - rest.multiply.green) * weight, m[2] + (shape.multiply.blue - rest.multiply.blue) * weight)
					s = floatArrayOf(s[0] + (shape.screen.red - rest.screen.red) * weight, s[1] + (shape.screen.green - rest.screen.green) * weight, s[2] + (shape.screen.blue - rest.screen.blue) * weight)
				}
			}
			color = MeshColor(Rgb(m[0], m[1], m[2]), Rgb(s[0], s[1], s[2]))
		}
		mesh.id to color
	}
}
