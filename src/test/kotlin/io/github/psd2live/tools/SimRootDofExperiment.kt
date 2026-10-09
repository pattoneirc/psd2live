package io.github.psd2live.tools

import io.github.psd2live.core.sim.PlacedCollider
import io.github.psd2live.core.sim.SimBaker
import io.github.psd2live.core.sim.SimScene
import io.github.psd2live.core.sim.SimVisualCheck
import io.github.psd2live.core.sim.SimVisualRun
import org.umamo.runtime.model.ParameterId
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Whether a simulated body answers only how the rig moves its root. For each part and input: how the input moves
 * the root (translation and turn of the pinned particles, by Procrustes) and how much it bends the body besides;
 * then the body stepped through the input's range for real against the same root motion applied rigidly, against
 * itself at a quarter and half the amplitude, and against other inputs that move the root the same way. Writes
 * build/tools/sim-root-dof/<sample>.md.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*SimRootDofExperiment'
 * PSD2LIVE_SAMPLE and PSD2LIVE_SIM_PARTS as for SimBakeSuite.
 */
class SimRootDofExperiment {
	/** Root motion at one input value: translation (px) and turn (rad) about the rest centroid, and how far the rest bends. */
	private class Root(val dx: Float, val dy: Float, val angle: Float, val anchorBend: Float, val goalBend: Float, val motion: Float)

	private class Kinematics(val input: String, val values: FloatArray, val roots: List<Root>, val rest: Float, val length: Float) {
		fun slope(channel: (Root) -> Float): Float {
			var num = 0.0; var den = 0.0
			for (k in values.indices) { val u = (values[k] - rest).toDouble(); num += u * channel(roots[k]); den += u * u }
			return if (den == 0.0) 0f else (num / den).toFloat()
		}
		/** The root motion at [u], linear between samples. */
		fun at(u: Float): Triple<Float, Float, Float> {
			val j = (0 until values.size - 1).firstOrNull { u <= values[it + 1] } ?: (values.size - 2)
			val t = ((u - values[j]) / (values[j + 1] - values[j])).coerceIn(0f, 1f)
			val a = roots[j]; val b = roots[j + 1]
			return Triple(a.dx + (b.dx - a.dx) * t, a.dy + (b.dy - a.dy) * t, a.angle + (b.angle - a.angle) * t)
		}
		val motion get() = roots.maxOf { it.motion }
		/** The root's largest travel over the range in px, the turn measured at the body's length. */
		fun reach(channel: (Root) -> Float) = roots.maxOf { abs(channel(it)) }
	}

