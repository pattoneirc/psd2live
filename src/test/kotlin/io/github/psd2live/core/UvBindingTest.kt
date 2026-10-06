package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.targets.cubism.PuppetIr
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UvBindingTest {
	@Test fun repackingLeavesTheUnboundRigAndMovesOnlyTheBoundUvs() {
		val preview = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val analysis = preview.analysis
		val small = AtlasPacker.pack(analysis.layers, 1024, 2)
		val large = AtlasPacker.pack(analysis.layers, 4096, 9)
		assertNotEquals(small.placementByLayerId, large.placementByLayerId)
		assertNotEquals(small.pages.size, large.pages.size)

		val packedSmall = RigBuilder.build(analysis, small, preview.config)
		val packedLarge = RigBuilder.build(analysis, large, preview.config)
		val unbound = assertNotNull(packedSmall.unbound)
		assertEquals(ContentHash.of(PuppetIr.toIr(unbound)), ContentHash.of(PuppetIr.toIr(assertNotNull(packedLarge.unbound))))
		assertTrue(unbound.atlas.pages.isEmpty() && unbound.atlas.tiles.all { it.placement == null })

		val layers = analysis.layers.associateBy { it.source.id.raw }
		var moved = 0
		for ((bound, atlas) in listOf(packedSmall to small, packedLarge to large)) {
			for (drawable in bound.puppet.drawables) {
				val offsets = unbound.drawables.single { it.id == drawable.id }.mesh!!
				val layer = layers.getValue(bound.layerIdByDrawableId.getValue(drawable.id.raw))
				val placement = atlas.placementByLayerId.getValue(layer.source.id.raw)
				assertEquals(placement.page, drawable.texturePage)
				assertContentEquals(offsets.positions, drawable.mesh!!.positions)
				assertContentEquals(LayerTexture.packed(layer, placement, atlas).bind(offsets.uvs), drawable.mesh!!.uvs)
			}
		}
		for (drawable in packedSmall.puppet.drawables) {
			if (!drawable.mesh!!.uvs.contentEquals(packedLarge.puppet.drawables.single { it.id == drawable.id }.mesh!!.uvs)) moved++
		}
		assertTrue(moved > 0)
		// Rebinding the other packing's unbound rig reproduces this packing's build exactly.
		assertEquals(ContentHash.of(PuppetIr.toIr(packedSmall.puppet)),
			ContentHash.of(PuppetIr.toIr(UvBinding.bind(assertNotNull(packedLarge.unbound), analysis, small).puppet)))
	}

	@Test fun layerTextureRoundTripsCanvasThroughAnAxisAlignedSlice() {
		val texture = LayerTexture.packed(org.umamo.format.art.LayerBounds(30, 40, 20, 10), AtlasPlacement(1, 64, 128, 40, 20, 2f, 2f), 512, 256)
		val uv = texture.uvOfCanvas(35f, 45f)
		assertEquals((64f + 5f * 2f) / 512f, uv[0])
		assertEquals((128f + 5f * 2f) / 256f, uv[1])
		assertContentEquals(floatArrayOf(35f, 45f), texture.canvasOfUv(uv[0], uv[1]))
		assertContentEquals(uv, texture.uvOfOffset(5f, 5f))
		assertEquals(1, texture.page)
	}
}
