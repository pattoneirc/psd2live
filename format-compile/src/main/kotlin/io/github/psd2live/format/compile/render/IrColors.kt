package io.github.psd2live.format.compile.render

import io.github.psd2live.format.model.*

/**
 * Multiply and screen colors of each mesh at a pose, and what each part's group composites with, evaluated
 * from the IR the way the editor evaluates channels: a keyed channel replaces the static value, interpolating
 * over its grid (values within 0.001 of a key take it, a missing cell of a sparse grid counts as zero), and
 * blend shapes move it toward their value by their weight from the value at the default pose.
 */
public class IrColors(private val ir: RigIR) {
	public class MeshColor(public val multiply: Rgb, public val screen: Rgb)

	/**
	 * A part at a pose: the draw order its group sorts by among its siblings (its draw-order channel and blend
	 * shapes over its static order), and the opacity (0..1), multiply and screen colors its isolated group
	 * composites with (the render group's channels over the group's composite; a part without an isolated
	 * group keeps its composite's statics).
	 */
	public class PartComposite(public val drawOrder: Float, public val opacity: Float, public val multiply: Rgb, public val screen: Rgb)

	private val parameters = ir.parameters.associateBy { it.id }

	/** The render group of each isolated part. */
	private val isolated: Map<String, RenderGroup> = HashMap<String, RenderGroup>().also { out ->
		fun walk(group: RenderGroup) {
			val part = group.part
			if (part != null && group.composite != null) out[part] = group
			for (child in group.children) if (child is RenderGroup) walk(child)
		}
		walk(ir.renderRoot)
	}

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

	/** Calls [cell] with each cell [grid] mixes at [values] and its weight. */
	private inline fun mixed(grid: KeyGrid<ChannelValue>, values: Map<String, Float>, cell: (ChannelValue, Float) -> Unit) {
		if (grid.axes.isEmpty()) {
			grid.cells.firstOrNull()?.let { cell(it.form, 1f) }
			return
		}
		val spans = grid.axes.map { span(it.keys, value(values, it.parameter), snap = true) }
		val cells = grid.cells.associateBy { c -> (0 until c.coordinate.size).map { c.coordinate[it] } }
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
			cells[coordinate]?.let { cell(it.form, weight) }
		}
	}

	private fun color(grid: KeyGrid<ChannelValue>, values: Map<String, Float>): Rgb {
		var r = 0f; var g = 0f; var b = 0f
		mixed(grid, values) { form, weight ->
			(form as? ChannelValue.Color)?.color?.let { r += it.red * weight; g += it.green * weight; b += it.blue * weight }
		}
		return Rgb(r, g, b)
	}

	private fun scalar(grid: KeyGrid<ChannelValue>, values: Map<String, Float>): Float {
		var v = 0f
		mixed(grid, values) { form, weight -> (form as? ChannelValue.Scalar)?.let { v += it.value * weight } }
		return v
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

	/** Calls [shape] with each shape [binding] mixes at [values] and its weight, the binding's limits applied. */
	private inline fun <S> shapes(binding: BlendBinding<S>, values: Map<String, Float>, shape: (S, Float) -> Unit) {
		// Blend shapes interpolate between their keys without snapping; the neutral key adds nothing.
		val (lo, hi, t) = span(binding.keys, value(values, binding.parameter), snap = false)
		val factor = binding.limits.fold(1f) { acc, l -> acc * limit(l, values) }
		for ((key, w) in listOf(lo to 1f - t, hi to t)) {
			if (w <= 0f || key == binding.neutralIndex) continue
			shape(binding.shapes[key] ?: continue, w * factor)
		}
	}

	/** Colors of every mesh at [values]; parameters left out sit at their defaults. */
	public fun at(values: Map<String, Float>): Map<String, MeshColor> = ir.meshes.associate { mesh ->
		var color = base(mesh, values)
		if (mesh.shapes.isNotEmpty()) {
			val rest = base(mesh, emptyMap())
			val m = floatArrayOf(color.multiply.red, color.multiply.green, color.multiply.blue)
			val s = floatArrayOf(color.screen.red, color.screen.green, color.screen.blue)
			for (binding in mesh.shapes) shapes(binding, values) { shape, weight ->
				m[0] += (shape.multiply.red - rest.multiply.red) * weight; m[1] += (shape.multiply.green - rest.multiply.green) * weight; m[2] += (shape.multiply.blue - rest.multiply.blue) * weight
				s[0] += (shape.screen.red - rest.screen.red) * weight; s[1] += (shape.screen.green - rest.screen.green) * weight; s[2] += (shape.screen.blue - rest.screen.blue) * weight
			}
			color = MeshColor(Rgb(m[0], m[1], m[2]), Rgb(s[0], s[1], s[2]))
		}
		mesh.id to color
	}

	private fun drawOrder(part: Part, values: Map<String, Float>): Float =
		part.channels[Channel.DRAW_ORDER]?.let { scalar(it, values) } ?: part.drawOrder.toFloat()

	/** Every part at [values] by its id; parameters left out sit at their defaults. */
	public fun parts(values: Map<String, Float>): Map<String, PartComposite> = ir.parts.associate { part ->
		var order = drawOrder(part, values)
		if (part.shapes.isNotEmpty()) {
			val rest = drawOrder(part, emptyMap())
			for (binding in part.shapes) shapes(binding, values) { shape, weight -> order += (shape.drawOrder - rest) * weight }
		}
		val group = isolated[part.id]
		val composite = group?.composite ?: part.composite
		val channels = group?.channels.orEmpty()
		part.id to PartComposite(order,
			(channels[Channel.OPACITY]?.let { scalar(it, values) } ?: composite.opacity).coerceIn(0f, 1f),
			channels[Channel.MULTIPLY_COLOR]?.let { color(it, values) } ?: composite.multiply,
			channels[Channel.SCREEN_COLOR]?.let { color(it, values) } ?: composite.screen)
	}
}
