package io.github.psd2live.render

import io.github.psd2live.format.eval.P2lRuntime
import io.github.psd2live.format.model.AlphaBlend
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.ChildRef
import io.github.psd2live.format.model.ColorBlend
import io.github.psd2live.format.model.Composite
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.GroupMode
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.RenderGroup
import io.github.psd2live.format.model.RenderMesh
import io.github.psd2live.format.model.RenderNode
import io.github.psd2live.format.model.Rgb
import io.github.psd2live.format.model.RigIR
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.umamo.render.puppet.compositeReference
import org.umamo.render.puppet.packedAlphaModeOf
import org.umamo.render.puppet.packedColorModeOf
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import io.github.psd2live.format.model.Mesh as IrMesh
import io.github.psd2live.format.model.Part as IrPart

/**
 * The `.p2lrt` preview's compositing read back from GL against the editor's reference math
 * ([compositeReference]): every color blend mode under every alpha blend mode, and isolated groups with their
 * modes, opacity, colors, masks, nesting and the meshes inside them. Needs an OpenGL 3.3 context and the runtime
 * built with `cargo build --release` in runtime/; skipped otherwise.
 */
class P2lrtGlRendererTest {
	@Test fun groupEndsPairEachOpeningWithItsClosing() {
		val g0 = P2lRuntime.beginGroup(0)
		val g1 = P2lRuntime.beginGroup(1)
		val end = P2lRuntime.END_GROUP
		assertContentEquals(intArrayOf(0, 5, 0, 4, 0, 0, 0), P2lrtGlRenderer.groupEnds(intArrayOf(3, g0, 1, g1, end, end, 2)))
		// A group never closed runs to the end.
		assertContentEquals(intArrayOf(2, 0), P2lrtGlRenderer.groupEnds(intArrayOf(g0, 1)))
	}

	@Test fun onlyNormalOverAndCubismsAddAndMultiplyBlendInFixedFunction() {
		assertTrue(P2lrtGlRenderer.fixedFunction(0, 0))
		for (alpha in 0..4) {
			assertTrue(P2lrtGlRenderer.fixedFunction(1, alpha))
			assertTrue(P2lrtGlRenderer.fixedFunction(2, alpha))
		}
		for (alpha in 1..4) assertFalse(P2lrtGlRenderer.fixedFunction(0, alpha))
		for (blend in 3..17) for (alpha in 0..4) assertFalse(P2lrtGlRenderer.fixedFunction(blend, alpha))
	}

