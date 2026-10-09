package io.github.psd2live.tools

import io.github.psd2live.core.MotionPresetSettings
import io.github.psd2live.core.MotionPresets
import io.github.psd2live.core.PreviewAnimationClock
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.SkeletonMotions
import io.github.psd2live.core.sim.SimMotions
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.PI
import kotlin.math.sin

/** Parameter values frame by frame at [SimBenchMotions.FPS]; parameters left out stay at their defaults. */
internal class BenchMotion(val name: String, val values: Map<String, FloatArray>, val frames: Int) {
	/** The pose at frame [f]. */
	fun pose(f: Int): Map<ParameterId, Float> = values.entries.associate { ParameterId(it.key) to it.value[f] }

	/** Per frame, whether any parameter moved since the frame before. */
	val moving: BooleanArray by lazy { BooleanArray(frames) { f -> f > 0 && values.values.any { kotlin.math.abs(it[f] - it[f - 1]) > 1e-6f } } }
}

/**
 * Motion the bake never trained on, the way a model is driven: a pointer dragged as face tracking follows it, the
 * head and body rolling, the built-in and the user's motions, each input stepped to its ends and held, and the
 * earlier benchmark's turns, drags and shaking. Each starts at rest and ends at rest, held while the body settles.
 */
internal object SimBenchMotions {
	const val FPS = 60
	private const val DT = 1f / FPS
	private const val TAIL = 1.5f

	fun all(model: PuppetModel, overlay: RigEditOverlay, inputs: List<String>): List<BenchMotion> {
		val ranges = model.parameters.associate { it.id.raw to it }
		val out = ArrayList<BenchMotion>()
		fun add(name: String, values: Map<String, FloatArray>) {
			val known = values.filterKeys { it in ranges }.mapValues { (id, track) ->
				val p = ranges.getValue(id); FloatArray(track.size) { track[it].coerceIn(p.min, p.max) }
			}
			if (known.isNotEmpty()) out += BenchMotion(name, known, known.values.first().size)
		}
		add("tracking", tracking())
		add("roll", roll(ranges.mapValues { it.value.max - it.value.min }))
		val clips = (listOf("Idle", "Nod", "Shake") + (if (overlay.skeleton != null) SkeletonMotions.presets.map { it.name } else emptyList())).mapNotNull { name ->
			runCatching { MotionPresets.clip(name, name, overlay.skeleton, overlay.motionPresets[name] ?: MotionPresetSettings()) }.getOrNull()
		} + overlay.motionClips.filter { it.enabled }
		for (clip in clips) add("clip:${clip.name}", SimMotions.sample(clip, FPS))
		for (input in inputs) ranges[input]?.let { p -> add("step:$input", mapOf(input to step(p.default, p.min, p.max))) }
		for (legacy in listOf("turns", "drags", "shake")) add(legacy, legacy(legacy))
		return out
	}

	private fun frames(seconds: Float) = (seconds * FPS).toInt()

