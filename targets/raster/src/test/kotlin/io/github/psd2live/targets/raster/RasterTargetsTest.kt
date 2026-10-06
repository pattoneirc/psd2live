package io.github.psd2live.targets.raster

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class RasterTargetsTest {
	/** Paints a square whose x offset is the parameter value, and records what it was asked. */
	private class FakeRenderer : FrameRenderer {
		val calls = mutableListOf<Pair<Map<String, Float>, Float>>()
		var physics: Boolean? = null
		override fun open(ir: RigIR, physics: Boolean): FrameSession {
			this.physics = physics
			return object : FrameSession {
				override val simulatesPhysics = physics
				override fun render(parameters: Map<String, Float>, deltaSeconds: Float, frame: FrameSpec, meshes: Set<String>?): RasterImage {
					calls += parameters to deltaSeconds
					val pixels = IntArray(frame.outputWidth * frame.outputHeight)
					val x = (parameters["P"] ?: 0f).toInt().coerceIn(0, frame.outputWidth - 4)
					for (dy in 0 until 4) for (dx in 0 until 4) pixels[dy * frame.outputWidth + x + dx] = 0xffff0000.toInt()
					return RasterImage(frame.outputWidth, frame.outputHeight, pixels)
				}
				override fun close() {}
			}
		}
	}

	private val clip = Clip("move", "Move", "Idle", "move", 1f, 4f, true,
		curves = listOf(Curve("P", 0f, 0f, listOf(CurveSegment.Linear(1f, 12f)))))
	private val rig = RigIR(canvas = Canvas(64f, 32f), parameters = listOf(Parameter("P", "P", 0f, 20f, 0f)), clips = listOf(clip),
		physics = Physics(listOf(PhysicsGroup("H", "H", emptyList(), emptyList(), listOf(PhysicsSegment(1f, 1f, 1f, 1f)),
			PhysicsNormalization(-1f, 0f, 1f, -1f, 0f, 1f)))))

	private fun export(target: ExportTarget, vararg settings: Pair<String, String>): Pair<Map<String, ByteArray>, ExportReport> {
		val files = LinkedHashMap<String, ByteArray>()
		val report = Compiler.export(target, rig, ExportOptions("anim", settings = mapOf(*settings))) { path, bytes -> files[path] = bytes }
		return files to report
	}

	@Test fun sequencesSampleTheClipAtItsFrameRate() {
		val renderer = FakeRenderer()
		val (files, report) = export(RasterTargets(renderer).sequence, "size" to "64")
		// A looping 1 s clip at 4 fps: four frames, the loop's end being its start.
		assertEquals(listOf("anim_0000.png", "anim_0001.png", "anim_0002.png", "anim_0003.png"), files.keys.toList())
		assertEquals(listOf(0f, 3f, 6f, 9f), renderer.calls.map { it.first.getValue("P") })
		assertEquals(listOf(0f, 0.25f, 0.25f, 0.25f), renderer.calls.map { it.second })
		assertEquals(true, renderer.physics)
		// Physics was simulated, so only the structure is reported lost.
		assertEquals(listOf(Feature.STRUCTURE), report.losses.map { it.feature })
		val frame = ImageIO.read(ByteArrayInputStream(files.getValue("anim_0002.png")))
		assertEquals(64 to 32, frame.width to frame.height)
		assertEquals(0xffff0000.toInt(), frame.getRGB(7, 1))
	}

	@Test fun sheetsTileFramesAndDescribeThem() {
		val (files, _) = export(RasterTargets(FakeRenderer()).sheet, "size" to "64", "fps" to "2", "physics" to "false")
		val sheet = ImageIO.read(ByteArrayInputStream(files.getValue("anim.png")))
		assertEquals(128 to 32, sheet.width to sheet.height)
		val json = files.getValue("anim.json").decodeToString()
		assertTrue("\"anim_0001\": {\"frame\": {\"x\": 64, \"y\": 0, \"w\": 64, \"h\": 32}" in json, json)
		assertTrue("\"size\": {\"w\": 128, \"h\": 32}" in json && "\"loop\": true" in json)
	}

	@Test fun gifsHoldEveryFrameWithTransparency() {
		val (files, report) = export(RasterTargets(FakeRenderer()).gif, "size" to "64", "physics" to "false")
		val reader = ImageIO.getImageReadersByFormatName("gif").next()
		reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(files.getValue("anim.gif")))
		assertEquals(4, reader.getNumImages(true))
		val first = reader.read(0)
		assertEquals(0, first.getRGB(40, 20) ushr 24, "background stays transparent")
		assertEquals(0xffff0000.toInt(), first.getRGB(1, 1))
		assertTrue(report.losses.any { it.feature == Feature.PHYSICS }, "physics turned off is reported")
	}

	@Test fun invalidSettingsAreRejected() {
		val target = RasterTargets(FakeRenderer()).sequence
		for (bad in listOf("clip" to "missing", "fps" to "0", "size" to "8")) assertFailsWith<IllegalArgumentException> { export(target, bad) }
	}

	@Test fun aRigWithoutClipsRendersItsRestPose() {
		val renderer = FakeRenderer()
		val files = LinkedHashMap<String, ByteArray>()
		Compiler.export(RasterTargets(renderer).sequence, rig.copy(clips = emptyList()), ExportOptions("still")) { path, bytes -> files[path] = bytes }
		assertEquals(listOf("still_0000.png"), files.keys.toList())
		assertEquals(emptyMap(), renderer.calls.single().first)
	}
}
