package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class ExportServiceTest {
	@TempDir lateinit var temporary: Path

	private fun layer(id: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order,
		bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) -1 else (60 + order * 90).toByte() }),
		null, null, false)

	private val preview by lazy {
		PSD2LivePipeline().buildPreview(WorkspaceSourceArt(96, 80, listOf(layer("a", 0, LayerBounds(8, 8, 32, 24)), layer("b", 1, LayerBounds(42, 36, 24, 24))), emptyList()),
			PipelineConfig(atlasSize = 256, meshSpacing = 8, meshOnly = true, generatePhysics = false))
	}

	@Test fun moc3ExportsMatchTheEditorsOwnRuntimeBundle() {
		val target = ExportService.registry(preview.config)["moc3"]
		val out = temporary.resolve("moc3")
		val report = ExportService.export(preview, target, ExportService.options(target, "rig", preview.config), out)
		val moc = Files.readAllBytes(out.resolve("rig.moc3"))
		assertEquals(preview.rig.puppet.drawables.size, Moc3Import.fromMocDocument(Moc3.read(moc), null).drawables.size)
		assertTrue(Files.isRegularFile(out.resolve("rig.moc3.report.json")))
		assertTrue(report.files.contains("rig.model3.json"))
		// The export and the preview bundle are one compile: same moc bytes under the same base name.
		val bundle = PSD2LivePipeline().buildRuntimeBundle("rig", preview.analysis, preview.atlas, preview.rig, preview.config).first
		assertContentEquals(bundle.assets.single { it.path.endsWith(".moc3") }.bytes, moc)
		// No staging directory is left behind.
		assertEquals(emptyList(), Files.list(out).use { files -> files.filter { it.fileName.toString().startsWith(".export-") }.toList() })
	}

	@Test fun rasterExportsRenderTheRigThroughTheIr() {
		val target = ExportService.registry(preview.config)["png-sequence"]
		val out = temporary.resolve("frames")
		val report = ExportService.export(preview, target, ExportService.options(target, "still", preview.config, mapOf("size" to "96")), out)
		val image = ImageIO.read(out.resolve(report.files.first()).toFile())
		assertEquals(96 to 80, image.width to image.height)
		// Layer b's art sits around (54, 48); the rig renders it opaque there and leaves the corner empty.
		assertEquals(255, image.getRGB(54, 48) ushr 24)
		assertEquals(0, image.getRGB(2, 76) ushr 24)
	}

	@Test fun posedPsdsHoldOneLayerPerVisibleMesh() {
		val target = ExportService.registry(preview.config)["psd-pose"]
		val out = temporary.resolve("psd")
		ExportService.export(preview, target, ExportService.options(target, "pose", preview.config), out)
		val psd = org.umamo.format.psd.PsdReader.read(Files.readAllBytes(out.resolve("pose.psd")))
		assertEquals(96 to 80, psd.widthPx to psd.heightPx)
		assertEquals(preview.rig.puppet.drawables.map { it.name }.toSet(), psd.layers.map { it.name }.toSet())
		// Each layer is cropped to its own art: layer b sits around (42..66, 36..60).
		val b = psd.layers.single { layer -> layer.name == preview.rig.puppet.drawables.single { preview.rig.layerIdByDrawableId[it.id.raw] == "b" }.name }
		assertTrue(b.bounds.left in 40..44 && b.bounds.top in 34..38 && b.bounds.width in 22..26, "${b.bounds}")
		assertFailsWith<IllegalArgumentException> {
			ExportService.export(preview, target, ExportService.options(target, "bad", preview.config, mapOf("pose" to "Missing=1")), out)
		}
	}

	@Test fun theCliRejectsIncompleteRequests() {
		assertEquals(2, io.github.psd2live.ExportCli.run(listOf("export")))
		assertEquals(2, io.github.psd2live.ExportCli.run(listOf("export", "x.psd2live")))
		assertEquals(2, io.github.psd2live.ExportCli.run(listOf("export", "x.psd2live", "--target", "moc3", "--set", "novalue")))
		assertEquals(1, io.github.psd2live.ExportCli.run(listOf("export", temporary.resolve("missing.txt").toString(), "--target", "moc3")))
		assertEquals(0, io.github.psd2live.ExportCli.run(listOf("targets")))
	}
}
