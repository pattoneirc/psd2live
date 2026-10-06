package io.github.psd2live.core

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LayerSpaceTest {
	private fun close(expected: Float, actual: Float) = assertEquals(expected, actual, 1e-4f)

	@Test
	fun boundsSpaceMapsCanvasToRasterOneToOne() {
		val space = LayerSpace.fromBounds(LayerBounds(10, 20, 30, 40), LayerRaster(30, 40, ByteArray(30 * 40 * 4)))
		assertEquals(LayerSpace(10f, 20f, 30f, 40f, 30, 40), space)
		assertEquals(1f, space.scaleX)
		assertEquals(1f, space.scaleY)
		assertEquals(5f to 7f, space.canvasToRaster(15f, 27f))
		assertEquals(15f to 27f, space.rasterToCanvas(5f, 7f))
		assertEquals(Bounds(10f, 20f, 40f, 60f), space.canvasBounds())
	}

	@Test
	fun nonUniformScaleRoundTrips() {
		// 2x horizontally and 0.5x vertically over a fractional rectangle.
		val space = LayerSpace(left = 12.5f, top = -3.25f, width = 64f, height = 200f, rasterWidth = 128, rasterHeight = 100)
		close(2f, space.scaleX)
		close(0.5f, space.scaleY)
		val (rx, ry) = space.canvasToRaster(44.5f, 96.75f)
		close(64f, rx)
		close(50f, ry)
		for ((x, y) in listOf(12.5f to -3.25f, 76.5f to 196.75f, 30.125f to 10.5f, -4f to 300f)) {
			val raster = space.canvasToRaster(x, y)
			val back = space.rasterToCanvas(raster.first, raster.second)
			close(x, back.first)
			close(y, back.second)
		}
	}

	@Test
	fun layerUnitsSpanTheRectangle() {
		val space = LayerSpace(100f, 50f, 40f, 10f, 400, 25)
		assertEquals(0f to 0f, space.canvasToLayerUnit(100f, 50f))
		assertEquals(1f to 1f, space.canvasToLayerUnit(140f, 60f))
		assertEquals(0.25f to 0.5f, space.canvasToLayerUnit(110f, 55f))
		assertEquals(110f to 55f, space.layerUnitToCanvas(0.25f, 0.5f))
		val unit = space.canvasToLayerUnit(123.4f, 51.2f)
		val back = space.layerUnitToCanvas(unit.first, unit.second)
		close(123.4f, back.first)
		close(51.2f, back.second)
	}

	@Test
	fun degenerateRectangleKeepsFiniteScale() {
		val space = LayerSpace(5f, 6f, 0f, 0f, 0, 0)
		assertEquals(1f, space.scaleX)
		assertEquals(1f, space.scaleY)
		assertEquals(0f to 0f, space.canvasToLayerUnit(9f, 9f))
		assertFailsWith<IllegalArgumentException> { LayerSpace(0f, 0f, -1f, 1f, 1, 1) }
		assertFailsWith<IllegalArgumentException> { LayerSpace(Float.NaN, 0f, 1f, 1f, 1, 1) }
	}
}
