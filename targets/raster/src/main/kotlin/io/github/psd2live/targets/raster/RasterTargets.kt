package io.github.psd2live.targets.raster

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.RigIR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

/**
 * Frames rendered from the rig: each target samples one clip (or the rest pose) and writes pixels only.
 *
 * Settings shared by every raster target:
 * - `clip`: the clip id to render; the first clip by default, or the rest pose when the rig has none.
 * - `fps`: frames per second; the clip's own rate by default.
 * - `size`: the output's longer side in pixels, 1024 by default.
 * - `background`: an ARGB color in hex (for example `ffffffff`); transparent by default.
 * - `physics`: whether physics moves between frames, true by default.
 */
public class RasterTargets(private val renderer: FrameRenderer) {
	public val all: List<ExportTarget> get() = listOf(sequence, sheet, gif)

	/** Numbered PNG frames. */
	public val sequence: ExportTarget = target("png-sequence", "PNG image sequence") { frames, options, clip, fps ->
		{ sink: OutputSink ->
			frames.forEachIndexed { index, image -> sink.write("${options.baseName}_${index.toString().padStart(4, '0')}.png", png(image)) }
		}
	}

	/** One sheet of frames in a grid plus a TexturePacker "hash" JSON describing them. */
	public val sheet: ExportTarget = target("sprite-sheet", "Sprite sheet (PNG + TexturePacker JSON)") { frames, options, clip, fps ->
		val width = frames.first().width
		val height = frames.first().height
		val columns = kotlin.math.ceil(kotlin.math.sqrt(frames.size.toDouble())).toInt()
		val rows = (frames.size + columns - 1) / columns
		require(columns.toLong() * width <= MAX_SHEET && rows.toLong() * height <= MAX_SHEET) {
			"Sprite sheet ${columns * width}x${rows * height} exceeds $MAX_SHEET px; lower the size or the frame rate"
		}
		val image = BufferedImage(columns * width, rows * height, BufferedImage.TYPE_INT_ARGB)
		frames.forEachIndexed { index, frame ->
			image.setRGB(index % columns * width, index / columns * height, width, height, frame.argb, 0, width)
		}
		val sheetName = "${options.baseName}.png"
		val json = buildString {
			append("{\n  \"frames\": {")
			frames.indices.forEach { index ->
				if (index > 0) append(',')
				val x = index % columns * width
				val y = index / columns * height
				append("\n    \"${options.baseName}_${index.toString().padStart(4, '0')}\": {\"frame\": {\"x\": $x, \"y\": $y, \"w\": $width, \"h\": $height}, ")
				append("\"rotated\": false, \"trimmed\": false, \"spriteSourceSize\": {\"x\": 0, \"y\": 0, \"w\": $width, \"h\": $height}, ")
				append("\"sourceSize\": {\"w\": $width, \"h\": $height}, \"duration\": ${(1000f / fps).toInt()}}")
			}
			append("\n  },\n  \"meta\": {\"app\": \"psd2live\", \"version\": \"${Compiler.version}\", \"image\": \"$sheetName\", ")
			append("\"format\": \"RGBA8888\", \"size\": {\"w\": ${image.width}, \"h\": ${image.height}}, \"scale\": \"1\", ")
			append("\"frameRate\": $fps, \"loop\": ${clip?.loop ?: false}}\n}\n")
		}
		val write: (OutputSink) -> Unit = { sink -> sink.write(sheetName, png(image)); sink.write("${options.baseName}.json", json.encodeToByteArray()) }
		write
	}

	/** An animated GIF: 256 colors, 1-bit transparency, looping when the clip loops. */
	public val gif: ExportTarget = target("gif", "Animated GIF", extraLoss = LossEntry("*", Feature.TEXTURE_SIZE, Handling.APPROXIMATED,
		note = "GIF holds 256 colors and 1-bit transparency")) { frames, options, clip, fps ->
		{ sink: OutputSink -> sink.write("${options.baseName}.gif", gif(frames, fps, clip?.loop ?: false)) }
	}