	@Test fun everyBlendPairAndIsolatedGroupDrawsAsTheReference() {
		val runtime = P2lRuntime.load()
		assumeTrue(runtime != null, "The native runtime is not built")
		val host = GlHost.start()
		assumeTrue(host != null, "No OpenGL 3.3 context: ${GlHost.failure}")
		try {
			val pixels = host!!.submit { render(runtime!!) }.get()
			val failures = ArrayList<String>()
			fun check(name: String, x: Int, y: Int, expected: FloatArray, tolerance: Float = TOLERANCE) {
				val actual = FloatArray(4) { (pixels[(y * WIDTH + x) * 4 + it].toInt() and 0xff) / 255f }
				if ((0 until 4).any { abs(actual[it] - expected[it]) > tolerance }) {
					failures += "$name: expected ${expected.format()}, got ${actual.format()}"
				}
			}
			val dest = backdrop
			for (blend in 0..17) for (alpha in 0..4) {
				val (x, y) = cellCenter(blend * 5 + alpha)
				val source = tinted(texel(SOURCE), 0.8f)
				// Fixed-function pairs blend the shaded fragment; the rest composite the mesh's 8-bit layer.
				val layer = if (P2lrtGlRenderer.fixedFunction(blend, alpha)) source else quantize(source)
				check("${BlendMode.entries[blendIndex(blend)]}/${AlphaBlendMode.entries[alphaIndex(alpha)]}", x, y,
					reference(layer, dest, blend, alpha))
			}
			// An isolated multiply/atop group at 0.6 with colors, of a mesh and a half-opaque mesh over its right half.
			run {
				val (x, y) = cellCenter(GROUP)
				val left = quantize(texel(SOURCE))
				val right = quantize(over(tinted(texel(SECOND), 0.5f), texel(SOURCE)))
				for ((name, px, layer) in listOf(Triple("group left", x - 3, left), Triple("group right", x + 3, right))) {
					val src = channels(layer, 0.6f, floatArrayOf(1f, 0.5f, 0.5f), floatArrayOf(0.1f, 0f, 0f))
					check(name, px, y, reference(src, dest, 6, 1))
				}
			}
			// A group masked by a mesh over the left half, then the same inverted.
			run {
				val (x, y) = cellCenter(MASKED)
				check("masked inside", x - 3, y, reference(quantize(texel(SOURCE)), dest, 0, 0))
				check("masked outside", x + 3, y, dest)
				val (ix, iy) = cellCenter(INVERTED)
				check("inverted outside", ix - 3, iy, dest)
				check("inverted inside", ix + 3, iy, reference(quantize(texel(SOURCE)), dest, 0, 0))
			}
			// A plain source-over group draws in place.
			run {
				val (x, y) = cellCenter(PLAIN)
				check("plain group", x, y, over(tinted(texel(SOURCE), 0.8f), dest))
			}
			// Screen of a group holding a group at half opacity.
			run {
				val (x, y) = cellCenter(NESTED)
				val inner = quantize(texel(SOURCE))
				val outer = quantize(reference(tinted(inner, 0.5f), FloatArray(4), 0, 0))
				check("nested groups", x, y, reference(outer, dest, 10, 0))
			}
			// A multiply group whose mesh a mesh masks over the left half: the stencil of the group's layer.
			run {
				val (x, y) = cellCenter(MASKED_MESH)
				check("masked mesh inside", x - 3, y, reference(quantize(texel(SOURCE)), dest, 6, 0))
				check("masked mesh outside", x + 3, y, dest)
			}
			// A half-opaque group holding a screen mesh over another: the mesh's layer composites into the group's.
			run {
				val (x, y) = cellCenter(EXTENDED_INSIDE)
				val layer = quantize(reference(quantize(tinted(texel(SOURCE), 0.8f)), quantize(texel(BACKDROP)), 10, 0))
				check("extended mesh in a group", x, y, reference(tinted(layer, 0.5f), dest, 0, 0))
			}
			if (failures.isNotEmpty()) fail("${failures.size} pixels off:\n" + failures.joinToString("\n"))
		} finally {
			host!!.close()
		}
	}

