package io.github.psd2live.format.eval

import io.github.psd2live.format.model.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

/** Needs the runtime built with `cargo build --release` in runtime/; skipped otherwise. */
class P2lRuntimeTest {
	private fun runtime(): P2lRuntime = P2lRuntime.load().also { assumeTrue(it != null, "The native runtime is not built") }!!

	// A lattice turned half a radian holds a rotation; its child follows the lattice's turn at its own size.
	private val c = cos(0.5f); private val s = sin(0.5f)
	private val rig = RigIR(
		canvas = Canvas(512f, 512f),
		parameters = listOf(Parameter("A", "A", 0f, 1f, 0f)),
		deformers = listOf(
			Deformer.Warp("W", "W", null, null, 1, 1, true, KeyGrid.single(LatticePoints(Floats.values(
				200f, 200f, 200f + 200f * c, 200f + 200f * s, 200f - 200f * s, 200f + 200f * c, 200f + 200f * (c - s), 200f + 200f * (s + c))))),
			Deformer.Rotation("R", "R", "W", null, 45f, KeyGrid(listOf(KeyAxis("A", Floats.values(0f, 1f))),
				listOf(KeyCell(Ints.values(0), Pivot(0.5f, 0.5f, 0f, 1f)), KeyCell(Ints.values(1), Pivot(0.5f, 0.5f, 20f, 1f))))),
		),
		meshes = listOf(Mesh("M", "M", "R", geometry = MeshGeometry(Floats.values(0f, 0f, 10f, 0f, 0f, 10f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)), offsets = null)),
		renderRoot = RenderGroup(null, 500, listOf(RenderMesh("M"))),
	)

	@Test fun aRigEvaluatesThroughTheNativeRuntime() {
		NativeGeometryEvaluator(runtime()).open(rig).use { session ->
			for ((a, angle) in listOf(0f to 73.648f, 1f to 93.648f)) {
				val p = session.evaluate(mapOf("A" to a)).positions.getValue("M")
				assertEquals(239.82f, p[0], 0.01f); assertEquals(335.70f, p[1], 0.01f)
				assertEquals(angle, Math.toDegrees(atan2(p[3] - p[1], p[2] - p[0]).toDouble()).toFloat(), 0.01f)
			}
		}
	}

	@Test fun invalidRigsAreRejectedWithTheRuntimesMessage() {
		val runtime = runtime()
		val error = assertFailsWith<IllegalArgumentException> { runtime.load(byteArrayOf(1, 2, 3)) }
		assertTrue(error.message!!.isNotBlank())
	}

	@Test fun theRigReportsItsObjectsAndDrawsBackToFront() {
		runtime().load(rig).use { r ->
			assertEquals(listOf("A"), r.parameterIds)
			assertEquals(listOf("M"), r.meshIds)
			r.set(mapOf("A" to 0.5f))
			r.evaluate()
			assertContentEquals(intArrayOf(0), r.renderOrder())
			assertEquals(1f, r.opacity(0))
		}
	}
}
