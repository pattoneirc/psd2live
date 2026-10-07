package io.github.psd2live.render

import io.github.psd2live.core.CanvasViewport
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The texture atlas page's GPU scene: tiles as textured rectangles of their rasters, clipped to their cells. */
class GpuAtlasSceneTest {
	/** A 2 x 2 raster: red, green / blue, half-transparent white; straight alpha, as layer rasters are. */
	private val raster = RasterTexture(2, 2, byteArrayOf(
		-1, 0, 0, -1, 0, -1, 0, -1,
		0, 0, -1, -1, -1, -1, -1, -128,
	))

	@Test fun tilesSampleTheirRastersPremultipliedAndKeepToTheirCells() {
		val host = GlHost.start()
		assumeTrue(host != null, "No OpenGL 3.3 context: ${GlHost.failure}")
		try {
			val renderer = host!!.submit { GlCanvasRenderer() }.get()
			// Page pixel (x, y) is world (x, -y) and lands on screen (x, y).
			val viewport = CanvasViewport(1.0, 0.0, 0.0, 40f, 40f)
			fun render(vararg items: OverlayItem): ByteArray {
				val scene = AtlasScene(40, 40, viewport, OverlayScene(items.toList()))
				return requireNotNull(host.submit { renderer.render("atlas", scene) }.get().readPixels())
			}
			fun at(pixels: ByteArray, x: Int, y: Int) = (0..3).map { pixels[(y * 40 + x) * 4 + it].toInt() and 0xff }
			fun assertNear(expected: List<Int>, actual: List<Int>) =
				assertTrue(expected.zip(actual).all { (e, v) -> kotlin.math.abs(e - v) <= 1 }, "expected $expected, was $actual")

			// The raster stretched over page (0, 0)..(20, 20), texel by texel.
			val whole = render(TextureQuad(raster, 0f, 0f, 20f, -20f, nearest = true))
			assertEquals(listOf(255, 0, 0, 255), at(whole, 4, 4))
			assertEquals(listOf(0, 255, 0, 255), at(whole, 15, 4))
			assertEquals(listOf(0, 0, 255, 255), at(whole, 4, 15))
			assertEquals(listOf(128, 128, 128, 128), at(whole, 15, 15), "half-transparent white, premultiplied")
			assertEquals(listOf(0, 0, 0, 0), at(whole, 30, 30), "nothing outside the tile")

			// Only the left half's cells: the right half stays empty.
			val clipped = render(TextureQuad(raster, 0f, 0f, 20f, -20f, nearest = true, clip = floatArrayOf(0f, 0f, 10f, -20f)))
			assertEquals(listOf(255, 0, 0, 255), at(clipped, 4, 4))
			assertEquals(listOf(0, 0, 0, 0), at(clipped, 15, 4))

			// Moved and scaled to page (20, 10)..(40, 30), at half opacity.
			val lifted = render(TextureQuad(raster, 20f, -10f, 40f, -30f, alpha = 0.5f, nearest = true))
			// Half of 255 rounds either way on the GPU.
			assertNear(listOf(128, 0, 0, 128), at(lifted, 24, 14))
			assertEquals(listOf(0, 0, 0, 0), at(lifted, 4, 4))

			// A wireframe line over a tile.
			val wired = render(TextureQuad(raster, 0f, 0f, 20f, -20f, nearest = true),
				LineBatch(0xFFFFFFFF.toInt(), 1f, floatArrayOf(0f, -2.5f, 40f, -2.5f)))
			assertEquals(listOf(255, 255, 255, 255), at(wired, 30, 2))

			host.submit { renderer.close() }.get()
		} finally {
			host?.close()
		}
	}
}
