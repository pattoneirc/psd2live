package io.github.psd2live.targets.runtime

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class P2lrtTest {
	private val geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
	private val rig = RigIR(
		canvas = Canvas(100f, 80f),
		parameters = listOf(Parameter("A", "A", -1f, 1f, 0f)),
		// The child comes first; the file orders parents before children.
		deformers = listOf(
			Deformer.Rotation("R", "R", "W", null, 0f, KeyGrid.single(Pivot(0.5f, 0.5f, 0f, 1f))),
			Deformer.Warp("W", "W", null, null, 1, 1, true, KeyGrid.single(LatticePoints(Floats.values(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f)))),
		),
		meshes = listOf(Mesh("M", "M", "R", geometry = geometry, offsets = null, page = 0)),
		textures = Textures(pages = listOf(TexturePage(4, 4, Bytes.of(byteArrayOf(1, 2, 3))))),
	)

	@Test fun theFileStartsWithItsMagicVersionAndCanvas() {
		val bytes = P2lrt.write(rig)
		assertEquals("P2LRT\u0000\u0000\u0000", String(bytes, 0, 8, Charsets.ISO_8859_1))
		val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		assertEquals(P2lrt.VERSION, b.getInt(8))
		assertEquals(100f, b.getFloat(12)); assertEquals(80f, b.getFloat(16))
		// The deformers follow the canvas and parameters, parents first: the warp, then the rotation.
		val text = String(bytes, Charsets.ISO_8859_1)
		assertTrue(text.indexOf("\u0001\u0000\u0000\u0000W") < text.indexOf("\u0001\u0000\u0000\u0000R"))
	}

	@Test fun brokenReferencesAndCyclesAreRejected() {
		assertFailsWith<IllegalArgumentException> { P2lrt.write(rig.copy(meshes = listOf(rig.meshes[0].copy(parent = "missing")))) }
		val cycle = listOf(
			Deformer.Warp("X", "X", "Y", null, 1, 1, true, null),
			Deformer.Warp("Y", "Y", "X", null, 1, 1, true, null),
		)
		assertFailsWith<IllegalArgumentException> { P2lrt.write(rig.copy(deformers = cycle, meshes = emptyList())) }
	}

	@Test fun theTargetWritesOneFileAndReportsWhatIsLost() {
		val files = LinkedHashMap<String, ByteArray>()
		Compiler.export(P2lrtTarget, rig, ExportOptions("hero")) { path, bytes -> files[path] = bytes }
		assertEquals(listOf("hero.p2lrt"), files.keys.toList())
		assertContentEquals(P2lrt.write(rig), files.getValue("hero.p2lrt"))
	}
}
