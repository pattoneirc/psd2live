package io.github.psd2live.core

import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.*
import kotlin.random.Random
import kotlin.test.*

/** Padding a repainted layer to its pinned rectangle: the same bytes as drawing it with Java2D, memoized by raster. */
class RigGenerationSourcePaddingTest {
	private fun layer(bounds: LayerBounds, raster: LayerRaster, rect: LayerCanvasRect? = null) = WorkspaceSourceLayer(
		LayerId("art"), "art", "", SourceLayerKind.Raster, true, 0, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		raster, null, null, false, rect)

	/** Every alpha with arbitrary colour, transparent pixels with colour too, so source-over's rounding shows. */
	private fun noise(width: Int, height: Int, seed: Int) = LayerRaster(width, height, Random(seed).nextBytes(width * height * 4).also { rgba ->
		for (i in 0 until width * height) when (i % 7) { 0 -> rgba[i * 4 + 3] = 0; 1 -> rgba[i * 4 + 3] = -1 }
	})

	@Test fun `padding matches the Java2D draw byte for byte`() {
		assertNotNull(RigGenerationSource.SourceOver.table, "the copy path must be in use on this JVM")
		// Every (alpha, value) pair once, then layers padded on each side and on all sides.
		val all = LayerRaster(256, 256, ByteArray(256 * 256 * 4).also { rgba ->
			for (a in 0 until 256) for (v in 0 until 256) {
				val o = (a * 256 + v) * 4
				rgba[o] = v.toByte(); rgba[o + 1] = (255 - v).toByte(); rgba[o + 2] = (v * 7).toByte(); rgba[o + 3] = a.toByte()
			}
		})
		val cases = listOf(
			layer(LayerBounds(4, 6, 256, 256), all) to LayerBounds(0, 0, 270, 263),
			layer(LayerBounds(10, 12, 33, 17), noise(33, 17, 1)) to LayerBounds(5, 12, 38, 17),
			layer(LayerBounds(10, 12, 33, 17), noise(33, 17, 2)) to LayerBounds(10, 2, 33, 40),
			layer(LayerBounds(-3, 8, 21, 9), noise(21, 9, 3)) to LayerBounds(-7, 0, 40, 30),
		)
		for ((current, previous) in cases) {
			val padded = RigGenerationSource.padded(current, previous)
			assertEquals(previous.let { p ->
				val l = minOf(p.left, current.bounds.left); val t = minOf(p.top, current.bounds.top)
				LayerBounds(l, t, maxOf(p.left + p.width, current.bounds.left + current.bounds.width) - l,
					maxOf(p.top + p.height, current.bounds.top + current.bounds.height) - t)
			}, padded.bounds)
			val legacy = RigGenerationSource.legacyPadded(current, padded.bounds)
			assertEquals(legacy.width, padded.raster.width)
			assertEquals(legacy.height, padded.raster.height)
			assertContentEquals(legacy.rgba, padded.raster.rgba)
		}
	}

	@Test fun `a layer inside its pinned rectangle is itself`() {
		val current = layer(LayerBounds(10, 10, 8, 8), noise(8, 8, 4))
		assertSame(current, RigGenerationSource.padded(current, LayerBounds(12, 12, 4, 4)))
	}

	@Test fun `the same raster pads to the same array`() {
		val raster = noise(20, 14, 5)
		val a = RigGenerationSource.padded(layer(LayerBounds(4, 4, 20, 14), raster), LayerBounds(0, 0, 30, 20))
		// A new layer object over the same raster, as each rebuild makes: the padded array is the one already made.
		val b = RigGenerationSource.padded(layer(LayerBounds(4, 4, 20, 14), raster), LayerBounds(0, 0, 30, 20))
		assertSame(a.raster.rgba, b.raster.rgba)
		val other = RigGenerationSource.padded(layer(LayerBounds(4, 4, 20, 14), raster), LayerBounds(0, 0, 40, 20))
		assertNotSame(a.raster.rgba, other.raster.rgba)
		assertEquals(40, other.raster.width)
		// Equal pixels in a new array are padded afresh, to equal bytes.
		val copy = RigGenerationSource.padded(layer(LayerBounds(4, 4, 20, 14), LayerRaster(20, 14, raster.rgba.copyOf())), LayerBounds(0, 0, 30, 20))
		assertNotSame(a.raster.rgba, copy.raster.rgba)
		assertContentEquals(a.raster.rgba, copy.raster.rgba)
	}

	@Test fun `a dense layer pads at its density and is memoized too`() {
		// A 2x raster over a 10 x 6 rectangle.
		val raster = noise(20, 12, 6)
		val dense = layer(LayerBounds(5, 5, 10, 6), raster, LayerCanvasRect(5f, 5f, 10f, 6f))
		assertTrue(CanvasDensity.dense(dense))
		val a = RigGenerationSource.padded(dense, LayerBounds(0, 5, 15, 6))
		assertEquals(LayerBounds(0, 5, 15, 6), a.bounds)
		assertEquals(30, a.raster.width); assertEquals(12, a.raster.height)
		// The original texels sit at their spot: alpha everywhere, colour where opaque.
		for (y in 0 until 12) for (x in 0 until 20) {
			val from = (y * 20 + x) * 4; val to = (y * 30 + x + 10) * 4
			assertEquals(raster.rgba[from + 3], a.raster.rgba[to + 3])
			if (raster.rgba[from + 3] == (-1).toByte()) for (c in 0 until 3) assertEquals(raster.rgba[from + c], a.raster.rgba[to + c])
		}
		val b = RigGenerationSource.padded(layer(LayerBounds(5, 5, 10, 6), raster, LayerCanvasRect(5f, 5f, 10f, 6f)), LayerBounds(0, 5, 15, 6))
		assertSame(a.raster.rgba, b.raster.rgba)
	}
}
