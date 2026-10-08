package io.github.psd2live.targets.runtime

import io.github.psd2live.format.model.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * What a `.p2lrt` 2 file offers the runtime's advanced mode, found in the compiled rig itself so every rig -
 * generated, skeleton-baked or imported - gets it from the same data the Cubism export bakes:
 *
 * - [skins]: meshes that bend across a joint below the rotation they hang from, keyed on the joint's parameters.
 *   The runtime fits their two-bone weights to those keyforms and corrects the chords between keys to arcs.
 * - [arcs]: rotations whose keyed pivot positions lie on one circle, as a folded skeleton link leaves them.
 */
internal object AdvancedData {
	class SkinData(val mesh: String, val axes: List<Pair<String, Floats>>, val bones: List<String>)
	class ArcData(val deformer: String, val parameter: String, val centerX: Float, val centerY: Float)

	private const val MAX_BONES = 16
	private const val MAX_AXES = 8

	fun skins(ir: RigIR): List<SkinData> {
		// The rig's deformers and the virtual bones hung among them.
		val all = ir.deformers + ir.advanced.virtualBones
		val rotations = all.filterIsInstance<Deformer.Rotation>().associateBy { it.id }
		val children = all.filter { it.parent != null }.groupBy { it.parent!! }
		// The parameters each rotation's pivot is keyed or blended on.
		fun keyedOn(r: Deformer.Rotation): Set<String> = r.pivot?.axes.orEmpty().map { it.parameter }.toSet() + r.shapes.map { it.parameter }
		return ir.meshes.mapNotNull { mesh ->
			val home = mesh.parent?.let(rotations::get) ?: return@mapNotNull null
			if (mesh.geometry == null) return@mapNotNull null
			val axes = LinkedHashMap<String, Floats>()
			mesh.offsets?.axes?.forEach { axes[it.parameter] = it.keys }
			mesh.shapes.forEach { axes.putIfAbsent(it.parameter, it.keys) }
			// Rotations below home, reached through rotations only, whose pivots move on the mesh's axes.
			val below = ArrayList<Deformer.Rotation>()
			fun walk(d: Deformer.Rotation) {
				for (child in children[d.id].orEmpty()) if (child is Deformer.Rotation) { below += child; walk(child) }
			}
			walk(home)
			val joints = below.filter { r -> keyedOn(r).any { it in axes } }
			if (joints.isEmpty()) return@mapNotNull null
			// Each joint with the rotations between it and home, home first.
			val bones = LinkedHashSet<String>()
			bones += home.id
			for (joint in joints) {
				val chain = generateSequence(joint) { r -> r.parent?.takeIf { it != home.id }?.let(rotations::get) }.toList().asReversed()
				chain.forEach { bones += it.id }
			}
			val used = axes.filterKeys { p -> bones.any { b -> p in keyedOn(rotations.getValue(b)) } }
			if (bones.size > MAX_BONES || used.isEmpty() || used.size > MAX_AXES) return@mapNotNull null
			SkinData(mesh.id, used.map { it.key to it.value }, bones.toList())
		}
	}

	fun arcs(ir: RigIR): List<ArcData> = ir.deformers.filterIsInstance<Deformer.Rotation>().mapNotNull { r ->
		val grid = r.pivot ?: return@mapNotNull null
		// Any three points lie on some circle: an arc takes four keys or more.
		if (grid.axes.size != 1 || grid.axes[0].keys.size < 4 || grid.cells.size != grid.axes[0].keys.size) return@mapNotNull null
		val points = grid.cells.sortedBy { it.coordinate[0] }.map { it.form.x.toDouble() to it.form.y.toDouble() }
		val center = circumcenter(points.first(), points[points.size / 2], points.last()) ?: return@mapNotNull null
		val radius = hypot(points[0].first - center.first, points[0].second - center.second)
		if (radius < 1.0 || radius > 1e5) return@mapNotNull null
		// Every key on the circle, each step turning the same way and by less than half a turn.
		if (points.any { abs(hypot(it.first - center.first, it.second - center.second) - radius) > 1e-3 * radius + 1e-3 }) return@mapNotNull null
		val turns = points.zipWithNext { a, b ->
			var t = atan2(b.second - center.second, b.first - center.first) - atan2(a.second - center.second, a.first - center.first)
			if (t > Math.PI) t -= 2 * Math.PI else if (t < -Math.PI) t += 2 * Math.PI
			t
		}
		if (turns.any { abs(it) < 1e-4 || abs(it) > Math.PI * 0.9 } || turns.any { it * turns[0] < 0 }) return@mapNotNull null
		ArcData(r.id, grid.axes[0].parameter, center.first.toFloat(), center.second.toFloat())
	}

	/** The center of the circle through three points, or null when they are (nearly) on a line. */
	private fun circumcenter(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): Pair<Double, Double>? {
		val d = 2 * (a.first * (b.second - c.second) + b.first * (c.second - a.second) + c.first * (a.second - b.second))
		val scale = maxOf(hypot(b.first - a.first, b.second - a.second), hypot(c.first - a.first, c.second - a.second))
		if (scale < 1e-3 || abs(d) < 1e-6 * scale * scale) return null
		val (a2, b2, c2) = Triple(a.first * a.first + a.second * a.second, b.first * b.first + b.second * b.second, c.first * c.first + c.second * c.second)
		return (a2 * (b.second - c.second) + b2 * (c.second - a.second) + c2 * (a.second - b.second)) / d to
			(a2 * (c.first - b.first) + b2 * (a.first - c.first) + c2 * (b.first - a.first)) / d
	}
}
