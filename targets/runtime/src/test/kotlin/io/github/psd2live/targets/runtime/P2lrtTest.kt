package io.github.psd2live.targets.runtime

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.test.*

class P2lrtTest {
	private val geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
	private val rig = RigIR(
		canvas = Canvas(100f, 80f),
		parameters = listOf(Parameter("A", "Angle", -1f, 1f, 0f, keys = Floats.values(-1f, 0f, 1f)), Parameter("B", "B", 0f, 1f, 0f)),
		parameterLinks = listOf(ParameterLink("A", "B")),
		parameterTree = listOf(ParameterNode.Group("G", "Face", true, listOf(ParameterNode.Param("A"), ParameterNode.Param("gone")), GroupLabel.Preset("RED"))),
		// The child comes first; the file orders parents before children.
		deformers = listOf(
			Deformer.Rotation("R", "R", "W", null, 0f, KeyGrid.single(Pivot(0.5f, 0.5f, 0f, 1f))),
			Deformer.Warp("W", "W", null, null, 1, 1, true, KeyGrid.single(LatticePoints(Floats.values(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f)))),
		),
		meshes = listOf(Mesh("M", "Mesh name", "R", geometry = geometry, offsets = null, page = 0)),
		textures = Textures(pages = listOf(TexturePage(4, 4, Bytes.of(byteArrayOf(1, 2, 3))))),
	)

	/** A chunk of a version 2 file, as stored and as its records read. */
	private class Chunk(val tag: String, val version: Int, val flags: Int, val offset: Long, val stored: ByteArray, val data: ByteArray, val crc: Int)

	private fun chunks(bytes: ByteArray): List<Chunk> {
		val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		assertEquals(32, b.getInt(20))
		return (0 until b.getInt(16)).map { i ->
			val at = 32 + i * 40
			val offset = b.getLong(at + 8); val length = b.getLong(at + 16).toInt(); val raw = b.getLong(at + 24).toInt()
			val stored = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
			val flags = b.getShort(at + 6).toInt()
			val data = if (flags shr 2 and 3 == 1) Inflater().run { setInput(stored); ByteArray(raw).also { inflate(it); end() } } else stored
			Chunk(String(bytes, at, 4, Charsets.US_ASCII), b.getShort(at + 4).toInt(), flags, offset, stored, data, b.getInt(at + 32))
		}
	}

	private fun strings(chunk: Chunk): List<String> {
		val b = ByteBuffer.wrap(chunk.data).order(ByteOrder.LITTLE_ENDIAN)
		val n = b.getInt(0)
		val offsets = (0..n).map { b.getInt(4 + it * 4) }
		val blob = 4 + (n + 1) * 4
		return (0 until n).map { String(chunk.data, blob + offsets[it], offsets[it + 1] - offsets[it], Charsets.UTF_8) }
	}

	@Test fun versionTwoIsAHeaderAChunkTableAndAlignedCheckedChunks() {
		val bytes = P2lrt.write(rig)
		assertEquals("P2LRT\u0000\u0000\u0000", String(bytes, 0, 8, Charsets.ISO_8859_1))
		val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		assertEquals(2, b.getShort(8).toInt())
		val chunks = chunks(bytes)
		assertEquals(listOf("STRS", "CANV", "PARM", "DEFM", "PART", "MESH", "DRAW", "TEXR", "PGUI", "META"), chunks.map { it.tag })
		for (c in chunks) {
			assertEquals(0L, c.offset % 16, c.tag)
			assertEquals(CRC32().apply { update(c.stored) }.value.toInt(), c.crc, c.tag)
			assertEquals(c.tag !in setOf("PGUI", "META"), c.flags and 1 == 1, c.tag)
		}
		val canvas = ByteBuffer.wrap(chunks.single { it.tag == "CANV" }.data).order(ByteOrder.LITTLE_ENDIAN)
		assertEquals(100f, canvas.getFloat(0)); assertEquals(80f, canvas.getFloat(4))
		// Strings are stored once, in first use; the group's parameter that the file lacks is left out.
		val strings = strings(chunks[0])
		assertEquals(strings.distinct(), strings)
		assertTrue(listOf("A", "Angle", "W", "R", "M", "Mesh name", "G", "Face", "RED", "generator", "psd2live").all { it in strings })
		assertFalse("gone" in strings)
	}

	@Test fun theSameRigWritesTheSameBytes() {
		assertContentEquals(P2lrt.write(rig), P2lrt.write(rig.copy()))
		assertContentEquals(P2lrt.write(rig, P2lrt.Options(compress = true)), P2lrt.write(rig, P2lrt.Options(compress = true)))
	}

	@Test fun compressionChangesOnlyHowChunksAreStored() {
		val plain = chunks(P2lrt.write(rig))
		val packed = chunks(P2lrt.write(rig, P2lrt.Options(compress = true)))
		assertEquals(plain.map { it.tag }, packed.map { it.tag })
		for ((p, c) in plain.zip(packed)) assertContentEquals(p.data, c.data, p.tag)
		// Texture pages are never compressed, and a chunk that would grow is stored as it is.
		assertEquals(0, packed.single { it.tag == "TEXR" }.flags shr 2 and 3)
		assertTrue(packed.all { it.stored.size <= it.data.size })
	}

	@Test fun strippedNamesKeepTheIds() {
		val bytes = P2lrt.write(rig, P2lrt.Options(stripNames = true))
		assertEquals(1, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(12))
		val strings = strings(chunks(bytes)[0])
		assertTrue(listOf("A", "M", "G").all { it in strings })
		assertTrue(listOf("Angle", "Mesh name", "Face").none { it in strings })
	}

	@Test fun versionOneIsStillWrittenOnRequest() {
		val bytes = P2lrt.write(rig, P2lrt.Options(version = 1))
		val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		assertEquals(1, b.getInt(8))
		assertEquals(100f, b.getFloat(12)); assertEquals(80f, b.getFloat(16))
		// The deformers follow the canvas and parameters, parents first: the warp, then the rotation.
		val text = String(bytes, Charsets.ISO_8859_1)
		assertTrue(text.indexOf("\u0001\u0000\u0000\u0000W") < text.indexOf("\u0001\u0000\u0000\u0000R"))
	}

	@Test fun brokenReferencesAndCyclesAreRejected() {
		for (version in 1..2) {
			val options = P2lrt.Options(version = version)
			assertFailsWith<IllegalArgumentException> { P2lrt.write(rig.copy(meshes = listOf(rig.meshes[0].copy(parent = "missing"))), options) }
			val cycle = listOf(
				Deformer.Warp("X", "X", "Y", null, 1, 1, true, null),
				Deformer.Warp("Y", "Y", "X", null, 1, 1, true, null),
			)
			assertFailsWith<IllegalArgumentException> { P2lrt.write(rig.copy(deformers = cycle, meshes = emptyList()), options) }
		}
	}

	@Test fun theTargetWritesOneFileAndReportsWhatIsLost() {
		val files = LinkedHashMap<String, ByteArray>()
		Compiler.export(P2lrtTarget, rig, ExportOptions("hero")) { path, bytes -> files[path] = bytes }
		assertEquals(listOf("hero.p2lrt"), files.keys.toList())
		assertContentEquals(P2lrt.write(rig), files.getValue("hero.p2lrt"))
		Compiler.export(P2lrtTarget, rig, ExportOptions("old", settings = mapOf("v1" to "true"))) { path, bytes -> files[path] = bytes }
		assertContentEquals(P2lrt.write(rig, P2lrt.Options(version = 1)), files.getValue("old.p2lrt"))
	}
}
