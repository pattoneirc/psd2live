package io.github.psd2live.format.compile.render

import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.model.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class SoftwareRasterizerTest {
	/** A 2 x 1 page: left texel opaque [left], right texel [right]. */
	private fun page(left: Int, right: Int): TexturePage {
		val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, left); setRGB(1, 0, right) }
		val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
		return TexturePage(2, 1, Bytes.of(bytes))
	}

	/** A quad covering the 10 x 10 canvas sampling the texel centered at [u]. */
	private fun quad(id: String, u: Float, page: Int, blend: ColorBlend = ColorBlend.NORMAL, maskedBy: List<String> = emptyList(),
	                 invert: Boolean = false, drawOrder: Float = 500f, channels: Channels = emptyMap(), shapes: List<BlendBinding<MeshShape>> = emptyList()) =
		Mesh(id, id, null, blend = blend, maskedBy = maskedBy, invertMask = invert, drawOrder = drawOrder, page = page, channels = channels, shapes = shapes,
			geometry = MeshGeometry(Floats.values(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f), Floats.values(u, 0.5f, u, 0.5f, u, 0.5f, u, 0.5f), Ints.values(0, 1, 2, 1, 3, 2)),
			offsets = null)

	/** A triangle over the canvas' left half only, sampling texel [u]. */
	private fun leftHalf(id: String, u: Float, page: Int) = Mesh(id, id, null, page = page, drawOrder = 100f, offsets = null,
		geometry = MeshGeometry(Floats.values(0f, 0f, 5f, 0f, 0f, 10f, 5f, 10f), Floats.values(u, 0.5f, u, 0.5f, u, 0.5f, u, 0.5f), Ints.values(0, 1, 2, 1, 3, 2)))

	private fun render(meshes: List<Mesh>, pages: List<TexturePage>, background: Int = 0, values: Map<String, Float> = emptyMap(),
	                   parameters: List<Parameter> = emptyList()): IntArray {
		val ir = RigIR(Canvas(10f, 10f), parameters, meshes = meshes, textures = Textures(pages = pages))
		val pose = PoseGeometry(meshes.associate { it.id to it.geometry!!.positions.toArray() }, meshes.associate { it.id to it.opacity }, meshes.associate { it.id to it.drawOrder })
		return SoftwareRasterizer(ir).render(pose, IrColors(ir).at(values), FrameSpec(0f, 0f, 10f, 10f, 10, 10, background)).argb
	}

	private fun near(want: Int, got: Int) {
		for (shift in listOf(24, 16, 8, 0)) assertTrue(kotlin.math.abs((want ushr shift and 0xff) - (got ushr shift and 0xff)) <= 2,
			"want %08x got %08x".format(want, got))
	}

	@Test fun masksClipByTextureAlphaAndInvertedMasksByItsComplement() {
		// The mask covers the left half with alpha; the masked quad is red everywhere.
		val pages = listOf(page(0xffff0000.toInt(), 0x00000000))
		val mask = leftHalf("mask", 0.25f, 0).copy(visible = false)
		val masked = render(listOf(mask, quad("m", 0.25f, 0, maskedBy = listOf("mask"))), pages)
		near(0xffff0000.toInt(), masked[5 * 10 + 2]); assertEquals(0, masked[5 * 10 + 7])
		val inverted = render(listOf(mask, quad("m", 0.25f, 0, maskedBy = listOf("mask"), invert = true)), pages)
		assertEquals(0, inverted[5 * 10 + 2]); near(0xffff0000.toInt(), inverted[5 * 10 + 7])
	}

	@Test fun blendModesCombineWithWhatIsBelow() {
		val pages = listOf(page(0xff808080.toInt(), 0xff4080c0.toInt()))
		fun over(mode: ColorBlend) = render(listOf(quad("under", 0.25f, 0, drawOrder = 100f), quad("top", 0.75f, 0, blend = mode)), pages)[55]
		near(0xff4080c0.toInt(), over(ColorBlend.NORMAL))
		// Multiply: 0x80 x (0x40, 0x80, 0xc0) / 0xff.
		near(0xff204060.toInt(), over(ColorBlend.MULTIPLY))
		near(0xff204060.toInt(), over(ColorBlend.MULTIPLY_PREMULTIPLIED))
		// Additive clamps at white.
		near(0xffc0ffff.toInt(), over(ColorBlend.ADD_PREMULTIPLIED))
		near(0xffa0c0e0.toInt(), over(ColorBlend.SCREEN))
		near(0xff408080.toInt(), over(ColorBlend.DARKEN))
		near(0xff8080c0.toInt(), over(ColorBlend.LIGHTEN))
	}

	@Test fun cubismAdditiveKeepsTheDestinationAlpha() {
		val pages = listOf(page(0xffff0000.toInt(), 0xff00ff00.toInt()))
		// Over nothing, additive adds color but no coverage; normal covers.
		assertEquals(0, render(listOf(quad("a", 0.75f, 0, blend = ColorBlend.ADD_PREMULTIPLIED)), pages)[55] ushr 24)
		near(0xffffff00.toInt(), render(listOf(quad("under", 0.25f, 0, drawOrder = 100f), quad("a", 0.75f, 0, blend = ColorBlend.ADD_PREMULTIPLIED)), pages)[55])
	}

	@Test fun multiplyAndScreenColorsFollowChannelsAndBlendShapes() {
		val pages = listOf(page(0xff808080.toInt(), 0))
		val parameters = listOf(Parameter("A", "A", 0f, 1f, 0f), Parameter("S", "S", 0f, 1f, 0f, blend = true))
		val channel = mapOf(Channel.MULTIPLY_COLOR to KeyGrid(listOf(KeyAxis("A", Floats.values(0f, 1f))),
			listOf(KeyCell(Ints.values(0), ChannelValue.Color(Rgb.White) as ChannelValue), KeyCell(Ints.values(1), ChannelValue.Color(Rgb(1f, 0f, 0f)) as ChannelValue))))
		val shape = listOf(BlendBinding("S", Floats.values(0f, 1f), 0, listOf(null, MeshShape(Floats.Empty, 500f, 1f, Rgb.White, Rgb(0f, 0f, 1f)))))
		val mesh = quad("m", 0.25f, 0, channels = channel, shapes = shape)
		near(0xff808080.toInt(), render(listOf(mesh), pages, parameters = parameters)[55])
		// Halfway to red multiplies green and blue by one half.
		near(0xff804040.toInt(), render(listOf(mesh), pages, values = mapOf("A" to 0.5f), parameters = parameters)[55])
		// The shape screens blue at full weight: 0x80 + 0xff - 0x80 x 0xff / 0xff.
		near(0xff8080ff.toInt(), render(listOf(mesh), pages, values = mapOf("S" to 1f), parameters = parameters)[55])
	}

	@Test fun hiddenPartsAndMeshesAreNotDrawn() {
		val pages = listOf(page(0xffff0000.toInt(), 0))
		val ir = RigIR(Canvas(10f, 10f), emptyList(), parts = listOf(Part("P", "P", listOf(ChildRef.MeshRef("m")), visible = false)),
			meshes = listOf(quad("m", 0.25f, 0)), textures = Textures(pages = pages))
		val pose = PoseGeometry(mapOf("m" to ir.meshes[0].geometry!!.positions.toArray()), mapOf("m" to 1f), mapOf("m" to 500f))
		assertTrue(SoftwareRasterizer(ir).render(pose, IrColors(ir).at(emptyMap()), FrameSpec(0f, 0f, 10f, 10f, 10, 10)).argb.all { it == 0 })
	}
}
