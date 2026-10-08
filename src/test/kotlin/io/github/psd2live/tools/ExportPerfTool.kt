package io.github.psd2live.tools

import io.github.psd2live.core.ExportService
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.format.compile.Compiler
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.psd.PsdReader
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test

/**
 * Time of every export target on the sample PSD upscaled to several resolutions, and a digest of every file
 * written, so an export speed-up can be checked against the previous commit byte for byte.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ExportPerfTool' -Ppsd2live.testHeap=8g
 * PSD2LIVE_SAMPLE picks the PSD (tml by default); PSD2LIVE_EXPORT_SCALES the integer upscales (nearest
 * neighbour, default 1,2); PSD2LIVE_EXPORT_TARGETS the target ids (every non-video target by default);
 * PSD2LIVE_GOLDEN_LABEL names the output. Raster targets render 1024 px per upscale step at 5 fps. The cmo3
 * holds random editor GUIDs, so it is listed by its image entries and its read-back moc3.
 * Writes build/tools/export-perf/<label>.txt (timings) and <label>.digest.txt (digests).
 */
class ExportPerfTool {
	@Test fun profile() {
		requireTools()
		val out = output("export-perf")
		val label = setting("PSD2LIVE_GOLDEN_LABEL", "current")
		val scales = setting("PSD2LIVE_EXPORT_SCALES", "1,2").split(',').map { it.trim().toInt() }
		val targets = setting("PSD2LIVE_EXPORT_TARGETS",
			"moc3,cmo3,vtube-studio,psd-pose,spine,dragonbones,p2lrt,web,gltf,png-sequence,sprite-sheet,gif").split(',').map(String::trim)
		val sample = Sample.fromEnvironment()
		val original = PsdReader.read(sample.path.toFile().readBytes())
		val timings = StringBuilder()
		val digests = StringBuilder()
		for (scale in scales) {
			val source = upscaled(original, scale)
			var start = System.nanoTime()
			val preview = PSD2LivePipeline().buildPreview(source)
			timings.appendLine("x$scale ${source.widthPx}x${source.heightPx} build ${ms(start)} ms, pages ${preview.atlas.pages.joinToString { "${it.image.width}x${it.image.height}" }}")
			start = System.nanoTime()
			RigIrCompiler.compile(preview)
			timings.appendLine("x$scale ir (first, encodes pages) ${ms(start)} ms")
			start = System.nanoTime()
			RigIrCompiler.compile(preview)
			timings.appendLine("x$scale ir (again) ${ms(start)} ms")
			val registry = ExportService.registry(preview.config)
			for (id in targets) {
				val target = registry[id]
				val settings = when {
					id in setOf("png-sequence", "sprite-sheet", "gif") -> mapOf("size" to minOf(8192, 1024 * scale).toString(), "fps" to "5")
					else -> emptyMap()
				}
				val options = ExportService.options(target, sample.name, preview.config, settings)
				val files = LinkedHashMap<String, ByteArray>()
				start = System.nanoTime()
				val ir = RigIrCompiler.compile(preview, tileArt = id == "cmo3")
				val compiled = ms(start)
				start = System.nanoTime()
				val result = runCatching { Compiler.export(target, ir, options) { path, bytes -> files[path] = bytes } }
				val took = ms(start)
				val line = result.fold({ "x$scale $id ${took} ms (ir $compiled ms) ${files.values.sumOf { it.size } / 1024} KiB" },
					{ "x$scale $id failed: ${it.javaClass.simpleName}: ${it.message?.take(200)}" })
				println(line)
				timings.appendLine(line)
				for ((path, bytes) in files) {
					if (path.endsWith(".cmo3")) cmo3Digest(bytes).forEach { digests.appendLine("x$scale $id $path $it") }
					else digests.appendLine("x$scale $id $path ${sha(bytes)}")
				}
			}
		}
		File(out, "$label.txt").writeText(timings.toString())
		File(out, "$label.digest.txt").writeText(digests.toString())
		println(timings)
	}

	private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

	/** [source] with every layer [scale] times larger, nearest neighbour. */
	private fun upscaled(source: SourceArt, scale: Int): SourceArt = if (scale == 1) source else object : SourceArt {
		override val warnings: List<String> = source.warnings
		override val groups = source.groups
		override val widthPx: Int = source.widthPx * scale
		override val heightPx: Int = source.heightPx * scale
		override val layers: List<SourceLayer> = source.layers.map { layer ->
			object : SourceLayer by layer {
				override val bounds = layer.bounds.let { org.umamo.format.art.LayerBounds(it.left * scale, it.top * scale, it.width * scale, it.height * scale) }
				override val raster: LayerRaster by lazy {
					val r = layer.raster
					val w = r.width * scale; val h = r.height * scale
					val rgba = ByteArray(w * h * 4)
					for (y in 0 until h) for (x in 0 until w) System.arraycopy(r.rgba, ((y / scale) * r.width + x / scale) * 4, rgba, (y * w + x) * 4, 4)
					LayerRaster(w, h, rgba)
				}
			}
		}
	}

	/** The cmo3's image entries and its read-back rig lowered to moc3; its XML holds random GUIDs. */
	private fun cmo3Digest(bytes: ByteArray): List<String> = runCatching {
		val archive = org.umamo.format.cmo3.caff.CaffCodec.read(bytes)
		val images = archive.entries.filter { it.path.endsWith(".png") }.map { "${it.path} ${sha(it.content)}" }
		val source = org.umamo.format.cmo3.Cmo3.read(bytes).root as org.umamo.format.cmo3.model.custom.CModelSource
		val puppet = org.umamo.interop.cmo3.Cmo3Import.fromModelSource(source)
		val bundle = org.umamo.interop.moc3.Moc3Sidecars.bundle(puppet, "readback", pages = emptyList())
		images + "readback ${sha(bundle.files.single { it.name.endsWith(".moc3") }.bytes)}"
	}.getOrElse { listOf("error:${it.javaClass.simpleName}") }

	private fun sha(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
