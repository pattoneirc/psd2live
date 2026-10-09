package io.github.psd2live.core

import io.github.psd2live.project.AtlasArrangement
import io.github.psd2live.project.AtlasArrangementCodec
import io.github.psd2live.project.TextureOverride
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A stored atlas arrangement: kept spots, free space for the rest, and the compact arrangement by mesh footprints. */
class AtlasArrangeTest {
	private fun layer(id: String, width: Int, height: Int, order: Int, color: Int = 0x40 + order * 16): ClassifiedLayer {
		val rgba = ByteArray(width * height * 4) { if (it % 4 == 3) -1 else color.toByte() }
		val source = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order, LayerBounds(order * 3, order * 2, width, height),
			1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
		return CharacterAnalyzer.classify(source, PipelineConfig())
	}

	private val layers = (0 until 10).map { layer("l$it", 40 + it * 11, 30 + (it * 23) % 70, it) }

	private fun pixels(atlas: PackedAtlas) = atlas.pages.map { page -> page.image.getRGB(0, 0, page.image.width, page.image.height, null, 0, page.image.width) }

	@Test fun aFrozenLayoutBuildsTheAutomaticOneExactly() {
		val config = PipelineConfig(atlasSize = 256)
		val auto = AtlasLayout.pack(layers, config)
		val frozen = AtlasLayout.frozen(auto)
		// Through the document setting, as a reopened project reads it.
		val stored = assertNotNull(AtlasArrangementCodec.decode(AtlasArrangementCodec.with(kotlinx.serialization.json.JsonObject(emptyMap()), frozen)))
		assertEquals(frozen, stored)
		val kept = AtlasLayout.pack(layers, config.copy(atlasArrangement = stored))
		assertEquals(auto.placementByLayerId, kept.placementByLayerId)
		assertEquals(auto.pages.size, kept.pages.size)
		pixels(auto).zip(pixels(kept)).forEach { (a, b) -> assertContentEquals(a, b) }
		assertTrue(kept.arranged && !auto.arranged)
	}

	@Test fun aKeptLayoutMovesOnlyTheTilesThatNoLongerFit() {
		val config = PipelineConfig(atlasSize = 512)
		val frozen = AtlasLayout.frozen(AtlasLayout.pack(layers, config))
		val grown = layers[3].source.id.raw
		// The grown tile overlaps its neighbour where it stands, and a new layer has no spot yet.
		val added = layer("new", 50, 40, 20)
		val kept = AtlasLayout.pack(layers + added, config.copy(atlasArrangement = frozen,
			textureOverrides = mapOf(grown to TextureOverride(density = 2f))))
		val moved = kept.placementByLayerId.filter { (id, at) -> frozen.tiles[id]?.let { it.x != at.x || it.y != at.y || it.page != at.page } != false }.keys
		assertTrue("new" in moved)
		assertTrue(moved.size <= layers.size / 2, "only the grown tile, what it now overlaps and the new one move: $moved")
		// A new layer and a tile that lost its stored spot fill free space quietly.
		assertTrue(kept.notices.none { "free space" in it || grown in it })
		val tiles = kept.placementByLayerId.values.toList()
		for (i in tiles.indices) for (j in i + 1 until tiles.size) {
			val a = tiles[i]; val b = tiles[j]
			if (a.page != b.page) continue
			assertTrue(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y, "$a overlaps $b")
		}
	}

	/** A right triangle over half of a [size] square raster: the lower-left half when [lower], else the upper-right. */
	private fun triangle(size: Int, lower: Boolean) = if (lower) floatArrayOf(0f, 0f, 0f, size.toFloat(), size.toFloat(), size.toFloat())
		else floatArrayOf(0f, 0f, size.toFloat(), 0f, size.toFloat(), size.toFloat())

