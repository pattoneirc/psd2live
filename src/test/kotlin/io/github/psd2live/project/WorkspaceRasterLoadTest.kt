package io.github.psd2live.project

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.RasterDigest
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.history.WorkspaceHistoryTree
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.*

/** Stored rasters load with the bytes the per-pixel `getRGB` read gave, and opening checks assets without decoding them. */
class WorkspaceRasterLoadTest {
	@TempDir lateinit var temporary: Path

	/** The decode loadRaster used before the bulk read. */
	private fun perPixel(image: BufferedImage): ByteArray {
		val bytes = ByteArray(image.width * image.height * 4)
		for (y in 0 until image.height) for (x in 0 until image.width) {
			val pixel = image.getRGB(x, y); val i = (y * image.width + x) * 4
			bytes[i] = (pixel ushr 16).toByte(); bytes[i + 1] = (pixel ushr 8).toByte()
			bytes[i + 2] = pixel.toByte(); bytes[i + 3] = (pixel ushr 24).toByte()
		}
		return bytes
	}

	private fun filled(image: BufferedImage, random: Random): BufferedImage {
		val raster = image.raster
		val samples = IntArray(raster.numBands)
		for (y in 0 until image.height) for (x in 0 until image.width) {
			for (b in samples.indices) samples[b] = random.nextInt(1 shl raster.sampleModel.getSampleSize(b))
			// Some fully transparent and some opaque pixels, whose colour channels must survive as stored.
			if (image.colorModel.hasAlpha() && x % 5 == 0) samples[samples.lastIndex] = if (y % 2 == 0) 0 else (1 shl raster.sampleModel.getSampleSize(samples.lastIndex)) - 1
			raster.setPixel(x, y, samples)
		}
		return image
	}