	private fun target(
		id: String, description: String, extraLoss: LossEntry? = null,
		writer: (List<RasterImage>, ExportOptions, Clip?, Float) -> (OutputSink) -> Unit,
	): ExportTarget = object : ExportTarget {
		override val id: String = id
		override val family: TargetFamily = TargetFamily.RASTER
		override val description: String = description
		override val capabilities: CapabilityProfile = CapabilityProfile(structure = false)
		override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
			val clip = options.setting("clip")?.let { wanted -> ir.clips.firstOrNull { it.id == wanted } ?: throw IllegalArgumentException("Unknown clip: $wanted") }
				?: ir.clips.firstOrNull()
			val fps = options.float("fps", clip?.fps ?: 30f)
			require(fps.isFinite() && fps in 1f..120f) { "FPS must be within 1..120" }
			val size = options.int("size", 1024)
			require(size in 16..8192) { "Size must be within 16..8192" }
			val background = options.setting("background")?.let { java.lang.Long.parseUnsignedLong(it.removePrefix("#"), 16).toInt() } ?: 0
			val physics = options.flag("physics", true) && ir.physics.groups.isNotEmpty()
			val spec = FrameSpec.canvas(ir, size, background)
			val times = clip?.let { ClipSampler.frameTimes(it, fps) } ?: listOf(0f)
			require(times.size <= MAX_FRAMES) { "${times.size} frames exceed the limit of $MAX_FRAMES" }
			var simulated = false
			val frames = renderer.open(ir, physics).use { session ->
				var previous = 0f
				times.map { time ->
					val values = clip?.let { ClipSampler.valuesAt(it, time) } ?: emptyMap()
					session.render(values, (time - previous).also { previous = time }, spec)
				}.also { simulated = session.simulatesPhysics }
			}
			val losses = CapabilityScan.scan(ir, capabilities, options).filter { it.feature != Feature.PHYSICS || !simulated } + listOfNotNull(extraLoss)
			val write = writer(frames, options, clip, fps)
			return object : LoweredExport {
				override val losses: List<LossEntry> = losses
				override fun write(sink: OutputSink) = write(sink)
			}
		}
	}

	private fun image(frame: RasterImage) = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB).also {
		it.setRGB(0, 0, frame.width, frame.height, frame.argb, 0, frame.width)
	}

	private fun png(frame: RasterImage) = png(image(frame))

	private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()

	private fun gif(frames: List<RasterImage>, fps: Float, loop: Boolean): ByteArray {
		val writer = ImageIO.getImageWritersByFormatName("gif").next()
		val output = ByteArrayOutputStream()
		ImageIO.createImageOutputStream(output).use { stream ->
			writer.output = stream
			writer.prepareWriteSequence(null)
			val delay = (100f / fps).toInt().coerceAtLeast(1)
			frames.forEachIndexed { index, frame ->
				val indexed = indexed(frame)
				val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(indexed), null)
				val format = metadata.nativeMetadataFormatName
				val root = metadata.getAsTree(format) as IIOMetadataNode
				child(root, "GraphicControlExtension").apply {
					setAttribute("disposalMethod", "restoreToBackgroundColor")
					setAttribute("userInputFlag", "FALSE")
					setAttribute("transparentColorFlag", "TRUE")
					setAttribute("delayTime", delay.toString())
					setAttribute("transparentColorIndex", "0")
				}
				val model = indexed.colorModel as java.awt.image.IndexColorModel
				child(root, "LocalColorTable").apply {
					while (hasChildNodes()) removeChild(firstChild)
					setAttribute("sizeOfLocalColorTable", "256"); setAttribute("sortFlag", "FALSE")
					for (entry in 0 until 256) appendChild(IIOMetadataNode("ColorTableEntry").apply {
						setAttribute("index", entry.toString()); setAttribute("red", model.getRed(entry).toString())
						setAttribute("green", model.getGreen(entry).toString()); setAttribute("blue", model.getBlue(entry).toString())
					})
				}
				if (index == 0 && loop) {
					val extensions = child(root, "ApplicationExtensions")
					extensions.appendChild(IIOMetadataNode("ApplicationExtension").apply {
						setAttribute("applicationID", "NETSCAPE"); setAttribute("authenticationCode", "2.0")
						userObject = byteArrayOf(1, 0, 0)
					})
				}
				metadata.setFromTree(format, root)
				writer.writeToSequence(IIOImage(indexed, null, metadata), null)
			}
			writer.endWriteSequence()
		}
		writer.dispose()
		return output.toByteArray()
	}

	private fun child(root: IIOMetadataNode, name: String): IIOMetadataNode {
		for (i in 0 until root.length) if (root.item(i).nodeName == name) return root.item(i) as IIOMetadataNode
		return IIOMetadataNode(name).also(root::appendChild)
	}

	/**
	 * A 256-color image whose index 0 is transparent: pixels under half opacity become transparent, the rest
	 * map to a 6x7x6 color cube. Deterministic, unlike an adaptive palette search.
	 */
	private fun indexed(frame: RasterImage): BufferedImage {
		val reds = IntArray(256); val greens = IntArray(256); val blues = IntArray(256)
		var n = 1
		for (r in 0 until 6) for (g in 0 until 7) for (b in 0 until 6) {
			reds[n] = r * 255 / 5; greens[n] = g * 255 / 6; blues[n] = b * 255 / 5; n++
		}
		val model = java.awt.image.IndexColorModel(8, 256, reds.map(Int::toByte).toByteArray(), greens.map(Int::toByte).toByteArray(),
			blues.map(Int::toByte).toByteArray(), 0)
		val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_BYTE_INDEXED, model)
		val raster = image.raster
		for (y in 0 until frame.height) for (x in 0 until frame.width) {
			val argb = frame.argb[y * frame.width + x]
			val index = if ((argb ushr 24) < 128) 0 else {
				val r = (((argb shr 16) and 255) * 5 + 127) / 255
				val g = (((argb shr 8) and 255) * 6 + 127) / 255
				val b = ((argb and 255) * 5 + 127) / 255
				1 + (r * 7 + g) * 6 + b
			}
			raster.setSample(x, y, 0, index)
		}
		return image
	}

	private companion object {
		const val MAX_SHEET = 16384L
		const val MAX_FRAMES = 3600
	}
}
