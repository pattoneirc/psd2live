package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceViewRenderer

import io.github.psd2live.project.WorkspaceViewBackground
import io.github.psd2live.project.WorkspaceViewFrame
import io.github.psd2live.project.WorkspaceViewOutputSpec
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.SkeletonAutoBuilder
import io.github.psd2live.core.SkeletonSpec
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

/*
 * Shared plumbing for the development tools in this package. They are tests only so they can reach the
 * pipeline's internals; each runs only with PSD2LIVE_TOOLS=1 and is skipped otherwise, so `./gradlew
 * test` never runs them. See docs/zh/guide/DEVELOPMENT.md.
 */

/** Skips the calling tool unless PSD2LIVE_TOOLS=1. */
internal fun requireTools() {
	assumeTrue(System.getenv("PSD2LIVE_TOOLS") == "1", "Set PSD2LIVE_TOOLS=1 to run the development tools")
}

/** An environment setting of a tool, or [default]. */
internal fun setting(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

/**
 * The sample PSD2LIVE_SAMPLE names: a bundled example (tml, ds) or a path to a PSD. Its name labels
 * the outputs.
 */
internal class Sample(val name: String, val path: Path) {
	/** This sample at [path] instead, under the same name. */
	fun at(path: Path) = Sample(name, path)

	companion object {
		fun fromEnvironment(): Sample {
			val sample = setting("PSD2LIVE_SAMPLE", "tml")
			val file = File(sample)
			return if (file.isFile) Sample(file.nameWithoutExtension, file.toPath())
			else Sample(sample, Path.of("examples/$sample/psd-input/$sample.psd"))
		}
	}
}

/** [sample]'s PSD with every layer and the canvas scaled [factor] times by pixel repetition, written to [out] once. */
internal fun scaledSample(sample: Sample, factor: Int, out: File): File {
	val file = File(out, "${sample.name}-x$factor.psd")
	if (file.isFile) return file
	val art = org.umamo.format.psd.PsdReader.read(java.nio.file.Files.readAllBytes(sample.path))
	fun scale(raster: org.umamo.format.art.LayerRaster): org.umamo.format.art.LayerRaster {
		val w = raster.width * factor; val h = raster.height * factor
		val bytes = ByteArray(w * h * 4)
		for (y in 0 until h) for (x in 0 until w) System.arraycopy(raster.rgba, ((y / factor) * raster.width + x / factor) * 4, bytes, (y * w + x) * 4, 4)
		return org.umamo.format.art.LayerRaster(w, h, bytes)
	}
	val layers = art.layers.map { l ->
		io.github.psd2live.project.WorkspaceSourceLayer(l.id, l.name, l.groupPath, l.kind, l.visible, l.order,
			org.umamo.format.art.LayerBounds(l.bounds.left * factor, l.bounds.top * factor, l.bounds.width * factor, l.bounds.height * factor),
			l.opacity, l.clipped, l.blend, l.channelMask, scale(l.raster), null, null, false)
	}
	file.writeBytes(org.umamo.format.psd.PsdWriter.write(io.github.psd2live.project.WorkspaceSourceArt(art.widthPx * factor, art.heightPx * factor, layers, art.groups)))
	return file
}

/** [sample] built plain, and [spec] (or its auto skeleton) built on it. */
internal class Built(val plain: RigPreviewModel, val spec: SkeletonSpec, val skeletal: RigPreviewModel)

internal fun build(sample: Sample, spec: (RigPreviewModel) -> SkeletonSpec = { SkeletonAutoBuilder.build(it.analysis, it.rig) }): Built {
	val plain = PSD2LivePipeline().buildPreview(sample.path)
	val skeleton = spec(plain)
	val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(rigEdits = plain.config.rigEdits.copy(skeleton = skeleton)))
	return Built(plain, skeleton, skeletal)
}

/** Renders [preview] posed at parameter values, over [rect] (the whole canvas by default), [size] pixels across. */
internal class Renderer(private val preview: RigPreviewModel, private val size: Int) {
	private val layers = preview.rig.layerIdByDrawableId.values.toSet()
	val canvas = Bounds(0f, 0f, preview.analysis.source.widthPx.toFloat(), preview.analysis.source.heightPx.toFloat())

	fun render(values: Map<String, Float>, rect: Bounds = canvas): BufferedImage {
		val view = WorkspaceViewRenderer.modelComposite(preview, "r", values, layers, emptySet(),
			WorkspaceViewFrame.CanvasRect(rect), WorkspaceViewBackground.CHECKERBOARD, WorkspaceViewOutputSpec(size))
		return ImageIO.read(view.png.inputStream())
	}
}

/** The directory build/tools/[name], created. */
internal fun output(name: String): File = File("build/tools/$name").apply { mkdirs() }

/** Labelled [frames] tiled [columns] across into [file]. */
internal fun sheet(frames: List<Pair<String, BufferedImage>>, file: File, columns: Int = 5) {
	val tw = frames.first().second.width
	val th = frames.first().second.height
	val across = minOf(columns, frames.size)
	val rows = (frames.size + across - 1) / across
	val image = BufferedImage(across * tw, rows * (th + LABEL), BufferedImage.TYPE_INT_RGB)
	val g = image.createGraphics()
	g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
	frames.forEachIndexed { i, (label, frame) ->
		val x = i % across * tw
		val y = i / across * (th + LABEL)
		g.color = Color.DARK_GRAY
		g.fillRect(x, y, tw, LABEL)
		g.color = Color.WHITE
		g.drawString(label, x + 4, y + 15)
		g.drawImage(frame, x, y + LABEL, null)
	}
	g.dispose()
	ImageIO.write(image, "png", file)
	println("wrote $file")
}

private const val LABEL = 20
