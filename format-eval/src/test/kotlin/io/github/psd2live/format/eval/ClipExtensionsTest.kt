package io.github.psd2live.format.eval

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.runtime.P2lrt
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

/** What the Kotlin writer puts in CEXT and version 2 of PHYS, as the Rust runtime plays it. Needs the runtime built. */
class ClipExtensionsTest {
	@Suppress("FunctionName")
	private interface Native12 : Library {
		fun p2l_rig_load(bytes: ByteArray, len: Long, error: ByteArray, errorCapacity: Long): Pointer?
		fun p2l_rig_free(rig: Pointer)
		fun p2l_abi_version(): Int
		fun p2l_behaviors(rig: Pointer, flags: Int)
		fun p2l_play(rig: Pointer, index: Int)
		fun p2l_update(rig: Pointer, dt: Float)
		fun p2l_event_count(rig: Pointer): Int
		fun p2l_event(rig: Pointer, index: Int, layer: IntArray?, clip: IntArray?, time: FloatArray?): String?
		fun p2l_mesh_opacity(rig: Pointer, index: Int): Float
		fun p2l_parameter_current(rig: Pointer): Pointer
	}

	private fun native(): Native12 {
		val library = P2lRuntime.locate()
		assumeTrue(library != null, "The native runtime is not built")
		val native = Native.load(library!!.absolutePath, Native12::class.java)
		assumeTrue(runCatching { native.p2l_abi_version() and 0xffff >= 2 }.getOrDefault(false), "The runtime predates ABI 1.2")
		return native
	}

	private val geometry = MeshGeometry(Floats.values(0f, 0f, 10f, 0f, 0f, 10f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
	private val rig = RigIR(
		canvas = Canvas(64f, 64f),
		parameters = listOf(Parameter("A", "A", -10f, 10f, 0f), Parameter("B", "B", -10f, 10f, 0f)),
		parts = listOf(Part("P", "Part", listOf(ChildRef.MeshRef("M")))),
		meshes = listOf(Mesh("M", "M", null, geometry = geometry, offsets = null)),
		renderRoot = RenderGroup(null, 500, listOf(RenderMesh("M"))),
		clips = listOf(Clip("c", "Clip", "Idle", "", 1f, 30f, true, fadeIn = 0f, fadeOut = 0f,
			curves = listOf(Curve("A", 0f, 0f, emptyList())),
			events = listOf(ClipEvent(0.5f, "half")),
			targetCurves = listOf(TargetCurve(CurveTarget.PartOpacity("P"), 0f, 0.25f, emptyList())))),
		physics = Physics(listOf(PhysicsGroup("g", "G", listOf(PhysicsInput("A", 100f, PhysicsSource.Y, false)),
			listOf(PhysicsOutput("B", 1, 10f, 100f, PhysicsSource.ANGLE, false)), listOf(PhysicsSegment(10f, 0.9f, 0.9f, 1f)),
			PhysicsNormalization(-10f, 0f, 10f, -10f, 0f, 10f)))),
	)

	@Test fun eventsPartCurvesAndVerticalPhysicsPlayInTheRuntime() {
		val native = native()
		val bytes = P2lrt.write(rig)
		val error = ByteArray(256)
		val handle = native.p2l_rig_load(bytes, bytes.size.toLong(), error, error.size.toLong())
			?: fail(String(error, 0, error.indexOf(0), Charsets.UTF_8))
		try {
			native.p2l_behaviors(handle, 0)
			native.p2l_play(handle, 0)
			native.p2l_update(handle, 0.6f)
			assertEquals(1, native.p2l_event_count(handle))
			val time = FloatArray(1)
			assertEquals("half", native.p2l_event(handle, 0, null, null, time))
			assertEquals(0.5f, time[0])
			assertEquals(0.25f, native.p2l_mesh_opacity(handle, 0), 1e-6f)
		} finally {
			native.p2l_rig_free(handle)
		}
	}
}
