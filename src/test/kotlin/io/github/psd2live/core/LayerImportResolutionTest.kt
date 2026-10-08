package io.github.psd2live.core

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.canvasRect
import io.github.psd2live.project.storedCanvasRect
import io.github.psd2live.targets.cubism.Cmo3Target
import kotlinx.coroutines.runBlocking
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.raster.RasterImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Imported rasters keep their pixels; only their canvas rectangle says how large they are on the canvas. */
class LayerImportResolutionTest {
	/** A disc of radius [size]/2 at [size] pixels, coloured by position. */
	private fun disc(size: Int): RasterImage = RasterImage(size, size, ByteArray(size * size * 4).also { rgba ->
		val r = size / 2f
		for (y in 0 until size) for (x in 0 until size) {
			val dx = x + 0.5f - r; val dy = y + 0.5f - r
			if (dx * dx + dy * dy > r * r * 0.81f) continue
			val o = (y * size + x) * 4
			rgba[o] = (40 + 160 * x / size).toByte(); rgba[o + 1] = 60; rgba[o + 2] = (200 - 120 * y / size).toByte(); rgba[o + 3] = -1
		}
	})

	@Test fun anImageLargerThanTheCanvasIsPlacedFittedWithEveryPixel() {
		val image = RasterImage(400, 200, ByteArray(400 * 200 * 4) { -1 })
		val layer = LayerImport.placedLayer(image, 100, 80, "wide")
		assertEquals(400, layer.raster.width); assertEquals(200, layer.raster.height)
		assertSame(image.rgba, layer.raster.rgba)
		assertEquals(LayerCanvasRect(0f, 15f, 100f, 50f), layer.canvasRect())
		assertEquals(LayerBounds(0, 15, 100, 50), layer.bounds)
		assertNull(layer.storedCanvasRect)
		assertEquals(4f, LayerSpace.of(layer).scaleX)

		// A fractional fit keeps the rectangle, inside whole-unit bounds.
		val odd = LayerImport.placedLayer(RasterImage(300, 200, ByteArray(300 * 200 * 4) { -1 }), 64, 64, "odd")
		val rect = odd.canvasRect()
		assertEquals(64f, rect.width); assertTrue(abs(rect.height - 64f * 200 / 300) < 1e-3f)
		assertTrue(rect.within(odd.bounds) && odd.storedCanvasRect != null)
		assertEquals(300, odd.raster.width)
	}

	@Test fun anImageThatFitsIsPlacedAsBefore() {
		val image = disc(10)
		val layer = LayerImport.placedLayer(image, 64, 32, "disc")
		val trimmedWidth = layer.raster.width
		assertEquals(LayerBounds((64 - trimmedWidth) / 2, (32 - layer.raster.height) / 2, trimmedWidth, layer.raster.height), layer.bounds)
		assertNull(layer.storedCanvasRect)
	}

	@Test fun absurdSizesAreRejectedByName() {
		val tooDense = assertFailsWith<IllegalArgumentException> { LayerSizeBudget.require(4096, 4096, LayerCanvasRect(0f, 0f, 4f, 4f)) }
		assertTrue("density" in tooDense.message!!)
		val tooLarge = assertFailsWith<IllegalArgumentException> { LayerSizeBudget.require(64, 64, LayerCanvasRect(0.5f, 0f, 4096f, 4096f)) }
		assertTrue("canvas resolution" in tooLarge.message!!)
		assertFailsWith<IllegalArgumentException> { LayerSizeBudget.require(64, 64, LayerCanvasRect(0f, 0f, 0.1f, 8f)) }
		LayerSizeBudget.require(1024, 1024, LayerCanvasRect(3.25f, 7f, 32f, 32f))
		val (bounds, rect) = LayerSizeBudget.enclosing(LayerCanvasRect(3.25f, 7f, 32f, 32.0001f))
		assertEquals(LayerBounds(3, 7, 33, 32), bounds); assertEquals(LayerCanvasRect(3.25f, 7f, 32f, 32f), rect)
	}

	private fun layer(id: String, order: Int, bounds: LayerBounds, raster: LayerRaster) = WorkspaceSourceLayer(
		LayerId(id), id, "", SourceLayerKind.Raster, true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		raster, null, null, false)

	private fun document(pupil: LayerRaster): WorkspaceDocument {
		val body = LayerRaster(40, 56, ByteArray(40 * 56 * 4) { if (it % 4 == 3) -1 else 120 })
		val source = WorkspaceSourceArt(128, 96, listOf(
			layer("body", 0, LayerBounds(10, 20, 40, 56), body),
			layer("pupil", 1, LayerBounds(70, 30, 32, 32), pupil),
		), emptyList())
		// Traced from the canvas view, a dense layer meshes exactly like its canvas-resolution twin.
		val config = PipelineConfig(atlasSize = 2048, meshSpacing = 8, meshOnly = true, meshTrace = MeshTrace.CANVAS)
		return WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
	}

