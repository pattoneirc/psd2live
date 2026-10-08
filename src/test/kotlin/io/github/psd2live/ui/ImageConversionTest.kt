package io.github.psd2live.ui

import io.github.psd2live.ui.utils.toSkiaImage
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageConversionTest {
	@Test fun bulkConversionKeepsEveryPixel() {
		for (type in listOf(BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_INT_RGB,
			BufferedImage.TYPE_4BYTE_ABGR)) {
			val source = BufferedImage(7, 5, type)
			for (y in 0 until 5) for (x in 0 until 7) source.setRGB(x, y, argb(x, y))
			assertSame(source, source.toSkiaImage().let(::pixels), "type $type")
			// A view into a larger raster has its own stride and origin.
			val view = source.getSubimage(2, 1, 4, 3)
			assertSame(view, view.toSkiaImage().let(::pixels), "subimage of type $type")
		}
	}

	private fun argb(x: Int, y: Int): Int = ((x * 37 + y * 11) % 256 shl 24) or (x * 40 shl 16) or (y * 50 shl 8) or (x * y * 7 % 256)

	private fun pixels(image: org.jetbrains.skia.Image): Bitmap {
		val bitmap = Bitmap().apply { allocPixels(ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)) }
		image.readPixels(bitmap, 0, 0)
		return bitmap
	}

	private fun assertSame(expected: BufferedImage, actual: Bitmap, label: String) {
		assertEquals(expected.width, actual.width, label)
		assertEquals(expected.height, actual.height, label)
		for (y in 0 until expected.height) for (x in 0 until expected.width) {
			val want = expected.getRGB(x, y)
			val got = actual.getColor(x, y)
			// Premultiplied sources lose precision at low alpha; compare channels within rounding.
			for (shift in intArrayOf(24, 16, 8, 0)) {
				val a = want ushr shift and 0xff
				val b = got ushr shift and 0xff
				val tolerance = if (shift == 24 || (want ushr 24) == 255) 0 else 255 / maxOf(1, want ushr 24) + 1
				kotlin.test.assertTrue(kotlin.math.abs(a - b) <= tolerance, "$label ($x, $y): ${Integer.toHexString(want)} != ${Integer.toHexString(got)}")
			}
		}
	}
}
