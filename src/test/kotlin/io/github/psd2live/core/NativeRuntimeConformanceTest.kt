package io.github.psd2live.core

import io.github.psd2live.format.eval.NativeGeometryEvaluator
import io.github.psd2live.format.eval.P2lRuntime
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Rust runtime deforms the samples as the editor does, plain and with a skeleton, at random poses.
 * Needs the runtime built with `cargo build --release` in runtime/; skipped otherwise.
 */
@Tag("slow")
class NativeRuntimeConformanceTest {
	@Test fun theRuntimeMatchesTheEditorOnTheSample() {
		val runtime = P2lRuntime.load()
		assumeTrue(runtime != null, "The native runtime is not built")
		val plain = PSD2LivePipeline().buildPreview(File("examples/tml/psd-input/tml.psd").toPath())
		val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
			rigEdits = plain.config.rigEdits.copy(skeleton = SkeletonAutoBuilder.build(plain.analysis, plain.rig))))
		val random = Random(7)
		for (preview in listOf(plain, skeletal)) {
			val ir = RigIrCompiler.compile(preview)
			IrGeometryEvaluator.open(ir).use { editor ->
				NativeGeometryEvaluator(runtime!!).open(ir).use { native ->
					repeat(12) {
						val pose = ir.parameters.associate { p -> p.id to p.min + random.nextFloat() * (p.max - p.min) }
						val want = editor.evaluate(pose)
						val got = native.evaluate(pose)
						for ((id, points) in want.positions) {
							val actual = got.positions.getValue(id)
							val error = points.indices.maxOfOrNull { abs(points[it] - actual[it]) } ?: 0f
							assertTrue(error < 0.02f, "$id differs by $error px")
							assertTrue(abs(want.opacity.getValue(id) - got.opacity.getValue(id)) < 1e-4f, "$id opacity")
							assertTrue(abs(want.drawOrder.getValue(id) - got.drawOrder.getValue(id)) < 1e-3f, "$id draw order")
						}
					}
				}
			}
		}
	}
}