	@Test fun run() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val wanted = setting("PSD2LIVE_SIM_PARTS", "").split(',').map(String::trim).filter(String::isNotEmpty).toSet()
		val parts = SimBenchParts.load(sample).filter { wanted.isEmpty() || it.name in wanted }
		val text = StringBuilder("# Root DOF experiment: ${sample.name}\n\n")
		text.append("Errors are sqrt(1 - R²) of one run against the other, body-frame offsets from the rig; static is the share off at the end of holds.\n\n")
		for (part in parts) {
			try { experiment(part, text) } catch (failure: Exception) { println("root-dof: ${part.name} failed: $failure"); text.append("## ${part.name}\n\nfailed: $failure\n\n") }
			File(output("sim-root-dof"), "${sample.name}.md").writeText(text.toString())
		}
		println("root-dof: wrote ${File(output("sim-root-dof"), "${sample.name}.md")}")
	}

	private fun experiment(p: SimBenchPart, text: StringBuilder) {
		val model = p.model; val edit = p.edit
		val calibrated = SimScene.build(model, edit).also { it.calibrate(model) }
		fun scene() = SimScene.build(model, edit).also { it.adopt(calibrated); it.reset(model, emptyMap()) }
		val rest = scene()
		val n = rest.state.count
		val restGoal = FloatArray(n * 2) { if (it % 2 == 0) rest.state.goalX[it / 2] else rest.state.goalY[it / 2] }
		val restAnchor = FloatArray(n * 2) { if (it % 2 == 0) rest.state.anchorX[it / 2] else rest.state.anchorY[it / 2] }
		val restColliders = rest.state.colliders
		val pinned = (0 until n).filter { rest.solver.pinWeight[it] >= 0.5f }
		val frame = if (pinned.size >= 2) pinned else (0 until n).toList()
		val cx0 = frame.sumOf { restAnchor[it * 2].toDouble() }.toFloat() / frame.size
		val cy0 = frame.sumOf { restAnchor[it * 2 + 1].toDouble() }.toFloat() / frame.size
		val length = (0 until n).maxOf { hypot(restGoal[it * 2] - cx0, restGoal[it * 2 + 1] - cy0) }
		fun rigid(x: Float, y: Float, dx: Float, dy: Float, angle: Float): Pair<Float, Float> {
			val c = cos(angle); val s = sin(angle); val rx = x - cx0; val ry = y - cy0
			return (cx0 + dx + c * rx - s * ry) to (cy0 + dy + s * rx + c * ry)
		}
		val parameters = model.parameters.associateBy { it.id.raw }

		// How each input moves the root, and bends the body besides.
		val kinematics = edit.inputs.mapNotNull { input ->
			val parameter = parameters[input.parameter] ?: return@mapNotNull null
			val (low, default, high) = SimBaker.trainingSpan(parameter, edit.inputRanges[input.parameter])
			val values = (List(4) { low + (default - low) * it / 4f } + default + List(4) { default + (high - default) * (it + 1) / 4f }).distinct().toFloatArray()
			val roots = values.map { u ->
				val s = scene().also { it.reset(model, mapOf(ParameterId(input.parameter) to u)) }.state
				var sx = 0f; var sy = 0f
				for (i in frame) { sx += s.anchorX[i]; sy += s.anchorY[i] }
				val cx = sx / frame.size; val cy = sy / frame.size
				var dot = 0.0; var cross = 0.0
				for (i in frame) {
					val ax = restAnchor[i * 2] - cx0; val ay = restAnchor[i * 2 + 1] - cy0; val bx = s.anchorX[i] - cx; val by = s.anchorY[i] - cy
					dot += ax * bx + ay * by; cross += ax * by - ay * bx
				}
				val angle = if (dot == 0.0 && cross == 0.0) 0f else atan2(cross, dot).toFloat()
				val dx = cx - cx0; val dy = cy - cy0
				val anchorBend = sqrt(frame.sumOf { i -> val (x, y) = rigid(restAnchor[i * 2], restAnchor[i * 2 + 1], dx, dy, angle); val e = hypot(s.anchorX[i] - x, s.anchorY[i] - y).toDouble(); e * e } / frame.size).toFloat()
				val bends = (0 until n).map { i -> val (x, y) = rigid(restGoal[i * 2], restGoal[i * 2 + 1], dx, dy, angle); hypot(s.goalX[i] - x, s.goalY[i] - y) }
				val motion = (0 until n).maxOf { i -> hypot(s.goalX[i] - restGoal[i * 2], s.goalY[i] - restGoal[i * 2 + 1]) }
				Root(dx, dy, angle, anchorBend, SimVisualCheck.percentile(bends, 0.95f), motion)
			}
			Kinematics(input.parameter, values, roots, default, length)
		}

		val dt = 1f / SimBenchMotions.FPS
		fun stepTrack(k: Kinematics, amplitude: Float): FloatArray {
			val low = k.rest + (k.values.first() - k.rest) * amplitude; val high = k.rest + (k.values.last() - k.rest) * amplitude
			val track = ArrayList<Float>(); var at = k.rest
			for (to in floatArrayOf(high, k.rest, low, k.rest)) {
				val ramp = 9; val from = at
				for (f in 1..ramp) { val t = f / ramp.toFloat(); track += from + (to - from) * t * t * (3f - 2f * t) }
				repeat(120) { track += to }
				at = to
			}
			return track.toFloatArray()
		}
		/** Body-frame offsets from the goals, per frame. */
		fun offsets(s: io.github.psd2live.core.sim.SimState) = FloatArray(n * 2).also { r ->
			val c = cos(-s.frameAngle); val sn = sin(-s.frameAngle)
			for (i in 0 until n) { val dx = s.x[i] - s.goalX[i]; val dy = s.y[i] - s.goalY[i]; r[i * 2] = c * dx - sn * dy; r[i * 2 + 1] = sn * dx + c * dy }
		}
		fun real(input: String, track: FloatArray): List<FloatArray> {
			val scene = scene()
			return track.map { u -> scene.drive(model, mapOf(ParameterId(input) to u), dt); offsets(scene.state) }
		}
		fun synthetic(k: Kinematics, track: FloatArray): List<FloatArray> {
			val scene = scene(); val s = scene.state
			return track.map { u ->
				val (dx, dy, angle) = k.at(u)
				for (i in 0 until n) {
					rigid(restAnchor[i * 2], restAnchor[i * 2 + 1], dx, dy, angle).let { (x, y) -> s.anchorX[i] = x; s.anchorY[i] = y }
					rigid(restGoal[i * 2], restGoal[i * 2 + 1], dx, dy, angle).let { (x, y) -> s.goalX[i] = x; s.goalY[i] = y }
				}
				s.colliders = restColliders.map { c ->
					val (ax, ay) = rigid(c.ax, c.ay, dx, dy, angle); val (bx, by) = rigid(c.bx, c.by, dx, dy, angle)
					PlacedCollider(ax, ay, bx, by, c.radiusA, c.radiusB, c.friction)
				}
				s.frameAngle = angle
				scene.solver.step(dt)
				offsets(s)
			}
		}
		fun compare(a: List<FloatArray>, b: List<FloatArray>, moving: BooleanArray, scale: Float = 1f): SimVisualCheck {
			val scaled = if (scale == 1f) b else b.map { row -> FloatArray(row.size) { row[it] * scale } }
			return SimVisualCheck.measure(SimVisualRun(a, scaled, a, scaled, List(a.size) { FloatArray(0) }, moving), emptyList(), FloatArray(0), SimBenchMotions.FPS)
		}
		fun error(m: SimVisualCheck) = sqrt(maxOf(0f, 1f - m.r2))
		fun moving(track: FloatArray) = BooleanArray(track.size) { it > 0 && track[it] != track[it - 1] }

		val relevant = kinematics.filter { it.motion >= 1f }
		class Row(val k: Kinematics, val synthetic: SimVisualCheck, val quarter: SimVisualCheck, val half: SimVisualCheck, val px: Float)
		val rows = relevant.parallelStream().map { k ->
			val full = stepTrack(k, 1f)
			val reference = real(k.input, full)
			val px = reference.maxOf { row -> (0 until n).maxOf { hypot(row[it * 2], row[it * 2 + 1]) } }
			Row(k, compare(reference, synthetic(k, full), moving(full)),
				compare(reference, real(k.input, stepTrack(k, 0.25f)), moving(full), 4f),
				compare(reference, real(k.input, stepTrack(k, 0.5f)), moving(full), 2f), px)
		}.toList()

		text.append("## ${p.name}\n\n")
		text.append("${frame.size} root particles (${if (pinned.size >= 2) "pinned" else "no pins: the whole goal field"}), body length %.0f px, material %s\n\n".format(length, edit.material.toJson()))
		text.append("| input | dx px | dy px | turn px | moves px | anchors bend px | goals bend p95 px | body px | rigid err | rigid static | rigid amp | rigid lag ms | rigid dir | 25% err | 50% err | verdict |\n")
		text.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
		for (k in kinematics) {
			val row = rows.firstOrNull { it.k === k }
			val head = "| ${k.input} | %.1f | %.1f | %.1f | %.1f | %.2f | %.1f |".format(k.reach { it.dx }, k.reach { it.dy }, k.reach { it.angle * length }, k.motion,
				k.roots.maxOf { it.anchorBend }, k.roots.maxOf { it.goalBend })
			if (row == null) { text.append("$head - | - | - | - | - | - | - | - | dropped (moves < 1 px) |\n"); continue }
			val s = row.synthetic
			val dof = error(s) <= 0.25f && (s.staticShare.isNaN() || s.staticShare <= 0.2f) && s.amplitude in 0.85f..1.15f && abs(s.lagMs) <= 1000f / 60f && s.direction >= 0.95f
			text.append("$head %.1f | %.2f | %.2f | %.2f | %.0f | %.3f | %.2f | %.2f | %s |\n".format(row.px, error(s), s.staticShare, s.amplitude, s.lagMs, s.direction,
				error(row.quarter), error(row.half), if (dof) "DOF" else "own"))
			println("root-dof: ${p.name} ${k.input}: rigid err %.2f static %.2f amp %.2f lag %.0f ms dir %.3f; 25%% err %.2f".format(error(s), s.staticShare, s.amplitude, s.lagMs, s.direction, error(row.quarter)))
		}

		// Inputs that move the root the same way, at the same root travel.
		val pairs = ArrayList<String>()
		for (i in relevant.indices) for (j in i + 1 until relevant.size) {
			val a = relevant[i]; val b = relevant[j]
			fun vector(k: Kinematics) = floatArrayOf(k.slope { it.dx } * (k.values.last() - k.rest), k.slope { it.dy } * (k.values.last() - k.rest), k.slope { it.angle } * length * (k.values.last() - k.rest))
			val va = vector(a); val vb = vector(b)
			val na = sqrt(va.sumOf { (it * it).toDouble() }).toFloat(); val nb = sqrt(vb.sumOf { (it * it).toDouble() }).toFloat()
			if (na < 1f || nb < 1f) continue
			val cosine = (va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2]) / (na * nb)
			if (abs(cosine) < 0.9f) continue
			// Scale each to the smaller root travel, the same sign.
			val travel = minOf(na, nb)
			val ta = stepTrack(a, 1f).map { a.rest + (it - a.rest) * travel / na }.toFloatArray()
			val tb = stepTrack(b, 1f).map { b.rest + (it - b.rest) * travel / nb * (if (cosine < 0) -1f else 1f) }.toFloatArray()
			val m = compare(real(a.input, ta), real(b.input, tb), moving(ta))
			pairs += "| ${a.input} | ${b.input} | %.3f | %.1f | %.2f | %.2f | %.2f | %.0f |".format(cosine, travel, error(m), m.staticShare, m.amplitude, m.lagMs)
		}
		if (pairs.isNotEmpty()) {
			text.append("\nSame root motion, different input (at equal root travel):\n\n| input A | input B | root cos | travel px | err | static | amp B/A | lag ms |\n|---|---|---|---|---|---|---|---|\n")
			pairs.forEach { text.append(it).append('\n') }
		}
		text.append("\n")
	}
}
