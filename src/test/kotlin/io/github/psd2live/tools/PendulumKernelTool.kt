package io.github.psd2live.tools

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsKernel
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import kotlin.math.sin
import kotlin.test.Test

/**
 * What a frame of a bake's pendulum costs through [PhysicsEngine] and through [PhysicsKernel]: a three-segment
 * pendulum with four inputs and two outputs played over 4,000 frames, as the fit plays every candidate.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*PendulumKernelTool'
 */
class PendulumKernelTool {
	@Test fun measure() {
		requireTools()
		val inputs = listOf("ParamAngleX", "ParamAngleZ", "ParamBodyAngleX", "ParamBodyAngleZ")
		val ranges = inputs.associateWith { PhysicsEngine.Range(-30f, 30f, 0f) } +
			listOf("__a", "__b").associateWith { PhysicsEngine.Range(-1e6f, 1e6f, 0f) }
		val setting = RigPhysicsEdit("P", "P", inputs.mapIndexed { i, p -> PhysicsInput(p, 40f + i * 10f, if (i % 2 == 0) PhysicsSourceType.X else PhysicsSourceType.ANGLE) },
			listOf(PhysicsOutput("__a", 2, 1f), PhysicsOutput("__b", 3, 1f)), List(3) { PhysicsSegment(10f, 0.9f, 0.9f, 1.2f) },
			PhysicsNormalization(angleMin = -30f, angleMax = 30f))
		val frames = 4000
		val track = inputs.indices.map { i -> FloatArray(frames) { f -> 30f * sin(f * (0.03f + i * 0.01f)) } }
		val dt = 1f / 60f
		var sink = 0f
		fun engine() {
			val e = PhysicsEngine(listOf(setting), ranges, 60f)
			val pose = HashMap<String, Float>()
			for (f in 0 until frames) {
				for (i in inputs.indices) pose[inputs[i]] = track[i][f]
				sink += e.step(pose, dt)["__b"] ?: 0f
			}
		}
		fun play(kernel: PhysicsKernel, into: FloatArray? = null) {
			val slots = inputs.map(kernel::indexOf)
			val values = kernel.defaults.copyOf()
			val out = FloatArray(kernel.outputs.size)
			kernel.reset()
			for (f in 0 until frames) {
				for (i in inputs.indices) values[slots[i]] = track[i][f]
				kernel.step(values, dt, out)
				sink += out[1]
				into?.set(f, out[1])
			}
		}
		val exact = PhysicsKernel(listOf(setting), ranges, 60f)
		val fast = PhysicsKernel(listOf(setting), ranges, 60f, exact = false)
		val e = mean(30, 10) { engine() } * 1e6 / frames
		val k = mean(30, 10) { play(exact) } * 1e6 / frames
		val q = mean(30, 10) { play(fast) } * 1e6 / frames
		println("pendulum-kernel: engine %.0f ns/frame, exact kernel %.0f ns/frame (%.1fx), fast kernel %.0f ns/frame (%.1fx) (sink %.1f)".format(e, k, e / k, q, e / q, sink))
		val a = FloatArray(frames); val b = FloatArray(frames)
		play(exact, a); play(fast, b)
		val peak = a.maxOf { kotlin.math.abs(it) }
		val worst = a.indices.maxOf { kotlin.math.abs(a[it] - b[it]) }
		println("pendulum-kernel: fast vs exact over $frames frames: worst %.3g rad of a %.3g rad swing (%.2g)".format(worst, peak, worst / peak))
	}
}
