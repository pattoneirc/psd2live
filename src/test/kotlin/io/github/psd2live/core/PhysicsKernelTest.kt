package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicsKernelTest {
	/** Inputs with uneven ranges and defaults off the midpoint, outputs with clamping and open ranges. */
	private val ranges = mapOf(
		"A" to PhysicsEngine.Range(-30f, 30f, 0f),
		"B" to PhysicsEngine.Range(-10f, 10f, 0f),
		"C" to PhysicsEngine.Range(0f, 1f, 0f),
		"D" to PhysicsEngine.Range(-5f, 20f, 3f),
		"E" to PhysicsEngine.Range(10f, -10f, 0f),
		"O1" to PhysicsEngine.Range(-1f, 1f, 0f),
		"O2" to PhysicsEngine.Range(-30f, 30f, 0f),
		"O3" to PhysicsEngine.Range(-1e6f, 1e6f, 0f),
		"O4" to PhysicsEngine.Range(-0.2f, 0.5f, 0.1f),
	)
	private val inputs = listOf("A", "B", "C", "D", "E", "Missing")
	private val outputs = listOf("O1", "O2", "O3", "O4", "Gone")

	private fun setting(random: java.util.Random, id: Int, readable: List<String>): RigPhysicsEdit {
		val segments = List(1 + random.nextInt(4)) {
			PhysicsSegment(0.5f + random.nextFloat() * 30f, random.nextFloat(), 0.05f + random.nextFloat() * 2f, random.nextFloat() * 4f)
		}
		val ins = readable.shuffled(random).take(1 + random.nextInt(minOf(5, readable.size))).map {
			PhysicsInput(it, random.nextFloat() * 100f, if (random.nextBoolean()) PhysicsSourceType.X else PhysicsSourceType.ANGLE, random.nextBoolean())
		}
		val outs = outputs.shuffled(random).take(1 + random.nextInt(3)).map {
			PhysicsOutput(it, 1 + random.nextInt(segments.size + 1), random.nextFloat() * 3f - 1f, if (random.nextInt(4) == 0) random.nextFloat() * 100f else 100f,
				if (random.nextInt(5) == 0) PhysicsSourceType.X else PhysicsSourceType.ANGLE, random.nextBoolean())
		}.filter { it.vertex <= segments.size }.ifEmpty { listOf(PhysicsOutput("O1", 1, 1f)) }
		val normalization = PhysicsNormalization(-random.nextFloat() * 20f - 1f, 0f, random.nextFloat() * 20f + 1f, -random.nextFloat() * 40f - 1f, 0f, random.nextFloat() * 40f + 1f)
		return RigPhysicsEdit("S$id", "S$id", ins, outs, segments, normalization)
	}

	@Test
	fun playsBitForBitLikeTheEngine() {
		val random = java.util.Random(7L)
		repeat(300) { config ->
			// Later settings may read what earlier ones write.
			val first = setting(random, 0, inputs)
			val settings = List(1 + random.nextInt(3)) { if (it == 0) first else setting(random, it, inputs + first.outputParameters) }
			val fps = listOf(60f, 30f, 24f, null)[random.nextInt(4)]
			val engine = PhysicsEngine(settings, ranges, fps)
			val kernel = PhysicsKernel(settings, ranges, fps)
			val values = kernel.defaults.copyOf()
			val out = FloatArray(kernel.outputs.size)
			val current = HashMap<String, Float>()
			repeat(240) { frame ->
				if (random.nextInt(80) == 0) { engine.reset(); kernel.reset() }
				// A random walk, past the ranges at times; some parameters left out of the engine's map.
				for (p in kernel.parameters) {
					val range = ranges.getValue(p)
					val span = maxOf(range.min, range.max) - minOf(range.min, range.max)
					val next = (current[p] ?: range.default) + (random.nextFloat() - 0.5f) * span * 0.3f
					current[p] = next.coerceIn(minOf(range.min, range.max) - span * 0.2f, maxOf(range.min, range.max) + span * 0.2f)
				}
				val given = current.filter { random.nextInt(10) != 0 }
				for ((i, p) in kernel.parameters.withIndex()) values[i] = given[p] ?: kernel.defaults[i]
				val dt = when (random.nextInt(40)) { 0 -> 0f; 1 -> 6f; 2 -> 0.2f; else -> 1f / 60f + (random.nextFloat() - 0.5f) * 0.004f }
				val expected = engine.step(given, dt)
				val played = kernel.step(values, dt, out)
				assertEquals(dt > 0f, played, "config $config frame $frame")
				if (!played) return@repeat
				assertEquals(expected.keys, kernel.outputs.toSet(), "config $config frame $frame")
				for ((k, p) in kernel.outputs.withIndex()) {
					val e = expected.getValue(p)
					assertTrue(e.toRawBits() == out[k].toRawBits(), "config $config frame $frame $p: engine $e kernel ${out[k]}")
				}
			}
		}
	}
}
