package io.github.psd2live.core

import io.github.psd2live.project.TextureOverride
import io.github.psd2live.project.TexturePin
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How [AtlasLayout] sizes tiles within an [AtlasBudget]: density, the common fit, locks, pins and overflow. */
class AtlasBudgetTest {
	private fun layer(id: String, width: Int, height: Int, rasterWidth: Int = width, rasterHeight: Int = height, order: Int = 0): ClassifiedLayer {
		val rgba = ByteArray(rasterWidth * rasterHeight * 4) { if (it % 4 == 3) -1 else (it * 7 + order).toByte() }
		val source = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order, LayerBounds(order * 3, order * 2, width, height),
			1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(rasterWidth, rasterHeight, rgba), null, null, false)
		return CharacterAnalyzer.classify(source, PipelineConfig())
	}

	private val layers = (0 until 12).map { layer("l$it", 40 + it * 13, 30 + (it * 29) % 90, order = it) }

	private fun assertDisjoint(atlas: PackedAtlas, padding: Int) {
		val tiles = atlas.placementByLayerId.values.toList()
		for (i in tiles.indices) for (j in i + 1 until tiles.size) {
			val a = tiles[i]; val b = tiles[j]
			if (a.page != b.page) continue
			val apart = a.x + a.width + padding <= b.x || b.x + b.width + padding <= a.x ||
				a.y + a.height + padding <= b.y || b.y + b.height + padding <= a.y
			assertTrue(apart, "tiles $a and $b overlap")
		}
		for (tile in tiles) {
			val side = atlas.pages[tile.page].image.width
			assertTrue(tile.x >= 0 && tile.y >= 0 && tile.x + tile.width <= side && tile.y + tile.height <= side, "tile $tile leaves its page")
		}
	}

	@Test fun areaResamplingUndoesABlockUpscale() {
		val source = layers[2].source.raster
		val factor = 4
		val big = ByteArray(source.width * factor * source.height * factor * 4)
		for (y in 0 until source.height * factor) for (x in 0 until source.width * factor)
			System.arraycopy(source.rgba, ((y / factor) * source.width + x / factor) * 4, big, (y * source.width * factor + x) * 4, 4)
		val back = io.github.psd2live.format.compile.RasterResample.resize(big, source.width * factor, source.height * factor, source.width, source.height)
		kotlin.test.assertContentEquals(source.rgba, back)
	}

	@Test fun defaultsReproduceTheLegacyLayout() {
		val legacy = AtlasLayout.pack(layers, 256, 2)
		val budgeted = AtlasLayout.pack(layers, AtlasBudget(256, 8, 2), emptyMap())
		assertEquals(legacy.placementByLayerId, budgeted.placementByLayerId)
		assertEquals(1f, budgeted.fit)
		assertTrue(budgeted.notices.isEmpty())
		assertTrue(budgeted.placementByLayerId.values.all { it.scaleX == 1f && it.scaleY == 1f })
		val config = PipelineConfig(atlasSize = 256)
		assertEquals(legacy.placementByLayerId, AtlasLayout.pack(layers, config).placementByLayerId)
	}

	@Test fun densityMultipliesTheRasterAndADenseRasterKeepsItsPixels() {
		val dense = layer("dense", 16, 16, 128, 128)
		assertTrue(dense.source is CanvasDensityLayer)
		val atlas = AtlasLayout.pack(listOf(dense, layers[0]), AtlasBudget(512, 8, 2), mapOf(layers[0].source.id.raw to TextureOverride(density = 2f)))
		val tile = atlas.placementByLayerId.getValue("dense")
		assertEquals(128, tile.width); assertEquals(1f, tile.scaleX)
		val doubled = atlas.placementByLayerId.getValue(layers[0].source.id.raw)
		assertEquals(layers[0].source.raster.width * 2, doubled.width); assertEquals(2f, doubled.scaleX)
	}

	@Test fun oneFitScalesEveryUnlockedTileToTheBudget() {
		val budget = AtlasBudget(256, 1, 2)
		val locked = layers[3].source.id.raw
		val atlas = AtlasLayout.pack(layers, budget, mapOf(locked to TextureOverride(lock = true)))
		assertEquals(1, atlas.pages.size)
		assertTrue(atlas.fit < 1f && atlas.fit > 0f)
		assertTrue(atlas.notices.isNotEmpty())
		for (layer in layers) {
			val tile = atlas.placementByLayerId.getValue(layer.source.id.raw)
			if (layer.source.id.raw == locked) assertEquals(layer.source.raster.width, tile.width)
			else assertEquals(maxOf(1, Math.round(layer.source.raster.width * atlas.fit.toDouble()).toInt()), tile.width)
		}
		assertDisjoint(atlas, 2)
		// The next step up does not fit, so the fit is the largest that does.
		val again = AtlasLayout.pack(layers, budget, mapOf(locked to TextureOverride(lock = true)))
		assertEquals(atlas.placementByLayerId, again.placementByLayerId)
		assertEquals(atlas.fit, again.fit)
	}

	@Test fun pinnedTilesKeepTheirSpotAndTheShelfGoesAround() {
		val pinned = layers[5].source.id.raw
		val atlas = AtlasLayout.pack(layers, AtlasBudget(512, 8, 2), mapOf(pinned to TextureOverride(pin = TexturePin(0, 100, 60))))
		val tile = atlas.placementByLayerId.getValue(pinned)
		assertEquals(Triple(0, 100, 60), Triple(tile.page, tile.x, tile.y))
		assertDisjoint(atlas, 2)
	}

	@Test fun lockedTilesBeyondTheBudgetAreReportedNotSilentlyShrunk() {
		// 16 canvas units, so the page does not grow for it, but 600 locked texture pixels.
		val big = layer("big", 16, 16, 600, 600)
		val atlas = AtlasLayout.pack(listOf(big) + layers, AtlasBudget(256, 1, 2), mapOf("big" to TextureOverride(lock = true)))
		assertTrue(atlas.notices.any { "big" in it })
		assertDisjoint(atlas, 2)
	}

	@Test fun anOutOfBudgetPinIsReported() {
		val pinned = layers[0].source.id.raw
		val atlas = AtlasLayout.pack(layers, AtlasBudget(512, 2, 2), mapOf(pinned to TextureOverride(pin = TexturePin(5, 0, 0))))
		assertTrue(atlas.notices.any { pinned in it })
		assertTrue(atlas.pages.size <= 2)
		assertDisjoint(atlas, 2)
	}
}
