package io.github.psd2live.format.compile

import java.util.zip.Adler32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PngEncoderTest {
	@Test fun bandsDecodeToThePixelsAndEncodeTheSameEveryTime() {
		// Over two bands, with alpha 0 pixels keeping their color and every filter's kind of content.
		val width = 37; val height = 150
		val random = java.util.Random(7)
		val argb = IntArray(width * height) { i ->
			val x = i % width; val y = i / width
			when {
				y < 40 -> (x * 7 shl 24) or (y * 3 shl 16) or (x * y and 0xff shl 8) or (x + y and 0xff)
				y < 90 -> random.nextInt()
				else -> if (x < 10) 0x00ff8040 else 0xff102030.toInt()
			}
		}
		val png = PngEncoder.encode(width, height, argb)
		val image = ImageIO.read(png.inputStream())
		assertEquals(width, image.width); assertEquals(height, image.height)
		assertContentEquals(argb, image.getRGB(0, 0, width, height, null, 0, width))
		assertContentEquals(png, PngEncoder.encode(width, height, argb))
	}

	@Test fun adlerCombinesLikeOneRun() {
		val first = ByteArray(70_000) { (it * 31).toByte() }; val second = ByteArray(123_457) { (it * 17 + 3).toByte() }
		fun adler(vararg runs: ByteArray) = Adler32().apply { runs.forEach(::update) }.value.toInt()
		assertEquals(adler(first, second), PngEncoder.combineAdler(adler(first), adler(second), second.size.toLong()))
	}
}
