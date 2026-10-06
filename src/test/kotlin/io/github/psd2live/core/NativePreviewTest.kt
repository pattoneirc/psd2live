package io.github.psd2live.core

import io.github.psd2live.format.eval.P2lRuntime
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The software preview through the runtime gives the engine's geometry. Needs the runtime built; skipped otherwise. */
@Tag("slow")
class NativePreviewTest {
	@Test fun thePreviewMatchesTheEngineOnceTheRuntimeHasCompiledTheModel() {
		assumeTrue(P2lRuntime.load() != null, "The native runtime is not built")
		val plain = PSD2LivePipeline().buildPreview(File("examples/tml/psd-input/tml.psd").toPath())
		val preview = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
			rigEdits = plain.config.rigEdits.copy(skeleton = SkeletonAutoBuilder.build(plain.analysis, plain.rig))))
		// The first call answers through the engine and starts the compile.
		RigCanvasSupport.evaluate(preview)
		NativePreview.awaitIdle()
		val engine = CpuDeformationEvaluator()
		val random = Random(3)
		var nativeNanos = 0L; var engineNanos = 0L
		repeat(20) {
			val pose = preview.rig.puppet.parameters.associate { p -> p.id to p.min + random.nextFloat() * (p.max - p.min) }
			val t0 = System.nanoTime()
			val native = NativePreview.evaluate(preview, pose) ?: error("The runtime did not answer")
			val t1 = System.nanoTime()
			val want = engine.evaluate(preview.rig.puppet, pose)
			val t2 = System.nanoTime()
			nativeNanos += t1 - t0; engineNanos += t2 - t1
			assertEquals(want.worldPositions.keys, native.worldPositions.keys)
			for ((id, points) in want.worldPositions) {
				val got = native.worldPositions.getValue(id)
				val error = points.indices.maxOfOrNull { abs(points[it] - got[it]) } ?: 0f
				assertTrue(error < 0.02f, "${id.raw} differs by $error")
				assertTrue(abs((want.opacity[id] ?: 1f) - (native.opacity[id] ?: 1f)) < 1e-4f, "${id.raw} opacity")
			}
		}
		println("NativePreviewTest: runtime ${nativeNanos / 20_000} µs, engine ${engineNanos / 20_000} µs per evaluation")
	}
}