	@Test fun meshFootprintsNestTilesAndEachWritesOnlyItsOwnCells() {
		val size = 120
		val a = layer("a", size, size, 0, color = 0x11); val b = layer("b", size, size, 1, color = 0x77)
		val footprints = mapOf(
			"a" to assertNotNull(AtlasArrange.footprint(size, size, 4, listOf(triangle(size, lower = true)))),
			"b" to assertNotNull(AtlasArrange.footprint(size, size, 4, listOf(triangle(size, lower = false)))),
		)
		// The meshes cover opposite halves of their squares, so the squares nest instead of standing side by side.
		val config = PipelineConfig(atlasBudget = AtlasBudget(256, 1, 2))
		val arranged = assertNotNull(AtlasLayout.arrange(listOf(a, b), config, footprints, null))
		assertEquals(AtlasArrangement.FIT_STEPS, arranged.fitStep)
		val atlas = AtlasLayout.pack(listOf(a, b), config.copy(atlasArrangement = arranged))
		val pa = atlas.placementByLayerId.getValue("a"); val pb = atlas.placementByLayerId.getValue("b")
		val overlap = pa.x < pb.x + pb.width && pb.x < pa.x + pa.width && pa.y < pb.y + pb.height && pb.y < pa.y + pa.height
		assertTrue(overlap, "the rectangles of nested halves overlap: $pa $pb")
		assertEquals(setOf("a", "b"), atlas.footprints.keys)
		// Each tile's own corner shows its own pixels: a's lower-left, b's upper-right.
		val page = atlas.pages[pa.page].image
		assertEquals(0x11, page.getRGB(pa.x + 2, pa.y + pa.height - 3) and 0xff)
		assertEquals(0x77, page.getRGB(pb.x + pb.width - 3, pb.y + 2) and 0xff)
		// Kept on the next build, exactly.
		val again = AtlasLayout.pack(listOf(a, b), config.copy(atlasArrangement = arranged))
		assertEquals(atlas.placementByLayerId, again.placementByLayerId)
		assertContentEquals(pixels(atlas).single(), pixels(again).single())
		// By rectangles the same two squares need the page side by side or stacked, never overlapping.
		val rect = assertNotNull(AtlasLayout.arrange(listOf(a, b), config, emptyMap(), null))
		val ra = rect.tiles.getValue("a"); val rb = rect.tiles.getValue("b")
		assertTrue(ra.x + size <= rb.x || rb.x + size <= ra.x || ra.y + size <= rb.y || rb.y + size <= ra.y)
	}

	@Test fun aKeptLayoutShrinksATileNoPageHolds() {
		val config = PipelineConfig(atlasSize = 512)
		val frozen = AtlasLayout.frozen(AtlasLayout.pack(layers, config))
		// A dense raster whose canvas extent fits the page but whose pixels do not, added after the arrangement.
		val dense = layer("dense", 300, 300, 20)
		val kept = AtlasLayout.pack(layers + dense, config.copy(atlasArrangement = frozen,
			textureOverrides = mapOf("dense" to TextureOverride(density = 4f))))
		val at = kept.placementByLayerId.getValue("dense")
		assertTrue(at.width <= 512 - 2 * config.effectiveAtlasBudget().padding && at.width == at.height, "$at")
		assertEquals(frozen.tiles.keys, kept.placementByLayerId.keys - "dense")
	}

	@Test fun anArrangementThatCannotFitShrinksTheTilesTogether() {
		val config = PipelineConfig(atlasBudget = AtlasBudget(256, 1, 2))
		val arranged = assertNotNull(AtlasLayout.arrange(layers, config, emptyMap(), null))
		assertTrue(arranged.fitStep < AtlasArrangement.FIT_STEPS)
		val atlas = AtlasLayout.pack(layers, config.copy(atlasArrangement = arranged))
		assertEquals(1, atlas.pages.size)
		assertTrue(atlas.notices.none { "free space" in it })
		assertEquals(arranged.fit.toFloat(), atlas.fit)
	}
}
