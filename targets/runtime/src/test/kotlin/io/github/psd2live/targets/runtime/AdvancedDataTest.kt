package io.github.psd2live.targets.runtime

import io.github.psd2live.format.model.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class AdvancedDataTest {
	private val keys = Floats.values(-60f, -20f, 20f, 60f)
	private fun pivotAt(degrees: Float) = Pivot(20f - 50f * sin(Math.toRadians(degrees.toDouble())).toFloat(), 30f + 50f * cos(Math.toRadians(degrees.toDouble())).toFloat(), degrees, 1f)
	private fun keyed(parameter: String, forms: List<Pivot>) = KeyGrid(listOf(KeyAxis(parameter, keys)), forms.mapIndexed { i, f -> KeyCell(Ints.values(i), f) })
	private val geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
	private val offsets = KeyGrid(listOf(KeyAxis("Elbow", keys)), (0..3).map { KeyCell(Ints.values(it), MeshOffsets(Floats.values(0f, 0f, 0f, 0f, it.toFloat(), 0f))) })

	private val rig = RigIR(
		canvas = Canvas(100f, 100f),
		parameters = listOf(Parameter("Elbow", "Elbow", -60f, 60f, 0f), Parameter("Link", "Link", -60f, 60f, 0f), Parameter("Other", "Other", 0f, 1f, 0f)),
		deformers = listOf(
			Deformer.Rotation("upper", "upper", null, null, 0f, null),
			Deformer.Rotation("forearm", "forearm", "upper", null, 0f, keyed("Elbow", listOf(-60f, -20f, 20f, 60f).map { Pivot(0f, 100f, it, 1f) })),
			Deformer.Rotation("link", "link", null, null, 0f, keyed("Link", listOf(-60f, -20f, 20f, 60f).map(::pivotAt))),
			// Keyed in a straight line: no arc.
			Deformer.Rotation("slide", "slide", null, null, 0f, keyed("Link", listOf(-1f, -0.5f, 0.5f, 1f).map { Pivot(it * 10f, 0f, 0f, 1f) })),
		),
		meshes = listOf(
			Mesh("arm", "arm", "upper", geometry = geometry, offsets = offsets),
			// Keyed on a parameter no bone below its home turns: not skinned.
			Mesh("flat", "flat", "upper", geometry = geometry, offsets = offsets.copy(axes = listOf(KeyAxis("Other", keys)))),
			Mesh("loose", "loose", null, geometry = geometry, offsets = offsets),
		),
	)

	@Test fun aMeshBentAcrossAJointBelowItsHomeIsSkinned() {
		val skins = AdvancedData.skins(rig)
		assertEquals(listOf("arm"), skins.map { it.mesh })
		assertEquals(listOf("upper", "forearm"), skins[0].bones)
		assertEquals(listOf("Elbow"), skins[0].axes.map { it.first })
	}

	@Test fun pivotsKeyedOnACircleAreArcs() {
		val arcs = AdvancedData.arcs(rig)
		assertEquals(listOf("link"), arcs.map { it.deformer })
		assertEquals(20f, arcs[0].centerX, 1e-3f)
		assertEquals(30f, arcs[0].centerY, 1e-3f)
	}

	@Test fun aVirtualBoneBelowHomeSkinsLikeARealOne() {
		val forearm = rig.deformers[1] as Deformer.Rotation
		val folded = rig.copy(deformers = rig.deformers - forearm, advanced = AdvancedIR(virtualBones = listOf(forearm)))
		assertEquals(listOf("upper", "forearm"), AdvancedData.skins(folded).single().bones)
		// The BONE chunk carries it after the rig's own deformers.
		val bytes = P2lrt.write(folded)
		assertTrue(String(bytes, Charsets.ISO_8859_1).contains("BONE"))
	}

	@Test fun theAdvancedChunksAreOptionalAndCanBeLeftOut() {
		val chunks = { bytes: ByteArray ->
			val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
			(0 until b.getInt(16)).associate { String(bytes, 32 + it * 40, 4, Charsets.US_ASCII) to b.getShort(32 + it * 40 + 6).toInt() }
		}
		val with = chunks(P2lrt.write(rig))
		assertEquals(0, with.getValue("BONE") and 1)
		assertEquals(0, with.getValue("SKIN") and 1)
		val without = chunks(P2lrt.write(rig, P2lrt.Options(advanced = false)))
		assertFalse("BONE" in without || "SKIN" in without)
	}
}
