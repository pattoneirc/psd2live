package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.LayerRaster
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AtlasLayoutTest {
	private val analysis by lazy { PSD2LivePipeline().inspect(Path.of("examples/tml/psd-input/tml.psd")) }

	private fun pixels(png: ByteArray): IntArray {
		val image = ImageIO.read(ByteArrayInputStream(png))
		return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
	}

	private fun pixels(page: AtlasPage) = page.image.getRGB(0, 0, page.image.width, page.image.height, null, 0, page.image.width)

	/** [layer] with its pixels recoloured, at the same size. */
	private fun repainted(layer: ClassifiedLayer, salt: Int): ClassifiedLayer {
		val rgba = layer.source.raster.rgba.copyOf()
		for (i in rgba.indices step 4) if (rgba[i + 3] != 0.toByte()) rgba[i] = (rgba[i] + salt).toByte()
		val source = (WorkspaceSourceLayer.copyOf(layer.source, layer.source.order) as WorkspaceSourceLayer)
			.copy(raster = LayerRaster(layer.source.raster.width, layer.source.raster.height, rgba))
		return layer.copy(source = source)
	}

	/** [layer] grown by [dx] x [dy] transparent pixels on the right and bottom. */
	private fun grown(layer: ClassifiedLayer, dx: Int, dy: Int): ClassifiedLayer {
		val old = layer.source.raster
		val width = old.width + dx; val height = old.height + dy
		val rgba = ByteArray(width * height * 4)
		for (y in 0 until old.height) System.arraycopy(old.rgba, y * old.width * 4, rgba, y * width * 4, old.width * 4)
		val source = (WorkspaceSourceLayer.copyOf(layer.source, layer.source.order) as WorkspaceSourceLayer).copy(
			raster = LayerRaster(width, height, rgba),
			bounds = org.umamo.format.art.LayerBounds(layer.source.bounds.left, layer.source.bounds.top, width, height))
		return layer.copy(source = source)
	}

	@Test fun stripEncodingDecodesToTheCanonicalPixels() {
		val atlas = AtlasLayout.pack(analysis.layers, 1024, 2)
		for (page in atlas.pages) {
			val canonical = page.png
			val preview = page.previewPng
			assertContentEquals(pixels(canonical), pixels(preview))
			assertContentEquals(pixels(page), pixels(preview))
		}
		// Adler-32 of a concatenation, combined from its parts.
		val a = byteArrayOf(1, 2, 3, 4, 5); val b = ByteArray(70_000) { (it * 7).toByte() }
		fun adler(bytes: ByteArray) = java.util.zip.Adler32().apply { update(bytes) }.value.toInt()
		assertEquals(adler(a + b), AtlasPagePng.combineAdler(adler(a), adler(b), b.size.toLong()))
	}

	@Test fun packFromAPreviousAtlasKeepsUnchangedPagesAndRedrawsASameSizeTileInPlace() {
		val atlas = AtlasLayout.pack(analysis.layers, 1024, 2)
		assertTrue(atlas.pages.size > 1)
		val target = analysis.layers.filter { it.opaquePixels > 0 }.minBy { it.source.raster.width * it.source.raster.height }
		val layers = analysis.layers.map { if (it === target) repainted(it, 40) else it }
		atlas.pages.forEach { it.previewPng }
		val repacked = AtlasLayout.pack(layers, 1024, 2, previous = atlas)
		assertEquals(atlas.placementByLayerId, repacked.placementByLayerId)
		val placement = atlas.placementByLayerId.getValue(target.source.id.raw)
		for (index in atlas.pages.indices) {
			if (index == placement.page) assertNotSame(atlas.pages[index], repacked.pages[index]) else assertSame(atlas.pages[index], repacked.pages[index])
		}
		// Only the strips the repainted tile crosses are encoded again.
		val before = atlas.pages[placement.page].strips.strips; val after = repacked.pages[placement.page].strips.strips
		val rows = AtlasPagePng.STRIP_ROWS
		for (index in after.indices) {
			val crosses = index * rows < placement.y + placement.height && placement.y < (index + 1) * rows
			if (crosses) assertNotSame(before[index], after[index]) else assertSame(before[index], after[index])
		}
		// Same pixels as a fresh canonical pack, and the reused strips still decode to them.
		val fresh = AtlasLayout.pack(layers, 1024, 2)
		assertEquals(fresh.placementByLayerId, repacked.placementByLayerId)
		for (index in fresh.pages.indices) {
			assertContentEquals(pixels(fresh.pages[index]), pixels(repacked.pages[index]))
			assertContentEquals(pixels(fresh.pages[index]), pixels(repacked.pages[index].previewPng))
		}
		// An unchanged document packs to the very same cached pages.
		val again = AtlasLayout.pack(layers, 1024, 2)
		for (index in fresh.pages.indices) assertSame(fresh.pages[index], again.pages[index])
	}

	@Test fun aResizedTileReflowsToTheCanonicalLayoutAndDerivedStripsStillDecode() {
		val atlas = AtlasLayout.pack(analysis.layers, 1024, 2)
		atlas.pages.forEach { it.previewPng }
		val target = analysis.layers.filter { it.opaquePixels > 0 }.sortedBy { it.source.raster.width * it.source.raster.height }[2]
		val layers = analysis.layers.map { if (it === target) grown(it, 7, 5) else it }
		val repacked = AtlasLayout.pack(layers, 1024, 2, previous = atlas)
		val fresh = AtlasLayout.pack(layers, 1024, 2)
		assertEquals(fresh.placementByLayerId, repacked.placementByLayerId)
		assertEquals(target.source.raster.width + 7, repacked.placementByLayerId.getValue(target.source.id.raw).width)
		for (index in fresh.pages.indices) {
			assertContentEquals(pixels(fresh.pages[index]), pixels(repacked.pages[index]))
			assertContentEquals(pixels(fresh.pages[index].png), pixels(repacked.pages[index].previewPng))
		}
		// Each page shows exactly its tiles' pixels.
		val byId = layers.associateBy { it.source.id.raw }
		for ((layerId, placement) in repacked.placementByLayerId) {
			val raster = byId.getValue(layerId).source.raster
			val page = repacked.pages[placement.page]
			val expected = PreviewRenderer.rasterImage(raster.width, raster.height, raster.rgba).getRGB(0, 0, raster.width, raster.height, null, 0, raster.width)
			assertContentEquals(expected, page.image.getRGB(placement.x, placement.y, raster.width, raster.height, null, 0, raster.width))
		}
	}
}