	private fun ease(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

	/**
	 * A pointer moved about as a user drags the model's gaze: quick minimum-jerk moves to new spots with pauses,
	 * flicks, and a circle, followed as the editor's face tracking follows it (head fast, body behind), then let go.
	 */
	private fun tracking(): Map<String, FloatArray> {
		val random = java.util.Random(41L)
		val path = ArrayList<Pair<Float, Float>>()
		var x = 0f; var y = 0f
		fun move(tx: Float, ty: Float, seconds: Float) {
			val n = frames(seconds).coerceAtLeast(1)
			val fx = x; val fy = y
			for (f in 1..n) { val t = f.toFloat() / n; val s = t * t * t * (10f - 15f * t + 6f * t * t); path += (fx + (tx - fx) * s) to (fy + (ty - fy) * s) }
			x = tx; y = ty
		}
		fun hold(seconds: Float) = repeat(frames(seconds)) { path += x to y }
		var moves = 0
		while (path.size < frames(12f)) {
			val far = random.nextInt(3) == 0
			val tx = if (far) (if (random.nextBoolean()) 1f else -1f) else random.nextFloat() * 2f - 1f
			val ty = (random.nextFloat() * 2f - 1f) * 0.7f
			move(tx, ty, if (++moves % 5 == 0) 0.1f else 0.2f + random.nextFloat() * 0.4f)
			hold(0.2f + random.nextFloat() * 0.8f)
			if (moves == 6) {
				val n = frames(2f)
				for (f in 1..n) { val a = 2 * PI * f / n; path += (0.7f * kotlin.math.cos(a).toFloat()) to (0.7f * sin(a).toFloat()) }
				x = path.last().first; y = path.last().second
			}
		}
		var clock = PreviewAnimationClock()
		val total = path.size + frames(TAIL)
		val ax = FloatArray(total); val ay = FloatArray(total); val bx = FloatArray(total); val by = FloatArray(total)
		for (f in 0 until total) {
			clock = clock.advanceTracking(DT, path.getOrNull(f))
			ax[f] = clock.followX * 38f; ay[f] = -clock.followY * 24f
			bx[f] = (clock.bodyX * 8f).coerceIn(-10f, 10f); by[f] = (-clock.bodyY * 8f).coerceIn(-10f, 10f)
		}
		return mapOf("ParamAngleX" to ax, "ParamAngleY" to ay, "ParamBodyAngleX" to bx, "ParamBodyAngleY" to by)
	}

	/** The head and body rolling as face tracking reads them: a few sines from 0.2 to 2.5 Hz at half the span. */
	private fun roll(spans: Map<String, Float>): Map<String, FloatArray> {
		val random = java.util.Random(43L)
		val n = frames(10f); val total = n + frames(TAIL)
		return listOf("ParamAngleZ", "ParamBodyAngleZ").associateWith { id ->
			val waves = listOf(0.23, 0.41, 0.77, 1.3, 2.1, 2.5).map { hz -> Triple(hz, random.nextDouble() * 2 * PI, 1.0 / hz) }
			val norm = waves.sumOf { it.third }
			val half = (spans[id] ?: 20f) / 4f
			FloatArray(total) { f ->
				if (f >= n) 0f else {
					val t = f.toDouble() / FPS
					val fade = ease(f / 30f) * ease((n - 1 - f) / 30f)
					(waves.sumOf { (hz, phase, a) -> a * sin(2 * PI * hz * t + phase) } / norm * half * 2.0).toFloat() * fade
				}
			}
		}
	}

	/** To the top of the range, held, back, held, to the bottom, held, back, held. */
	private fun step(rest: Float, low: Float, high: Float): FloatArray {
		val track = ArrayList<Float>()
		var at = rest
		for (to in floatArrayOf(high, rest, low, rest)) {
			val ramp = frames(0.15f); val from = at
			for (f in 1..ramp) track += from + (to - from) * ease(f.toFloat() / ramp)
			repeat(frames(2f)) { track += to }
			at = to
		}
		return track.toFloatArray()
	}

	/** SimBakeBenchmark's turns and wobbles, mouse drags, and steady hard shaking, 10 s each. */
	private fun legacy(kind: String): Map<String, FloatArray> {
		val names = listOf("ParamAngleX", "ParamAngleZ", "ParamBodyAngleX", "ParamBodyAngleZ")
		val spans = listOf(30f, 30f, 10f, 10f)
		val frames = 600
		val fade = { f: Int -> ease(f / 30f) * ease((frames - 60 - f) / 30f) }
		val tracks = when (kind) {
			"turns" -> names.indices.map { i ->
				FloatArray(frames) { f ->
					val s = f / 60.0
					when (i) {
						0 -> 30f * (ease((f - 20) / 15f) - ease((f - 140) / 15f)) +
							if (f > 220) (12 * sin(2 * PI * 0.8 * s) + 8 * sin(2 * PI * 1.3 * s + 1)).toFloat() * fade(f - 220) else 0f
						1, 3 -> if (f > 220) (6 * sin(2 * PI * 0.6 * s + 2)).toFloat() * fade(f - 220) else 0f
						else -> 0f
					}
				}
			}
			"drags" -> {
				val random = java.util.Random(5L)
				names.indices.map { i ->
					val out = FloatArray(frames); var from = 0f; var to = 0f; var at = 0; var next = 10
					for (f in 0 until frames) {
						if (f == next && f < frames - 90) { from = out[f - 1]; to = (random.nextFloat() * 2 - 1) * spans[i]; at = f; next = f + 24 + random.nextInt(48) }
						if (f >= frames - 90 && next >= 0) { from = out[f - 1]; to = 0f; at = f; next = -1 }
						out[f] = from + (to - from) * ease((f - at) / 8f)
					}
					out
				}
			}
			else -> names.indices.map { i -> FloatArray(frames) { f -> if (i == 0) (spans[i] * sin(2 * PI * 1.2 * f / 60.0)).toFloat() * fade(f) else 0f } }
		}
		val tail = frames(TAIL)
		return names.indices.associate { names[it] to (tracks[it] + FloatArray(tail)) }
	}
}
