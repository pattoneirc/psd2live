package io.github.psd2live.core

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.config
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A 32-unit "pupil" replaced by a 1024-pixel raster keeps its rig and its place, and its tile holds the new pixels. */
class LayerImageReplaceTest {
	@TempDir lateinit var temp: Path

	/** A disc of radius [size]/2 at [size] pixels, coloured by position so resampling errors show. */
	private fun disc(size: Int): LayerRaster = LayerRaster(size, size, ByteArray(size * size * 4).also { rgba ->
		val r = size / 2f
		for (y in 0 until size) for (x in 0 until size) {
			val dx = x + 0.5f - r; val dy = y + 0.5f - r
			if (dx * dx + dy * dy > r * r * 0.81f) continue
			val o = (y * size + x) * 4
			rgba[o] = (40 + 160 * x / size).toByte(); rgba[o + 1] = 60; rgba[o + 2] = (200 - 120 * y / size).toByte(); rgba[o + 3] = -1
		}
	})

	private fun layer(id: String, order: Int, bounds: LayerBounds, raster: LayerRaster) = WorkspaceSourceLayer(
		LayerId(id), id, "", SourceLayerKind.Raster, true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		raster, null, null, false)

	private fun document(atlas: JsonObject? = null): WorkspaceDocument {
		val body = LayerRaster(40, 56, ByteArray(40 * 56 * 4) { if (it % 4 == 3) -1 else 120 })
		val source = WorkspaceSourceArt(128, 96, listOf(
			layer("body", 0, LayerBounds(10, 20, 40, 56), body),
			layer("pupil", 1, LayerBounds(70, 30, 32, 32), disc(32)),
		), emptyList())
		val config = PipelineConfig(atlasSize = 2048, meshSpacing = 8, meshOnly = true)
		val settings = WorkspaceSettingsCodec.encode(config).let { if (atlas == null) it else JsonObject(it + (WorkspaceSettingsCodec.ATLAS to atlas)) }
		return WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits, settings)
	}

	/** Each nearest-neighbour block of [raster] repeated [factor] times on both axes. */
	private fun upscaled(raster: LayerRaster, factor: Int) = LayerRaster(raster.width * factor, raster.height * factor,
		ByteArray(raster.width * factor * raster.height * factor * 4).also { out ->
			for (y in 0 until raster.height * factor) for (x in 0 until raster.width * factor)
				System.arraycopy(raster.rgba, ((y / factor) * raster.width + x / factor) * 4, out, (y * raster.width * factor + x) * 4, 4)
		})

	private fun pupil(model: RigPreviewModel) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == "pupil" }

	@Test fun aDenseReplacementKeepsTheRigAndAddressesItsFullResolutionTile() = runBlocking {
		val builder = WorkspacePreviewBuilder()
		val before = document()
		val old = builder.build(before)
		val replacement = upscaled(disc(32), 32)
		val after = LayerImageReplace.replace(before, "pupil", replacement, LayerImageReplace.Fit.STRETCH)
		assertEquals(before.source.layers[1].bounds, after.source.layers[1].bounds)
		val new = builder.build(after)

		// Geometry and keyforms are the same; only the bound uvs and pages may differ.
		assertEquals(old.rig.puppet.drawables.map { it.id }, new.rig.puppet.drawables.map { it.id })
		for ((a, b) in old.rig.puppet.drawables.zip(new.rig.puppet.drawables)) {
			assertContentEquals(a.mesh!!.positions, b.mesh!!.positions)
			assertContentEquals(a.mesh!!.indices, b.mesh!!.indices)
			assertEquals(a.geometryGrid?.axes, b.geometryGrid?.axes)
			assertEquals(a.geometryGrid?.cells?.size, b.geometryGrid?.cells?.size)
			a.geometryGrid?.cells.orEmpty().zip(b.geometryGrid?.cells.orEmpty()).forEach { (x, y) ->
				assertContentEquals(x.coordinate, y.coordinate); assertContentEquals(x.form.positionDeltas, y.form.positionDeltas)
			}
		}
		assertEquals(old.rig.puppet.deformers, new.rig.puppet.deformers)

		// The tile is the raster at full resolution (the budget fits it at fit 1), and every uv lands where the old one
		// did, measured in canvas units of the layer.
		val oldTile = old.atlas.placementByLayerId.getValue("pupil")
		val tile = new.atlas.placementByLayerId.getValue("pupil")
		assertEquals(1f, new.atlas.fit)
		assertEquals(1024, tile.width); assertEquals(1024, tile.height); assertEquals(1f, tile.scaleX)
		val oldPage = old.atlas.pages[oldTile.page].image.width.toFloat(); val page = new.atlas.pages[tile.page].image.width.toFloat()
		val oldUvs = pupil(old).mesh!!.uvs; val uvs = pupil(new).mesh!!.uvs
		for (i in uvs.indices step 2) {
			val oldOffset = (oldUvs[i] * oldPage - oldTile.x) / oldTile.scaleX
			val offset = (uvs[i] * page - tile.x) / tile.scaleX / 32f
			assertTrue(abs(oldOffset - offset) < 1e-2f, "uv $i maps to $offset instead of $oldOffset")
		}
		val oldOffsetY = (oldUvs[1] * oldPage - oldTile.y); val offsetY = (uvs[1] * page - tile.y) / 32f
		assertTrue(abs(oldOffsetY - offsetY) < 1e-2f)

		// The page holds the new pixels: the tile's centre is the disc's centre colour.
		val centre = new.atlas.pages[tile.page].image.getRGB(tile.x + 512, tile.y + 512)
		assertEquals(replacement.rgba[(512 * 1024 + 512) * 4 + 3].toInt() and 0xff, centre ushr 24)

		// The layer still renders over its 32-unit rectangle.
		val oldComposite = PreviewRenderer.composite(before.source); val composite = PreviewRenderer.composite(after.source)
		for ((x, y) in listOf(86 to 46, 72 to 32, 100 to 60, 60 to 40)) {
			val a = oldComposite.getRGB(x, y); val b = composite.getRGB(x, y)
			for (shift in listOf(0, 8, 16, 24)) assertTrue(abs((a ushr shift and 0xff) - (b ushr shift and 0xff)) <= 24, "pixel $x,$y: $a vs $b")
		}

		// Exports read back: moc3 with every drawable, cmo3 with the layer at canvas resolution and a texture-size note.
		val result = PSD2LivePipeline().run(after.source, "pupil", temp, after.config().copy(exportJson = false))
		val moc = Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(result.exportedFiles.single { it.path.toString().endsWith(".moc3") }.path)), null)
		assertEquals(new.rig.puppet.drawables.map { it.id }.toSet(), moc.drawables.map { it.id }.toSet())
		val cmo3 = Files.readAllBytes(result.exportedFiles.single { it.path.toString().endsWith(".cmo3") }.path)
		val imported = Cmo3ModelImport.read(cmo3)
		assertEquals(new.rig.puppet.drawables.size, imported.puppet.drawables.size)
		assertTrue(result.warnings.any { "Cubism Editor rebuilds the atlas" in it }, result.warnings.joinToString("\n"))
		val ir = RigIrCompiler.compile(new, tileArt = true)
		val lowered = io.github.psd2live.targets.cubism.Cmo3Target().convert(ir, io.github.psd2live.format.compile.ExportOptions("pupil"))
		assertTrue(lowered.puppet.atlas.tiles.any { it.name == "pupil" && it.width == 32 && it.height == 32 })
	}

	@Test fun aTightBudgetScalesTheDenseTileByOneFit() = runBlocking {
		val budget = JsonObject(WorkspaceSettingsCodec.encodeAtlasBudget(AtlasBudget(512, 1, 2)))
		val before = document(budget)
		val after = LayerImageReplace.replace(before, "pupil", upscaled(disc(32), 32), LayerImageReplace.Fit.STRETCH)
		val model = WorkspacePreviewBuilder().build(after)
		assertEquals(1, model.atlas.pages.size)
		assertTrue(model.atlas.fit < 1f)
		assertTrue(model.atlas.notices.isNotEmpty())
		val tile = model.atlas.placementByLayerId.getValue("pupil")
		assertEquals(Math.round(1024 * model.atlas.fit.toDouble()).toInt(), tile.width)
		val body = model.atlas.placementByLayerId.getValue("body")
		assertEquals(Math.round(40 * model.atlas.fit.toDouble()).toInt(), body.width)
		assertContentEquals(pupil(WorkspacePreviewBuilder().build(before)).mesh!!.positions, pupil(model).mesh!!.positions)
	}

	@Test fun aDenseLayerIsMeshedAtCanvasResolution() = runBlocking {
		val plain = document()
		// The same layer authored at 32x: its canvas view is the 32-pixel disc again, so the mesh is too.
		val dense = plain.copy(source = WorkspaceSourceArt(plain.source.widthPx, plain.source.heightPx, plain.source.layers.map {
			if (it.id.raw != "pupil") it else (it as WorkspaceSourceLayer).copy(raster = upscaled(it.raster, 32))
		}, plain.source.groups))
		val builder = WorkspacePreviewBuilder()
		val a = builder.build(plain); val b = builder.build(dense)
		assertContentEquals(pupil(a).mesh!!.positions, pupil(b).mesh!!.positions)
		assertContentEquals(pupil(a).mesh!!.indices, pupil(b).mesh!!.indices)
		assertEquals(a.analysis.layers.single { it.source.id.raw == "pupil" }.bounds, b.analysis.layers.single { it.source.id.raw == "pupil" }.bounds)
		assertEquals(1024, b.atlas.placementByLayerId.getValue("pupil").width)
		assertEquals(1024, b.rig.puppet.atlas.tiles.single { it.name == "pupil" }.width)
	}

	@Test fun containKeepsTheAspectRatioOnTransparentPixels() {
		val wide = LayerRaster(64, 32, ByteArray(64 * 32 * 4) { -1 })
		val replaced = LayerImageReplace.replace(document(), "pupil", wide, LayerImageReplace.Fit.CONTAIN).source.layers[1]
		assertEquals(64, replaced.raster.width); assertEquals(64, replaced.raster.height)
		assertEquals(0, replaced.raster.rgba[3].toInt())
		assertEquals(-1, replaced.raster.rgba[(32 * 64 + 10) * 4 + 3].toInt())
		assertEquals(LayerBounds(70, 30, 32, 32), replaced.bounds)
	}
}
