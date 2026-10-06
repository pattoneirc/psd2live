package io.github.psd2live.format.compile

import io.github.psd2live.format.model.*
import kotlin.test.*

class ExportFrameworkTest {
	private val curve = Curve("P", 0f, 0f, listOf(
		CurveSegment.Linear(1f, 10f),
		CurveSegment.Stepped(2f, 20f),
		CurveSegment.InverseStepped(3f, 30f),
		CurveSegment.Bezier(3f + 1f / 3f, 30f, 4f - 1f / 3f, 40f, 4f, 40f),
	))

	@Test fun curvesFollowEachSegmentType() {
		assertEquals(0f, ClipSampler.valueAt(curve, -1f))
		assertEquals(5f, ClipSampler.valueAt(curve, 0.5f), 1e-5f)
		assertEquals(10f, ClipSampler.valueAt(curve, 1.5f), "stepped holds the previous value")
		assertEquals(20f, ClipSampler.valueAt(curve, 2f))
		assertEquals(30f, ClipSampler.valueAt(curve, 2.5f), "inverse stepped jumps at the start")
		// A Bezier whose handles sit flat on its ends eases in and out symmetrically.
		assertEquals(35f, ClipSampler.valueAt(curve, 3.5f), 1e-4f)
		assertTrue(ClipSampler.valueAt(curve, 3.25f) < 32.5f)
		assertEquals(40f, ClipSampler.valueAt(curve, 9f))
	}

	@Test fun loopingClipsWrapAndOneShotsHoldTheirEnds() {
		val loop = Clip("c", "C", "Idle", "c", 4f, 30f, true, curves = listOf(curve))
		assertEquals(ClipSampler.valuesAt(loop, 0.5f), ClipSampler.valuesAt(loop, 4.5f))
		assertEquals(120, ClipSampler.frameTimes(loop, 30f).size)
		val once = loop.copy(loop = false)
		assertEquals(40f, ClipSampler.valuesAt(once, 10f).getValue("P"))
		assertEquals(121, ClipSampler.frameTimes(once, 30f).size)
	}

	private val rig = RigIR(
		canvas = Canvas(100f, 100f),
		parameters = listOf(Parameter("P", "P", 0f, 1f, 0f)),
		deformers = listOf(Deformer.Warp("W", "W", null, null, 1, 1, false, null)),
		meshes = listOf(Mesh("M", "M", "W", blend = ColorBlend.OVERLAY, maskedBy = listOf("N"), geometry = null, offsets = null,
			channels = mapOf(Channel.DRAW_ORDER to KeyGrid.single(ChannelValue.Scalar(1f) as ChannelValue)))),
		glues = listOf(Glue("G", "M", "N", emptyList())),
		physics = Physics(listOf(PhysicsGroup("H", "H", emptyList(), emptyList(), listOf(PhysicsSegment(1f, 1f, 1f, 1f)),
			PhysicsNormalization(-1f, 0f, 1f, -1f, 0f, 1f)))),
		textures = Textures(pages = listOf(TexturePage(3000, 1000))),
	)

	@Test fun theScanReportsEveryCapabilityATargetLacks() {
		val losses = CapabilityScan.scan(rig, CapabilityProfile(parameterGrid = 2, maxTextureSize = 2048, powerOfTwo = true), ExportOptions("x"))
		val features = losses.map { it.objectId to it.feature }.toSet()
		assertEquals(setOf("W" to Feature.WARP_LATTICE, "M" to Feature.BLEND_MODE, "M" to Feature.MASK, "M" to Feature.KEYED_DRAW_ORDER,
			"G" to Feature.GLUE, "H" to Feature.PHYSICS, "texture:0" to Feature.TEXTURE_SIZE), features)
		assertEquals(Handling.DROPPED, losses.single { it.feature == Feature.MASK }.handling)
	}

	@Test fun handlingOverridesAndRasterTargetsCollapseToStructure() {
		val options = ExportOptions("x", handling = mapOf(Feature.WARP_LATTICE to Handling.APPROXIMATED))
		assertEquals(Handling.APPROXIMATED, CapabilityScan.scan(rig, CapabilityProfile(), options).single { it.feature == Feature.WARP_LATTICE }.handling)
		val raster = CapabilityScan.scan(rig, CapabilityProfile(structure = false), options)
		assertEquals(listOf(Feature.STRUCTURE, Feature.PHYSICS), raster.map { it.feature })
	}

	private fun target(vararg paths: String) = object : ExportTarget {
		override val id = "t"; override val family = TargetFamily.RIG; override val capabilities = CapabilityProfile(); override val description = "t"
		override fun plan(ir: RigIR, options: ExportOptions) = object : LoweredExport {
			override val losses = listOf(LossEntry("*", Feature.STRUCTURE, Handling.DROPPED, 1.5f, "say \"hi\"\n"))
			override fun write(sink: OutputSink) = paths.forEach { sink.write(it, byteArrayOf(1)) }
		}
	}

	@Test fun exportsStayInsideTheirOutputAndReportAsJson() {
		val written = mutableListOf<String>()
		val report = Compiler.export(target("a.json", "dir/b.png"), rig, ExportOptions("x")) { path, _ -> written += path }
		assertEquals(listOf("a.json", "dir/b.png"), written)
		assertEquals(Compiler.version, report.compiler)
		assertTrue(report.toJson().contains("\"note\":\"say \\\"hi\\\"\\n\""))
		assertTrue(report.toJson().contains("\"error\":1.5"))
		for (bad in listOf("../a", "/abs", "a//b", "dir/../x")) assertFailsWith<IllegalArgumentException> { Compiler.export(target(bad), rig, ExportOptions("x")) { _, _ -> } }
		assertFailsWith<IllegalArgumentException> { Compiler.export(target("a", "a"), rig, ExportOptions("x")) { _, _ -> } }
		assertFailsWith<IllegalArgumentException> { ExportOptions("bad/name") }
		assertFailsWith<IllegalArgumentException> { ExportRegistry(listOf(target(), target())) }
		assertFailsWith<IllegalArgumentException> { ExportRegistry(listOf(target()))["missing"] }
	}
}
