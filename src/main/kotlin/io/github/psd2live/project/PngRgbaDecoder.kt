package io.github.psd2live.project

import java.nio.ByteBuffer
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Decodes the PNG files the working store writes - 8-bit RGBA or RGB, not interlaced - straight to RGBA bytes,
 * several times faster than `ImageIO.read` followed by a copy out of the image. Any other PNG (another bit depth
 * or colour type, a palette, transparency or colour-space chunks, an unknown critical chunk) gives null, and the
 * caller reads it through ImageIO; so does a stream that ends early. Callers check the result against its digest.
 */
internal object PngRgbaDecoder {
	private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
	/** Ancillary chunks that do not change the decoded pixels. */
	private val IGNORED = setOf("tEXt", "zTXt", "iTXt", "tIME", "pHYs", "bKGD", "sRGB", "sPLT", "hIST")

	fun decode(png: ByteArray, expectedWidth: Int, expectedHeight: Int): ByteArray? {
		val buffer = ByteBuffer.wrap(png)
		if (png.size < 8 + 25 || !png.copyOfRange(0, 8).contentEquals(SIGNATURE)) return null
		buffer.position(8)
		var width = 0; var height = 0; var channels = 0
		val idat = ArrayList<IntArray>()
		var first = true
		while (true) {
			if (buffer.remaining() < 12) return null
			val length = buffer.int
			if (length < 0 || length > buffer.remaining() - 8) return null
			val type = String(png, buffer.position(), 4, Charsets.ISO_8859_1)
			val data = buffer.position() + 4
			buffer.position(data + length + 4)
			if (first) {
				if (type != "IHDR" || length != 13) return null
				first = false
				val header = ByteBuffer.wrap(png, data, 13)
				width = header.int; height = header.int
				val depth = png[data + 8].toInt(); val colour = png[data + 9].toInt()
				val compression = png[data + 10].toInt(); val filter = png[data + 11].toInt(); val interlace = png[data + 12].toInt()
				if (depth != 8 || compression != 0 || filter != 0 || interlace != 0) return null
				channels = when (colour) { 6 -> 4; 2 -> 3; else -> return null }
				if (width != expectedWidth || height != expectedHeight || width <= 0 || height <= 0) return null
				continue
			}
			when (type) {
				"IDAT" -> idat.add(intArrayOf(data, length))
				"IEND" -> break
				in IGNORED -> {}
				else -> return null
			}
		}
		if (idat.isEmpty()) return null
		return inflate(png, idat, width, height, channels)
	}

	private fun inflate(png: ByteArray, idat: List<IntArray>, width: Int, height: Int, channels: Int): ByteArray? {
		val stride = Math.multiplyExact(width, channels)
		val out = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), 4))
		var row = ByteArray(stride + 1)
		var previous = ByteArray(stride + 1)
		val inflater = Inflater()
		try {
			var chunk = 0
			for (y in 0 until height) {
				var filled = 0
				while (filled < row.size) {
					val count = inflater.inflate(row, filled, row.size - filled)
					filled += count
					if (count == 0) {
						if (inflater.finished() || inflater.needsDictionary()) return null
						if (inflater.needsInput()) {
							if (chunk == idat.size) return null
							val (offset, length) = idat[chunk++].let { it[0] to it[1] }
							inflater.setInput(png, offset, length)
						}
					}
				}
				if (!unfilter(row, previous, channels)) return null
				var o = y * width * 4
				if (channels == 4) System.arraycopy(row, 1, out, o, stride)
				else {
					var i = 1
					for (x in 0 until width) {
						out[o] = row[i]; out[o + 1] = row[i + 1]; out[o + 2] = row[i + 2]; out[o + 3] = -1
						o += 4; i += 3
					}
				}
				val swap = previous; previous = row; row = swap
			}
			return out
		} catch (_: DataFormatException) {
			return null
		} finally {
			inflater.end()
		}
	}

	/** Reverses the row's filter in place; [previous] is the unfiltered row above (zeros for the first). */
	private fun unfilter(row: ByteArray, previous: ByteArray, bpp: Int): Boolean {
		val n = row.size
		when (row[0].toInt()) {
			0 -> {}
			1 -> for (i in 1 + bpp until n) row[i] = (row[i] + row[i - bpp]).toByte()
			2 -> for (i in 1 until n) row[i] = (row[i] + previous[i]).toByte()
			3 -> {
				for (i in 1 until minOf(1 + bpp, n)) row[i] = (row[i] + ((previous[i].toInt() and 255) ushr 1)).toByte()
				for (i in 1 + bpp until n) row[i] = (row[i] + (((row[i - bpp].toInt() and 255) + (previous[i].toInt() and 255)) ushr 1)).toByte()
			}
			4 -> {
				for (i in 1 until minOf(1 + bpp, n)) row[i] = (row[i] + previous[i]).toByte()
				for (i in 1 + bpp until n) {
					val a = row[i - bpp].toInt() and 255
					val b = previous[i].toInt() and 255
					val c = previous[i - bpp].toInt() and 255
					val p = a + b - c
					val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
					val predictor = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
					row[i] = (row[i] + predictor).toByte()
				}
			}
			else -> return false
		}
		return true
	}
}