	private fun cmo3(document: WorkspaceDocument, layerArt: String?): ByteArray = runBlocking {
		val model = WorkspacePreviewBuilder().build(document)
		val ir = RigIrCompiler.compile(model, tileArt = true)
		val settings = layerArt?.let { mapOf("layer_art" to it) } ?: emptyMap()
		Cmo3.write(Cmo3Target().convert(ir, ExportOptions("pupil", settings = settings)).model)
	}

	@Test fun cmo3ImportKeepsDenseArtDenseAndCanvasArtAsBefore() {
		val dense = disc(1024).let { LayerRaster(it.width, it.height, it.rgba) }
		val plain = disc(32).let { LayerRaster(it.width, it.height, it.rgba) }
		val config = PipelineConfig(atlasSize = 2048)

		// Canvas-resolution art: every layer covers its bounds one pixel per canvas unit, exactly as before.
		val (legacy, _) = Cmo3ModelImport.prepare(cmo3(document(plain), null), Cmo3ImportMode.NEW, null, config)
		for (layer in legacy.layers) {
			assertEquals(layer.bounds.width, layer.raster.width); assertEquals(layer.bounds.height, layer.raster.height)
		}

		// Dense art, whether the file keeps it only on the atlas page (canvas layers, packed at 32) or in the layer
		// itself (model image mapped at 1/32), imports at its density over the same bounds.
		val bounds = legacy.layers.single { it.name == "pupil" }.bounds
		for (layerArt in listOf(null, "native")) {
			val (source, imported) = Cmo3ModelImport.prepare(cmo3(document(dense), layerArt), Cmo3ImportMode.NEW, null, config)
			val pupil = source.layers.single { it.name == "pupil" }
			assertEquals(bounds, pupil.bounds, "layer_art=$layerArt")
			assertTrue(abs(pupil.raster.width / pupil.bounds.width.toFloat() - 32f) < 0.5f, "layer_art=$layerArt: ${pupil.raster.width}x${pupil.raster.height} on $bounds")
			assertTrue(abs(pupil.raster.height / pupil.bounds.height.toFloat() - 32f) < 0.5f)
			val body = source.layers.single { it.name == "body" }
			assertEquals(body.bounds.width, body.raster.width)

			// It renders over its bounds like the canvas-resolution import.
			val composite = PreviewRenderer.composite(source); val reference = PreviewRenderer.composite(legacy)
			val cx = bounds.left + bounds.width / 2; val cy = bounds.top + bounds.height / 2
			assertEquals(reference.getRGB(cx, cy) ushr 24, composite.getRGB(cx, cy) ushr 24)
			val preview = PSD2LivePipeline().buildPreview(source, imported)
			assertTrue(preview.rig.puppet.drawables.any { it.name == "pupil" })
		}
	}

	@Test fun rasterSizeStaysWithinTheBudget() {
		assertEquals(10 to 20, Cmo3ModelImport.rasterSize(10, 20, null))
		assertEquals(40 to 80, Cmo3ModelImport.rasterSize(10, 20, 4f to 4f))
		val (w, h) = Cmo3ModelImport.rasterSize(2000, 2000, 8f to 8f)
		assertTrue(w.toLong() * h <= LayerSizeBudget.MAX_PIXELS && w >= 2000)
	}

	@Test fun aPlacedLayerIsMeshedAtItsCanvasResolution() = runBlocking {
		val dense = LayerImport.placedLayer(disc(1024), 32, 32, "pupil", "pupil")
		assertTrue(dense.raster.width > 900, "trimmed to the disc, at full resolution")
		assertEquals(LayerBounds(0, 0, 32, 32), dense.bounds)
		val view = CanvasDensity.canvasRaster(dense)
		val doc = document(LayerRaster(32, 32, ByteArray(32 * 32 * 4)))
		fun with(raster: LayerRaster) = doc.copy(source = WorkspaceSourceArt(doc.source.widthPx, doc.source.heightPx,
			doc.source.layers.map { if (it.id.raw == "pupil") (it as WorkspaceSourceLayer).copy(raster = raster) else it }, doc.source.groups))
		val builder = WorkspacePreviewBuilder()
		val a = builder.build(with(dense.raster)); val b = builder.build(with(view))
		fun pupil(model: RigPreviewModel) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == "pupil" }
		assertContentEquals(pupil(b).mesh!!.positions, pupil(a).mesh!!.positions)
		assertContentEquals(pupil(b).mesh!!.indices, pupil(a).mesh!!.indices)
	}
}