	private fun images(width: Int, height: Int): Map<String, BufferedImage> {
		val random = Random(11)
		val gray = ColorSpace.getInstance(ColorSpace.CS_GRAY)
		val grayAlpha = ComponentColorModel(gray, true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE)
		val palette = IndexColorModel(8, 256, ByteArray(256) { it.toByte() }, ByteArray(256) { (it * 7).toByte() },
			ByteArray(256) { (255 - it).toByte() }, ByteArray(256) { (it * 3).toByte() })
		return linkedMapOf(
			"4BYTE_ABGR" to BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR),
			"INT_ARGB" to BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB),
			"3BYTE_BGR" to BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR),
			"INT_RGB" to BufferedImage(width, height, BufferedImage.TYPE_INT_RGB),
			"BYTE_GRAY" to BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY),
			"USHORT_GRAY" to BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY),
			"gray+alpha" to BufferedImage(grayAlpha, grayAlpha.createCompatibleWritableRaster(width, height), false, null),
			"indexed" to BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, palette),
			"4BYTE_ABGR_PRE" to BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR_PRE),
		).mapValues { (_, image) -> filled(image, random) }
	}

	@Test fun bulkDecodeEqualsPerPixelDecode() {
		for ((name, image) in images(37, 23)) {
			assertContentEquals(perPixel(image), WorkspaceStore.rgbaOf(image), name)
			// What the PNG reader gives back for the same pixels (RGBA, RGB, gray, gray+alpha, palette, 16-bit).
			val png = ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "png", it), name) }.toByteArray()
			val read = ImageIO.read(ByteArrayInputStream(png))
			assertContentEquals(perPixel(read), WorkspaceStore.rgbaOf(read), "$name as PNG (type ${read.type})")
		}
		// Wider than one block of rows, and a sub-image whose raster is offset in its parent's buffer.
		val wide = filled(BufferedImage(70_000, 2, BufferedImage.TYPE_BYTE_GRAY), Random(3))
		assertContentEquals(perPixel(wide), WorkspaceStore.rgbaOf(wide))
		val parent = filled(BufferedImage(40, 30, BufferedImage.TYPE_4BYTE_ABGR), Random(5))
		val sub = parent.getSubimage(3, 4, 20, 10)
		assertContentEquals(perPixel(sub), WorkspaceStore.rgbaOf(sub))
	}

	/** A PNG of [samples] (rows of [channels]-byte pixels) whose row y uses filter `y % 5`, its stream split into several IDAT chunks. */
	private fun png(width: Int, height: Int, channels: Int, samples: ByteArray, extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
		val stride = width * channels
		val filtered = ByteArrayOutputStream()
		for (y in 0 until height) {
			val filter = y % 5
			filtered.write(filter)
			for (i in 0 until stride) {
				val x = samples[y * stride + i].toInt() and 255
				val a = if (i >= channels) samples[y * stride + i - channels].toInt() and 255 else 0
				val b = if (y > 0) samples[(y - 1) * stride + i].toInt() and 255 else 0
				val c = if (y > 0 && i >= channels) samples[(y - 1) * stride + i - channels].toInt() and 255 else 0
				val predictor = when (filter) {
					1 -> a; 2 -> b; 3 -> (a + b) / 2
					4 -> { val p = a + b - c; val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c); if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
					else -> 0
				}
				filtered.write((x - predictor) and 255)
			}
		}
		val deflated = ByteArrayOutputStream().also { out -> java.util.zip.DeflaterOutputStream(out).use { it.write(filtered.toByteArray()) } }.toByteArray()
		val out = ByteArrayOutputStream()
		out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
		fun chunk(type: String, data: ByteArray) {
			val head = java.nio.ByteBuffer.allocate(8).putInt(data.size).put(type.toByteArray(Charsets.ISO_8859_1)).array()
			val crc = java.util.zip.CRC32().apply { update(head, 4, 4); update(data) }
			out.write(head); out.write(data); out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
		}
		chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(if (channels == 4) 6 else 2).put(0).put(0).put(0).array())
		extra.forEach { (type, data) -> chunk(type, data) }
		var at = 0
		while (at < deflated.size) { val n = minOf(97, deflated.size - at); chunk("IDAT", deflated.copyOfRange(at, at + n)); at += n }
		chunk("IEND", ByteArray(0))
		return out.toByteArray()
	}

	@Test fun directPngDecodeEqualsImageIo() {
		val random = Random(21)
		for (channels in listOf(4, 3)) for ((width, height) in listOf(1 to 1, 2 to 7, 33 to 21)) {
			// Noise, smooth gradients and flat runs, so the filters see every kind of neighbourhood.
			val samples = ByteArray(width * height * channels) { i -> when ((i / channels / width) % 3) { 0 -> random.nextInt().toByte(); 1 -> (i * 3).toByte(); else -> 77 } }
			val encoded = png(width, height, channels, samples)
			val expected = perPixel(ImageIO.read(ByteArrayInputStream(encoded)))
			assertContentEquals(expected, PngRgbaDecoder.decode(encoded, width, height), "$channels channels, ${width}x$height")
			// Text chunks do not change the pixels.
			assertContentEquals(expected, PngRgbaDecoder.decode(png(width, height, channels, samples, listOf("tEXt" to "Software\u0000test".toByteArray())), width, height))
		}
		// What the store writes.
		val stored = filled(BufferedImage(29, 13, BufferedImage.TYPE_INT_ARGB), Random(4))
		val written = ByteArrayOutputStream().also { ImageIO.write(stored, "png", it) }.toByteArray()
		assertContentEquals(perPixel(stored), PngRgbaDecoder.decode(written, 29, 13))
		// Anything else is left to ImageIO: other dimensions, colour-space chunks, palettes, gray, a cut stream.
		val samples = ByteArray(5 * 4 * 4) { it.toByte() }
		assertNull(PngRgbaDecoder.decode(png(5, 4, 4, samples), 4, 5))
		assertNull(PngRgbaDecoder.decode(png(5, 4, 4, samples, listOf("gAMA" to byteArrayOf(0, 0, -79, -113))), 5, 4))
		assertNull(PngRgbaDecoder.decode(png(5, 4, 3, ByteArray(60), listOf("tRNS" to ByteArray(6))), 5, 4))
		for (type in listOf(BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_BYTE_INDEXED)) {
			val other = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(5, 4, type), "png", it) }.toByteArray()
			assertNull(PngRgbaDecoder.decode(other, 5, 4))
		}
		val whole = png(33, 21, 4, ByteArray(33 * 21 * 4).also(random::nextBytes))
		assertNull(PngRgbaDecoder.decode(whole.copyOf(whole.size / 2), 33, 21))
	}

	private fun layer(index: Int, raster: LayerRaster) = WorkspaceSourceLayer(LayerId("layer-$index"), "Layer $index", "", SourceLayerKind.Raster,
		true, index, LayerBounds(0, 0, raster.width, raster.height), 1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)

	@Test fun historyRastersLoadInParallelAndSeedTheirDigests() {
		val random = Random(9)
		fun raster() = LayerRaster(31, 17, ByteArray(31 * 17 * 4).also(random::nextBytes))
		val root = WorkspaceDocument(WorkspaceSourceArt(31, 17, List(6) { layer(it, raster()) }, emptyList()),
			emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty)
		val tree = WorkspaceHistoryTree(root, WorkspaceRevisions.of(root), WorkspaceRevisions.of(root))
		var document = root
		repeat(5) { step ->
			val layers = document.source.layers.mapIndexed { i, it -> if (i == step) layer(i, raster()) else it }
			document = document.copy(source = WorkspaceSourceArt(31, 17, layers, emptyList()),
				generationSource = if (step == 3) WorkspaceSourceArt(31, 17, listOf(layer(9, raster())), emptyList()) else document.generationSource)
			val revision = WorkspaceRevisions.of(document)
			tree.commit(tree.head().node.id, document, revision, revision, "Edit $step", "user")
		}
		val store = WorkspaceStore(temporary.resolve("store"))
		store.persistHistory("project", tree.state())
		val loaded = WorkspaceStore(temporary.resolve("store")).loadHistory("project")!!
		val expected = tree.state().selections.associate { it.node.id to it.snapshot }
		for (selection in loaded.state().selections) {
			val original = expected.getValue(selection.node.id)
			assertEquals(selection.node.revisionId, WorkspaceRevisions.of(selection.snapshot))
			val sources = listOfNotNull(selection.snapshot.source, selection.snapshot.generationSource)
			val originals = listOfNotNull(original.source, original.generationSource)
			for ((source, before) in sources.zip(originals)) for ((layer, was) in source.layers.zip(before.layers)) {
				assertContentEquals(was.raster.rgba, layer.raster.rgba)
				assertEquals(RasterDigest.compute(layer.raster.rgba), RasterDigest.of(layer.raster.rgba))
			}
		}
		// The same raster across revisions is one array.
		val heads = loaded.state().selections.map { it.snapshot.source.layers.last().raster.rgba }
		assertTrue(heads.all { it === heads.first() })
	}

	@Test fun aCorruptRasterFailsTheHistoryLoad() {
		val document = WorkspaceDocument(WorkspaceSourceArt(4, 4, List(3) { layer(it, LayerRaster(4, 4, ByteArray(64) { b -> (b * it).toByte() })) }, emptyList()),
			emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty)
		val tree = WorkspaceHistoryTree(document, WorkspaceRevisions.of(document), WorkspaceRevisions.of(document))
		WorkspaceStore(temporary.resolve("store")).persistHistory("project", tree.state())
		val blobs = temporary.resolve("store/project/blobs")
		val victim = Files.list(blobs).use { it.sorted().toList() }[1]
		val other = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, 4, 4, IntArray(16) { 0x7f123456 }, 0, 4) }, "png", it) }.toByteArray()
		Files.write(victim, other)
		val failure = assertFailsWith<IllegalArgumentException> { WorkspaceStore(temporary.resolve("store")).loadHistory("project") }
		assertTrue(failure.message!!.startsWith("Stored raster hash mismatch"), failure.message)
	}

	@Test fun catalogValidationReadsAssetMetadataOnly() {
		val store = WorkspaceStore(temporary.resolve("store"))
		val placement = WorkspaceCanvasPlacement("canvas", Bounds(0f, 0f, 8f, 8f), 8, 8, 1f, 1f, "view")
		store.persistAsset("project", WorkspacePngAsset(WorkspaceImportedPngAsset("asset-1", "sha", 8, 8, placement), ByteArray(256) { it.toByte() }))
		val catalog = WorkspaceAssetCatalog(setOf("asset-1"))
		store.validateAssetCatalog("project", catalog)
		val blob = Files.list(temporary.resolve("store/project/blobs")).use { it.toList() }.single()
		// Unreadable pixels are found when the asset is loaded, not when the catalog is checked.
		Files.write(blob, byteArrayOf(1, 2, 3))
		store.validateAssetCatalog("project", catalog)
		assertFails { store.loadAsset("project", "asset-1") }
		Files.delete(blob)
		assertFailsWith<IllegalArgumentException> { store.validateAssetCatalog("project", catalog) }
		assertFailsWith<IllegalArgumentException> { store.validateAssetCatalog("project", WorkspaceAssetCatalog(setOf("missing"))) }
	}
}
