package io.github.psd2live.tools

import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.compile.render.SoftwareRasterizer
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.runtime.P2lrt
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * A synthetic rig for checking players by eye: every color blend mode over a gradient, multiply and screen
 * colors, a mask and an inverted mask. Writes build/tools/runtime-samples/features.p2lrt and the software
 * rasterizer's rendering of it, features.png, as the reference.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*RuntimeSamplesTool'
 */
class RuntimeSamplesTool {
	@Test fun features() {
		requireTools()
		val out = output("runtime-samples")
		fun png(image: BufferedImage) = Bytes.wrap(ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
		// Page 0: a gradient (top half) and four solid blocks (bottom half); page 1: an opaque disc.
		val atlas = BufferedImage(256, 64, BufferedImage.TYPE_INT_ARGB)
		for (x in 0 until 256) for (y in 0 until 32) {
			val t = x / 255f
			atlas.setRGB(x, y, (0xff shl 24) or ((255 * (1 - t)).toInt() shl 16) or ((128 + 100 * kotlin.math.sin(t * 6f)).toInt() shl 8) or (255 * t).toInt())
		}
		val solids = listOf(0xffe04030.toInt(), 0xff30c060.toInt(), 0xff3060e0.toInt(), 0xff808080.toInt())
		for ((i, c) in solids.withIndex()) for (x in i * 64 until i * 64 + 64) for (y in 32 until 64) atlas.setRGB(x, y, c)
		val disc = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
		for (x in 0 until 64) for (y in 0 until 64) if ((x - 31.5f) * (x - 31.5f) + (y - 31.5f) * (y - 31.5f) < 30f * 30f) disc.setRGB(x, y, 0xffffffff.toInt())
		fun quad(id: String, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, page: Int,
		         blend: ColorBlend = ColorBlend.NORMAL, order: Float = 500f, multiply: Rgb = Rgb.White, screen: Rgb = Rgb.Black,
		         maskedBy: List<String> = emptyList(), invert: Boolean = false, visible: Boolean = true) =
			Mesh(id, id, null, blend = blend, maskedBy = maskedBy, invertMask = invert, drawOrder = order, multiply = multiply, screen = screen,
				page = page, visible = visible, offsets = null, geometry = MeshGeometry(
					Floats.values(x, y, x + w, y, x, y + h, x + w, y + h), Floats.values(u0, v0, u1, v0, u0, v1, u1, v1), Ints.values(0, 1, 2, 1, 3, 2)))
		val meshes = ArrayList<Mesh>()
		meshes += quad("background", 0f, 0f, 800f, 600f, 0f, 0.1f, 1f, 0.4f, 0, order = 100f)
		val modes = ColorBlend.entries
		modes.forEachIndexed { i, mode ->
			val col = i % 6; val row = i / 6
			// Each block takes one of the solid colors.
			val u = (i % 3) / 4f + 0.125f
			meshes += quad("blend-${mode.name.lowercase()}", 20f + col * 130f, 20f + row * 130f, 110f, 110f, u, 0.75f, u, 0.75f, 0, blend = mode)
		}
		meshes += quad("multiply-color", 20f, 430f, 110f, 110f, 0.875f, 0.75f, 0.875f, 0.75f, 0, multiply = Rgb(1f, 0.5f, 0.2f))
		meshes += quad("screen-color", 150f, 430f, 110f, 110f, 0.875f, 0.75f, 0.875f, 0.75f, 0, screen = Rgb(0.1f, 0.3f, 0.9f))
		meshes += quad("mask", 300f, 430f, 110f, 110f, 0f, 0f, 1f, 1f, 1, visible = false)
		meshes += quad("masked", 280f, 410f, 150f, 150f, 0.375f, 0.75f, 0.375f, 0.75f, 0, maskedBy = listOf("mask"))
		meshes += quad("mask-2", 470f, 430f, 110f, 110f, 0f, 0f, 1f, 1f, 1, visible = false)
		meshes += quad("inverted", 450f, 410f, 150f, 150f, 0.125f, 0.75f, 0.125f, 0.75f, 0, maskedBy = listOf("mask-2"), invert = true)
		val ir = RigIR(Canvas(800f, 600f), emptyList(), meshes = meshes,
			textures = Textures(pages = listOf(TexturePage(256, 64, png(atlas)), TexturePage(64, 64, png(disc)))),
			renderRoot = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, meshes.map { RenderMesh(it.id) }))
		File(out, "features.p2lrt").writeBytes(P2lrt.write(ir))
		val pose = IrGeometryEvaluator.open(ir).use { it.evaluate(emptyMap()) }
		val frame = SoftwareRasterizer(ir).render(pose, IrColors(ir).at(emptyMap()), FrameSpec(0f, 0f, 800f, 600f, 800, 600))
		val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, width, height, frame.argb, 0, width) }
		ImageIO.write(image, "png", File(out, "features.png"))
		File(out, "layout.txt").writeText(modes.withIndex().joinToString("\n") { (i, m) -> "row ${i / 6} column ${i % 6}: ${m.name}" } +
			"\nrow 3: multiply color, screen color, mask, inverted mask\n")
	}
}