	private fun render(runtime: P2lRuntime): ByteArray {
		val texture = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, WIDTH, HEIGHT, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
		val depthStencil = GL30.glGenRenderbuffers()
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthStencil)
		GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, WIDTH, HEIGHT)
		val framebuffer = GL30.glGenFramebuffers()
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0)
		GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depthStencil)
		check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE)
		GL11.glViewport(0, 0, WIDTH, HEIGHT)
		GL11.glClearColor(0f, 0f, 0f, 0f)
		GL11.glClearStencil(0)
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_STENCIL_BUFFER_BIT)
		val renderer = P2lrtGlRenderer()
		val buffer = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4)
		try {
			runtime.load(rig()).use { rig ->
				rig.evaluate()
				renderer.setRig(rig, listOf(page()))
				// Canvas pixel (x, y) lands on framebuffer row y, which reads back as row y.
				renderer.draw(rig, WIDTH, HEIGHT, 1f, 0f, 0f)
			}
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
			GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
			check(GL11.glGetError() == GL11.GL_NO_ERROR) { "GL error" }
			return ByteArray(WIDTH * HEIGHT * 4).also { buffer.get(it) }
		} finally {
			MemoryUtil.memFree(buffer)
			renderer.close()
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
			GL30.glDeleteFramebuffers(framebuffer)
			GL30.glDeleteRenderbuffers(depthStencil)
			GL11.glDeleteTextures(texture)
		}
	}

	/** A 64-pixel page of four solid quadrants, straight alpha: backdrop, source, second source, an opaque mask. */
	private fun page(): BufferedImage = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
		for (y in 0 until 64) for (x in 0 until 64) setRGB(x, y, QUADRANTS[(if (y < 32) 0 else 2) + (if (x < 32) 0 else 1)])
	}

	private fun rig(): RigIR {
		val meshes = ArrayList<IrMesh>()
		val root = ArrayList<RenderNode>()
		val parts = ArrayList<IrPart>()
		fun cell(index: Int) = (index % COLUMNS) * CELL to (index / COLUMNS) * CELL
		fun quad(id: String, x0: Int, y0: Int, x1: Int, y1: Int, quadrant: Int, blend: ColorBlend = ColorBlend.NORMAL,
			alpha: AlphaBlend = AlphaBlend.OVER, opacity: Float = 1f, maskedBy: List<String> = emptyList(), visible: Boolean = true): String {
			val u = if (quadrant % 2 == 0) 0.25f else 0.75f
			val v = if (quadrant < 2) 0.25f else 0.75f
			meshes += IrMesh(id, id, null, blend = blend, alphaBlend = alpha, maskedBy = maskedBy, visible = visible, opacity = opacity, page = 0,
				geometry = MeshGeometry(Floats.values(x0.toFloat(), y0.toFloat(), x1.toFloat(), y0.toFloat(), x0.toFloat(), y1.toFloat(), x1.toFloat(), y1.toFloat()),
					Floats.values(u, v, u, v, u, v, u, v), Ints.values(0, 1, 2, 2, 1, 3)), offsets = null)
			return id
		}
		fun inset(id: String, index: Int, quadrant: Int, opacity: Float = 1f, blend: ColorBlend = ColorBlend.NORMAL,
			alpha: AlphaBlend = AlphaBlend.OVER, maskedBy: List<String> = emptyList()): String {
			val (x, y) = cell(index)
			return quad(id, x + 2, y + 2, x + CELL - 2, y + CELL - 2, quadrant, blend, alpha, opacity, maskedBy)
		}
		fun leftMask(id: String, index: Int): String {
			val (x, y) = cell(index)
			return quad(id, x + 2, y + 2, x + CELL / 2, y + CELL - 2, MASK, visible = false)
		}
		fun group(part: String, children: List<RenderNode>, composite: Composite): RenderGroup {
			parts += IrPart(part, part, children.map { if (it is RenderGroup) ChildRef.PartRef(it.part!!) else ChildRef.MeshRef((it as RenderMesh).id) },
				groupMode = GroupMode.ISOLATED)
			return RenderGroup(part, RigIR.DEFAULT_DRAW_ORDER, children, composite = composite)
		}
		root += RenderMesh(quad("backdrop", 0, 0, WIDTH, HEIGHT, BACKDROP))
		for (blend in ColorBlend.entries) for (alpha in AlphaBlend.entries) {
			val index = blend.ordinal * 5 + alpha.ordinal
			root += RenderMesh(inset("m$index", index, SOURCE, 0.8f, blend, alpha))
		}
		run {
			val (x, y) = cell(GROUP)
			val children = listOf(RenderMesh(inset("g1a", GROUP, SOURCE)),
				RenderMesh(quad("g1b", x + CELL / 2, y + 2, x + CELL - 2, y + CELL - 2, SECOND, opacity = 0.5f)))
			root += group("G1", children, Composite(ColorBlend.MULTIPLY, AlphaBlend.ATOP, opacity = 0.6f,
				multiply = Rgb(1f, 0.5f, 0.5f), screen = Rgb(0.1f, 0f, 0f)))
		}
		root += RenderMesh(leftMask("maskMasked", MASKED))
		root += group("G2", listOf(RenderMesh(inset("g2", MASKED, SOURCE))), Composite(maskedBy = listOf("maskMasked")))
		root += RenderMesh(leftMask("maskInverted", INVERTED))
		root += group("G3", listOf(RenderMesh(inset("g3", INVERTED, SOURCE))), Composite(maskedBy = listOf("maskInverted"), invertMask = true))
		root += group("G4", listOf(RenderMesh(inset("g4", PLAIN, SOURCE, 0.8f))), Composite())
		root += group("G5", listOf(group("G5i", listOf(RenderMesh(inset("g5", NESTED, SOURCE))), Composite(opacity = 0.5f))),
			Composite(ColorBlend.SCREEN))
		root += RenderMesh(leftMask("maskMesh", MASKED_MESH))
		root += group("G6", listOf(RenderMesh(inset("g6", MASKED_MESH, SOURCE, maskedBy = listOf("maskMesh")))), Composite(ColorBlend.MULTIPLY))
		root += group("G7", listOf(RenderMesh(inset("g7a", EXTENDED_INSIDE, BACKDROP)),
			RenderMesh(inset("g7b", EXTENDED_INSIDE, SOURCE, 0.8f, ColorBlend.SCREEN))), Composite(opacity = 0.5f))
		return RigIR(canvas = Canvas(WIDTH.toFloat(), HEIGHT.toFloat()), parameters = emptyList(), parts = parts, meshes = meshes,
			renderRoot = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, root))
	}

	private companion object {
		const val CELL = 16
		const val COLUMNS = 10
		const val WIDTH = CELL * COLUMNS
		const val HEIGHT = CELL * 10
		const val BACKDROP = 0
		const val SOURCE = 1
		const val SECOND = 2
		const val MASK = 3
		const val GROUP = 90
		const val MASKED = 91
		const val INVERTED = 92
		const val PLAIN = 93
		const val NESTED = 94
		const val MASKED_MESH = 95
		const val EXTENDED_INSIDE = 96
		/** Three 8-bit steps: layers and the target store 8 bits, which unpremultiplying magnifies at low alpha. */
		const val TOLERANCE = 3f / 255f
		val QUADRANTS = intArrayOf(argb(191, 204, 77, 51), argb(204, 51, 153, 230), argb(255, 230, 230, 26), argb(255, 255, 255, 255))

		fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

		fun cellCenter(index: Int) = (index % COLUMNS) * CELL + CELL / 2 to (index / COLUMNS) * CELL + CELL / 2

		/** The quadrant's texel as the renderer uploads it: premultiplied with rounding, 8 bits. */
		fun texel(quadrant: Int): FloatArray {
			val c = QUADRANTS[quadrant]
			val a = c ushr 24
			fun premultiply(channel: Int) = ((channel * a + 127) / 255) / 255f
			return floatArrayOf(premultiply(c ushr 16 and 0xff), premultiply(c ushr 8 and 0xff), premultiply(c and 0xff), a / 255f)
		}

		/** The backdrop over the cleared target. */
		val backdrop: FloatArray get() = texel(BACKDROP)

		fun tinted(premultiplied: FloatArray, opacity: Float) = FloatArray(4) { premultiplied[it] * opacity }

		fun quantize(color: FloatArray) = FloatArray(4) { (color[it].coerceIn(0f, 1f) * 255f).roundToInt() / 255f }

		fun over(source: FloatArray, destination: FloatArray) = FloatArray(4) { source[it] + destination[it] * (1f - source[3]) }

		/** A layer pixel after a group's opacity and colors, as the composite shader applies them. */
		fun channels(layer: FloatArray, opacity: Float, multiply: FloatArray, screen: FloatArray): FloatArray {
			val a = layer[3] * opacity
			if (a <= 0f) return FloatArray(4)
			return FloatArray(4) { i ->
				if (i == 3) a else {
					val c = (layer[i] * opacity / a).coerceIn(0f, 1f) * multiply[i]
					(c + screen[i] - c * screen[i]) * a
				}
			}
		}

		fun blendIndex(mode: Int) = BlendMode.entries.indexOfFirst { packedColorModeOf(it) == mode }
		fun alphaIndex(mode: Int) = AlphaBlendMode.entries.indexOfFirst { packedAlphaModeOf(it) == mode }

		fun reference(source: FloatArray, destination: FloatArray, blend: Int, alpha: Int): FloatArray =
			compositeReference(source, destination, BlendMode.entries[blendIndex(blend)], AlphaBlendMode.entries[alphaIndex(alpha)])

		fun FloatArray.format() = joinToString(", ", "(", ")") { "%.3f".format(it) }
	}
}
