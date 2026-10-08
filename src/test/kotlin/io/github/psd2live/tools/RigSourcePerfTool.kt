package io.github.psd2live.tools

import io.github.psd2live.core.AtlasLayout
import io.github.psd2live.core.AtlasPage
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PackedAtlas
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigBuildProfile
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.psd.PsdReader
import java.nio.file.Files
import kotlin.test.Test

/**
 * The pixel-proportional work of a rebuild on a large document with a saved generation input: the
 * generation-source preparation (padding repainted layers to their pinned rectangles, classification), the
 * atlas pages and the preview PNG encoding of every page.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*RigSourcePerfTool'
 * PSD2LIVE_SAMPLE picks the PSD (tml by default), PSD2LIVE_SCALE how much it is enlarged (3 by default). Every
 * third layer is cropped by a few pixels against the generation input (so it is padded back to its pinned
 * rectangle), every fifth is stored at twice its canvas density. Writes build/tools/rig-source-perf/report.txt.
 */
class RigSourcePerfTool {
	private fun ms(start: Long) = (System.nanoTime() - start) / 1e6

	private fun scaled(layer: SourceLayer, scale: Int, order: Int, dense: Boolean): SourceLayer {
		val r = layer.raster
		val density = if (dense) 2 else 1
		val w = r.width * scale; val h = r.height * scale
		val rw = w * density; val rh = h * density
		val rgba = ByteArray(rw * rh * 4)
		for (y in 0 until rh) {
			val sy = y / (scale * density)
			for (x in 0 until rw) {
				val sx = x / (scale * density)
				System.arraycopy(r.rgba, (sy * r.width + sx) * 4, rgba, (y * rw + x) * 4, 4)
			}
		}
		val bounds = LayerBounds(layer.bounds.left * scale, layer.bounds.top * scale, w, h)
		return WorkspaceSourceLayer(layer.id, layer.name, layer.groupPath, layer.kind, layer.visible, order, bounds, layer.opacity,
			layer.clipped, layer.blend, layer.channelMask, LayerRaster(rw, rh, rgba), null, null, false,
			if (dense) LayerCanvasRect.of(bounds) else null)
	}

	private fun cropped(layer: SourceLayer, by: Int): SourceLayer {
		val b = layer.bounds; val r = layer.raster
		if (b.width <= 2 * by + 1 || b.height <= 2 * by + 1) return layer
		val w = b.width - 2 * by; val h = b.height - 2 * by
		val rgba = ByteArray(w * h * 4)
		for (y in 0 until h) System.arraycopy(r.rgba, ((y + by) * r.width + by) * 4, rgba, y * w * 4, w * 4)
		val l = layer as WorkspaceSourceLayer
		return l.copy(bounds = LayerBounds(b.left + by, b.top + by, w, h), raster = LayerRaster(w, h, rgba))
	}

	@Test fun profile() {
		requireTools()
		System.setProperty("psd2live.validatePreviewBundles", "false")
		val sample = Sample.fromEnvironment()
		val scale = setting("PSD2LIVE_SCALE", "3").toInt()
		val out = output("rig-source-perf")
		val report = StringBuilder()
		fun line(text: String) { report.appendLine(text); println(text) }
		val psd = PsdReader.read(Files.readAllBytes(sample.path))
		val layers = psd.layers.filter { it.raster.width > 0 && it.raster.height > 0 }
		val reference = WorkspaceSourceArt(psd.widthPx * scale, psd.heightPx * scale,
			layers.mapIndexed { i, layer -> scaled(layer, scale, layer.order, dense = i % 5 == 4) }, psd.groups)
		val current = reference.copy(layers = reference.layers.mapIndexed { i, layer -> if (i % 3 == 0 && i % 5 != 4) cropped(layer, 3) else layer })
		line("sample=${sample.name} scale=$scale canvas=${reference.widthPx}x${reference.heightPx} layers=${reference.layers.size} " +
			"pixels=%.1f MP".format(reference.layers.sumOf { it.raster.width.toLong() * it.raster.height } / 1e6))
		val config = PipelineConfig(generationSource = reference)
		val pipeline = PSD2LivePipeline()
		RigBuildProfile.recording = true
		fun stages(label: String, block: () -> RigPreviewModel): RigPreviewModel {
			RigBuildProfile.reset()
			val start = System.nanoTime()
			val model = block()
			line("$label: %.0f ms".format(ms(start)))
			for ((name, value) in RigBuildProfile.snapshot()) if (name.startsWith("prepare") || name.startsWith("pipeline") || name.startsWith("analyze"))
				line("    %-40s %8.1f ms x%d".format(name, value.first, value.second))
			return model
		}
		var model = stages("cold build") { pipeline.buildPreview(current, config) }
		repeat(3) { round -> model = stages("warm rebuild $round") { pipeline.buildPreview(current, config, previousAtlas = model.atlas) } }
		val painted = current.copy(layers = current.layers.mapIndexed { i, layer ->
			if (i != 1) layer else (layer as WorkspaceSourceLayer).copy(raster = LayerRaster(layer.raster.width, layer.raster.height, layer.raster.rgba.copyOf()))
		})
		model = stages("paint rebuild") { pipeline.buildPreview(painted, config, previousAtlas = model.atlas) }
		line("atlas pages=${model.atlas.pages.size} size=${model.atlas.pages.firstOrNull()?.image?.width}")
		// Pages composed afresh: every tile raster is a new array, so no page or digest cache hits.
		val fresh = model.analysis.layers.map { layer ->
			val s = layer.source
			layer.copy(source = object : SourceLayer by s { override val raster = LayerRaster(s.raster.width, s.raster.height, s.raster.rgba.copyOf()) })
		}
		repeat(2) { round ->
			val copies = if (round == 0) fresh else fresh.map { layer ->
				val s = layer.source
				layer.copy(source = object : SourceLayer by s { override val raster = LayerRaster(s.raster.width, s.raster.height, s.raster.rgba.copyOf()) })
			}
			var start = System.nanoTime()
			val atlas: PackedAtlas = AtlasLayout.pack(copies, config)
			line("compose pages (cold) $round: %.0f ms".format(ms(start)))
			start = System.nanoTime()
			val bytes = atlas.pages.sumOf { AtlasPage(it.image).previewPng.size.toLong() }
			line("encode preview pages $round: %.0f ms, %.1f MB".format(ms(start), bytes / 1e6))
		}
		RigBuildProfile.recording = false
		out.resolve("report.txt").writeText(report.toString())
	}
}
