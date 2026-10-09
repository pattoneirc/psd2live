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

	@Test fun theLibraryImplementsTheAbiTheBindingsNeed() {
		val abi = runtime().abiVersion
		assertEquals(P2lRuntime.ABI_MAJOR, abi ushr 16)
		assertTrue((abi and 0xffff) >= P2lRuntime.ABI_MINOR)
		runtime().load(rig).use { r ->
			r.evaluate()
			assertNull(r.failure)
		}
	}

	@Test fun invalidRigsAreRejectedWithTheRuntimesMessage() {
		val runtime = runtime()
		val error = assertFailsWith<IllegalArgumentException> { runtime.load(byteArrayOf(1, 2, 3)) }
		assertTrue(error.message!!.isNotBlank())
	}

	@Test fun isolatedPartsKeepTheirGroupInTheRenderCommands() {
		val mask = Mesh("K", "K", null, blend = ColorBlend.SCREEN, alphaBlend = AlphaBlend.ATOP, visible = false,
			geometry = rig.meshes[0].geometry, offsets = null)
		val isolated = rig.copy(
			meshes = rig.meshes + mask,
			parts = listOf(Part("P", "P", listOf(ChildRef.MeshRef("M")), groupMode = GroupMode.ISOLATED)),
			renderRoot = RenderGroup(null, 500, listOf(RenderMesh("K"), RenderGroup("P", 500, listOf(RenderMesh("M")),
				composite = Composite(ColorBlend.MULTIPLY, AlphaBlend.OUT, maskedBy = listOf("K"), invertMask = true,
					opacity = 0.5f, multiply = Rgb(1f, 0.5f, 0.25f), screen = Rgb(0f, 0.1f, 0f))))),
		)
		runtime().load(isolated).use { r ->
			r.evaluate()
			assertEquals(listOf("P"), r.partIds)
			assertContentEquals(intArrayOf(P2lRuntime.beginGroup(0), 0, P2lRuntime.END_GROUP), r.renderCommands())
			assertEquals(0, P2lRuntime.groupPart(r.renderCommands()[0]))
			val part = r.part(0)
			assertEquals(P2lRuntime.GROUP_ISOLATED, part.group)
			assertEquals(ColorBlend.MULTIPLY.ordinal, part.blend)
			assertEquals(AlphaBlend.OUT.ordinal, part.alphaBlend)
			assertTrue(part.invertMask)
			assertContentEquals(intArrayOf(1), part.masks)
			val multiply = FloatArray(3)
			val screen = FloatArray(3)
			assertEquals(0.5f, r.partComposite(0, multiply, screen))
			assertContentEquals(floatArrayOf(1f, 0.5f, 0.25f), multiply)
			assertContentEquals(floatArrayOf(0f, 0.1f, 0f), screen)
			assertTrue(r.partComposite(5, multiply, screen).isNaN())
			assertEquals(ColorBlend.SCREEN.ordinal, r.mesh(1).blend)
			assertEquals(AlphaBlend.ATOP.ordinal, r.mesh(1).alphaBlend)
			assertEquals(AlphaBlend.OVER.ordinal, r.mesh(0).alphaBlend)
		}
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
