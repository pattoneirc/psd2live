package io.github.psd2live.format.compile.render

import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.PlacedRaster
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

	/** A quad over columns [x0]..[x1] of the canvas, full height, sampling texel [u]. */
	private fun rect(id: String, x0: Float, x1: Float, u: Float, page: Int, drawOrder: Float = 500f, blend: ColorBlend = ColorBlend.NORMAL,
	                 alpha: AlphaBlend = AlphaBlend.OVER, visible: Boolean = true) =
		Mesh(id, id, null, blend = blend, alphaBlend = alpha, drawOrder = drawOrder, page = page, visible = visible, offsets = null,
			geometry = MeshGeometry(Floats.values(x0, 0f, x1, 0f, x0, 10f, x1, 10f), Floats.values(u, 0.5f, u, 0.5f, u, 0.5f, u, 0.5f), Ints.values(0, 1, 2, 1, 3, 2)))

	private fun render(meshes: List<Mesh>, pages: List<TexturePage>, background: Int = 0, values: Map<String, Float> = emptyMap(),
	                   parameters: List<Parameter> = emptyList(), parts: List<Part> = emptyList(), root: RenderGroup? = null): IntArray {
		val ir = RigIR(Canvas(10f, 10f), parameters, meshes = meshes, textures = Textures(pages = pages), parts = parts,
			renderRoot = root ?: RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, emptyList()))
		val pose = PoseGeometry(meshes.associate { it.id to it.geometry!!.positions.toArray() }, meshes.associate { it.id to it.opacity }, meshes.associate { it.id to it.drawOrder })
		val colors = IrColors(ir)
		return SoftwareRasterizer(ir).render(pose, colors.at(values), FrameSpec(0f, 0f, 10f, 10f, 10, 10, background), parts = colors.parts(values)).argb
	}

	private fun scalar(parameter: String, vararg keyed: Pair<Float, Float>) = KeyGrid(listOf(KeyAxis(parameter, Floats.values(*keyed.map { it.first }.toFloatArray()))),
		keyed.mapIndexed { i, (_, v) -> KeyCell(Ints.values(i), ChannelValue.Scalar(v) as ChannelValue) })

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

	@Test fun addGlowIsTheW3cAddUnderTheAlphaMode() {
		val pages = listOf(page(0xff808080.toInt(), 0xff4080c0.toInt()), page(0xffff0000.toInt(), 0))
		// Over an opaque backdrop it adds, clamping at white.
		near(0xffc0ffff.toInt(), render(listOf(quad("under", 0.25f, 0, drawOrder = 100f), quad("top", 0.75f, 0, blend = ColorBlend.ADD_GLOW)), pages)[55])
		// Over nothing it covers as normal does, where Cubism's additive adds no coverage.
		near(0xffff0000.toInt(), render(listOf(quad("a", 0.25f, 1, blend = ColorBlend.ADD_GLOW)), pages)[55])
	}

	@Test fun overlayAtopKeepsTheBackdropAlphaAndMixesByItsCoverage() {
		// Backdrop: (0x40, 0x40, 0x40) at alpha 0x80 over the left half; source (0x40, 0x80, 0xc0) opaque everywhere.
		val pages = listOf(page(0x80404040.toInt(), 0xff4080c0.toInt()))
		val out = render(listOf(rect("under", 0f, 5f, 0.25f, 0, drawOrder = 100f), quad("top", 0.75f, 0, blend = ColorBlend.OVERLAY).copy(alphaBlend = AlphaBlend.ATOP)), pages)
		// B = 2·Cb·Cs = 0.502·Cs (Cb = 0.251 is dark); m = (1 − da)·Cs + da·B = 0.75·Cs; atop keeps da = 0x80.
		near(0x80306090.toInt(), out[52])
		// Where there is no backdrop the source does not show.
		assertEquals(0, out[57])
	}

	@Test fun outErasesTheBackdropUnderTheSource() {
		val pages = listOf(page(0xffff0000.toInt(), 0x800000ff.toInt()))
		val out = render(listOf(quad("under", 0.25f, 0, drawOrder = 100f), rect("top", 0f, 5f, 0.75f, 0, alpha = AlphaBlend.OUT)), pages)
		// da·(1 − sa) of the red stays, and no blue.
		near(0x7fff0000, out[52])
		near(0xffff0000.toInt(), out[57])
	}

	@Test fun conjointAndDisjointOverSetHowTheCoveragesOverlap() {
		// Half-covering red under half-covering blue.
		val pages = listOf(page(0x80ff0000.toInt(), 0x800000ff.toInt()))
		fun over(alpha: AlphaBlend) = render(listOf(quad("under", 0.25f, 0, drawOrder = 100f), quad("top", 0.75f, 0).copy(alphaBlend = alpha)), pages)[55]
		// Conjoint: the source hides the backdrop it fully overlaps, a = max(sa, da).
		near(0x800000ff.toInt(), over(AlphaBlend.CONJOINT))
		// Disjoint: the coverages lie side by side, a = min(1, sa + da), the red weighed by (1 − sa)/da.
		near(0xff7f0080.toInt(), over(AlphaBlend.DISJOINT))
		// Over, for contrast: a = sa + da·(1 − sa).
		near(0xc05500aa.toInt(), over(AlphaBlend.OVER))
	}

	@Test fun anIsolatedPartDrawsItsMeshesIntoALayerThatTakesTheGroupsOpacityAndColors() {
		// Gray everywhere, white over the left half, both opaque; the group multiplies by (1, 0.5, 0) and its
		// opacity channel goes from 1 to 0.5 as A does.
		val pages = listOf(page(0xff808080.toInt(), 0xffffffff.toInt()))
		val meshes = listOf(quad("a", 0.25f, 0), rect("b", 0f, 5f, 0.75f, 0, drawOrder = 600f))
		val composite = Composite(multiply = Rgb(1f, 0.5f, 0f))
		val channels = mapOf(Channel.OPACITY to scalar("A", 0f to 1f, 1f to 0.5f))
		val parts = listOf(Part("G", "G", listOf(ChildRef.MeshRef("a"), ChildRef.MeshRef("b")), groupMode = GroupMode.ISOLATED, composite = composite))
		val root = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, listOf(RenderGroup("G", 500, listOf(RenderMesh("a"), RenderMesh("b")), channels, composite)))
		val parameters = listOf(Parameter("A", "A", 0f, 1f, 0f))
		val opaque = render(meshes, pages, parameters = parameters, parts = parts, root = root)
		near(0xffff8000.toInt(), opaque[52]); near(0xff804000.toInt(), opaque[57])
		// At half opacity the layer fades as one: the white does not show the gray under it.
		val half = render(meshes, pages, values = mapOf("A" to 1f), parameters = parameters, parts = parts, root = root)
		near(0x80ff8000.toInt(), half[52]); near(0x80804000.toInt(), half[57])
	}

	@Test fun anIsolatedGroupIsMaskedByThePartsItNamesAndBlendsByItsMode() {
		val pages = listOf(page(0xff808080.toInt(), 0xffff0000.toInt()))
		val mask = rect("mask", 0f, 5f, 0.25f, 0, visible = false)
		val meshes = listOf(quad("back", 0.25f, 0, drawOrder = 100f), mask, quad("red", 0.75f, 0))
		fun group(invert: Boolean): IntArray {
			val composite = Composite(blend = ColorBlend.MULTIPLY, maskedByParts = listOf("M"), invertMask = invert)
			val parts = listOf(Part("M", "M", listOf(ChildRef.MeshRef("mask"))),
				Part("G", "G", listOf(ChildRef.MeshRef("red")), groupMode = GroupMode.ISOLATED, composite = composite))
			return render(meshes, pages, parts = parts, root = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER,
				listOf(RenderMesh("back"), RenderMesh("mask"), RenderGroup("G", 500, listOf(RenderMesh("red")), composite = composite))))
		}
		// Red multiplies the gray where the mask part covers, and leaves it elsewhere.
		val masked = group(invert = false)
		near(0xff800000.toInt(), masked[52]); near(0xff808080.toInt(), masked[57])
		val inverted = group(invert = true)
		near(0xff808080.toInt(), inverted[52]); near(0xff800000.toInt(), inverted[57])
	}

	@Test fun groupsSortAmongTheirSiblingsByTheirPartsDrawOrder() {
		val pages = listOf(page(0xffff0000.toInt(), 0xff0000ff.toInt()))
		val meshes = listOf(quad("blue", 0.75f, 0, drawOrder = 600f), quad("red", 0.25f, 0, drawOrder = 100f))
		val channels = mapOf(Channel.DRAW_ORDER to scalar("A", 0f to 700f, 1f to 500f))
		val parts = listOf(Part("P", "P", listOf(ChildRef.MeshRef("red")), groupMode = GroupMode.GROUPED, drawOrder = 700, channels = channels))
		val root = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, listOf(RenderMesh("blue"), RenderGroup("P", 700, listOf(RenderMesh("red")), channels)))
		val parameters = listOf(Parameter("A", "A", 0f, 1f, 0f))
		// The red mesh's own order is 100, but its group's is 700 and then 500.
		near(0xffff0000.toInt(), render(meshes, pages, parameters = parameters, parts = parts, root = root)[55])
		near(0xff0000ff.toInt(), render(meshes, pages, values = mapOf("A" to 1f), parameters = parameters, parts = parts, root = root)[55])
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

	@Test fun eachMeshDrawsOverItsOwnReachAsTheFullFrameDoes() {
		// A 4 x 4 page of varied texels, so bilinear sampling differs from pixel to pixel.
		val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until 4) for (x in 0 until 4) image.setRGB(x, y, ((40 + 50 * x) shl 24) or ((x * 60) shl 16) or ((y * 70) shl 8) or (x * y * 15))
		val pages = listOf(TexturePage(4, 4, Bytes.of(ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())))
		fun mesh(id: String, x0: Float, y0: Float, x1: Float, y1: Float, maskedBy: List<String> = emptyList(), invert: Boolean = false) =
			Mesh(id, id, null, page = 0, maskedBy = maskedBy, invertMask = invert, offsets = null, geometry = MeshGeometry(
				Floats.values(x0, y0, x1, y0, x0, y1, x1, y1), Floats.values(0.1f, 0.05f, 0.9f, 0.2f, 0f, 1f, 0.95f, 0.85f), Ints.values(0, 1, 2, 1, 3, 2)))
		val meshes = listOf(
			mesh("mask", 3.3f, 2.7f, 17.2f, 12.9f).copy(visible = false),
			mesh("a", 1.37f, 2.11f, 9.73f, 14.6f, maskedBy = listOf("mask")),
			mesh("b", 6.2f, -4f, 23.5f, 8.8f, maskedBy = listOf("mask"), invert = true),
			mesh("c", 30f, 30f, 40f, 40f),
		)
		val ir = RigIR(Canvas(20f, 16f), emptyList(), meshes = meshes, textures = Textures(pages = pages))
		val pose = PoseGeometry(meshes.associate { it.id to it.geometry!!.positions.toArray() }, meshes.associate { it.id to 1f }, meshes.associate { it.id to it.drawOrder })
		val colors = IrColors(ir).at(emptyMap())
		val frame = FrameSpec(0.5f, 0.25f, 19f, 15f, 37, 29)
		val rasterizer = SoftwareRasterizer(ir)
		val ids = meshes.map { it.id }
		val each = rasterizer.renderMeshes(pose, colors, frame, ids)
		for ((index, id) in ids.withIndex()) {
			val full = PlacedRaster(0, 0, rasterizer.render(pose, colors, frame, setOf(id))).trimmed()
			val placed = each[index]
			assertEquals(full == null, placed == null, id)
			if (full == null || placed == null) continue
			assertEquals(listOf(full.left, full.top, full.image.width, full.image.height), listOf(placed.left, placed.top, placed.image.width, placed.image.height), id)
			assertContentEquals(full.image.argb, placed.image.argb, id)
		}
		assertNotNull(each[1]); assertNotNull(each[2]); assertNull(each[3])
		// Whole frames drawn together match frames drawn one by one.
		val moved = PoseGeometry(pose.positions.mapValues { (_, p) -> FloatArray(p.size) { p[it] + 1.5f } }, pose.opacity, pose.drawOrder)
		val frames = rasterizer.renderFrames(listOf(pose to colors, moved to colors), frame)
		assertContentEquals(rasterizer.render(pose, colors, frame).argb, frames[0].argb)
		assertContentEquals(rasterizer.render(moved, colors, frame).argb, frames[1].argb)
		// Pixels the host already holds stand in for decoding the page.
		val held = SoftwareRasterizer(ir) { image.getRGB(0, 0, 4, 4, null, 0, 4) }
		assertContentEquals(rasterizer.render(pose, colors, frame).argb, held.render(pose, colors, frame).argb)
	}

	@Test fun hiddenPartsAndMeshesAreNotDrawn() {
		val pages = listOf(page(0xffff0000.toInt(), 0))
		val ir = RigIR(Canvas(10f, 10f), emptyList(), parts = listOf(Part("P", "P", listOf(ChildRef.MeshRef("m")), visible = false)),
			meshes = listOf(quad("m", 0.25f, 0)), textures = Textures(pages = pages))
		val pose = PoseGeometry(mapOf("m" to ir.meshes[0].geometry!!.positions.toArray()), mapOf("m" to 1f), mapOf("m" to 500f))
		assertTrue(SoftwareRasterizer(ir).render(pose, IrColors(ir).at(emptyMap()), FrameSpec(0f, 0f, 10f, 10f, 10, 10)).argb.all { it == 0 })
	}
}
