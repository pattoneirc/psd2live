package io.github.psd2live.format.compile

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.stream.IntStream
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs

/**
 * An 8-bit RGBA PNG encoder for large images that compresses bands of rows in parallel.
 *
 * Each band of [BAND_ROWS] rows is filtered (per row, the filter whose output has the smallest sum of
 * absolute signed bytes, as common encoders choose) and deflated by its own compressor, ending on a sync
 * flush; the bands' deflate segments concatenate into one zlib stream whose Adler-32 is combined from the
 * bands'. The bands do not depend on the number of threads, so the bytes are the same on every machine with
 * the same zlib.
 */
public object PngEncoder {
	private const val BAND_ROWS = 64
	private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

	private class Band(val deflated: ByteArray, val adler: Int, val length: Long)

	/** [argb] (0xAARRGGBB, straight alpha, row by row) as a PNG file. */
	public fun encode(width: Int, height: Int, argb: IntArray): ByteArray {
		require(width > 0 && height > 0 && argb.size.toLong() == width.toLong() * height) { "Raster size mismatch" }
		val count = (height + BAND_ROWS - 1) / BAND_ROWS
		val bands = arrayOfNulls<Band>(count)
		IntStream.range(0, count).parallel().forEach { index ->
			val start = index * BAND_ROWS
			bands[index] = band(argb, width, start, minOf(height, start + BAND_ROWS))
		}
		var adler = 1
		for (band in bands) adler = combineAdler(adler, band!!.adler, band.length)
		val idat = ByteArrayOutputStream(bands.sumOf { it!!.deflated.size } + 16)
		// zlib header (deflate, 32K window, no preset dictionary), the bands, an empty final stored block, the checksum.
		idat.write(0x78); idat.write(0x01)
		for (band in bands) idat.write(band!!.deflated)
		idat.write(byteArrayOf(0x01, 0x00, 0x00, 0xff.toByte(), 0xff.toByte()))
		idat.write(ByteBuffer.allocate(4).putInt(adler).array())
		val out = ByteArrayOutputStream(idat.size() + 64)
		out.write(SIGNATURE)
		chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(6).put(0).put(0).put(0).array())
		chunk(out, "IDAT", idat.toByteArray())
		chunk(out, "IEND", ByteArray(0))
		return out.toByteArray()
	}

	/** Rows [start] until [end] filtered and deflated on their own. */
	private fun band(argb: IntArray, width: Int, start: Int, end: Int): Band {
		val stride = width * 4
		val raw = ByteArray((stride + 1) * (end - start))
		var previous = if (start == 0) ByteArray(stride) else row(argb, width, start - 1, ByteArray(stride))
		var current = ByteArray(stride)
		val candidates = Array(5) { ByteArray(stride) }
		for (y in start until end) {
			row(argb, width, y, current)
			var best = 0; var bestScore = Long.MAX_VALUE
			for (filter in 0 until 5) {
				val score = filter(filter, current, previous, candidates[filter])
				if (score < bestScore) { bestScore = score; best = filter }
			}
			val offset = (y - start) * (stride + 1)
			raw[offset] = best.toByte()
			candidates[best].copyInto(raw, offset + 1)
			val swap = previous; previous = current; current = swap
		}
		val adler = Adler32().apply { update(raw) }.value.toInt()
		val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
		try {
			deflater.setInput(raw)
			val output = ByteArrayOutputStream(raw.size / 4 + 64)
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val written = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
				output.write(buffer, 0, written)
				// The flush is complete once it no longer fills the buffer.
				if (written < buffer.size) break
			}
			return Band(output.toByteArray(), adler, raw.size.toLong())
		} finally {
			deflater.end()
		}
	}

	private fun row(argb: IntArray, width: Int, y: Int, into: ByteArray): ByteArray {
		var o = 0
		for (x in y * width until (y + 1) * width) {
			val p = argb[x]
			into[o] = (p shr 16).toByte(); into[o + 1] = (p shr 8).toByte(); into[o + 2] = p.toByte(); into[o + 3] = (p ushr 24).toByte()
			o += 4
		}
		return into
	}

	/** [current] filtered by PNG filter [type] against [previous] into [out]; returns the sum of absolute signed bytes. */
	private fun filter(type: Int, current: ByteArray, previous: ByteArray, out: ByteArray): Long {
		var sum = 0L
		for (i in current.indices) {
			val x = current[i].toInt() and 0xff
			val a = if (i >= 4) current[i - 4].toInt() and 0xff else 0
			val b = previous[i].toInt() and 0xff
			val c = if (i >= 4) previous[i - 4].toInt() and 0xff else 0
			val predicted = when (type) {
				0 -> 0
				1 -> a
				2 -> b
				3 -> (a + b) ushr 1
				else -> {
					val p = a + b - c
					val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
					if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
				}
			}
			val v = (x - predicted).toByte()
			out[i] = v
			sum += abs(v.toInt())
		}
		return sum
	}

	private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
		val typeBytes = type.encodeToByteArray()
		val crc = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
		out.write(ByteBuffer.allocate(8).putInt(data.size).put(typeBytes).array())
		out.write(data)
		out.write(ByteBuffer.allocate(4).putInt(crc).array())
	}

	/** The Adler-32 of two byte runs from each one's checksum and the second's length. */
	internal fun combineAdler(first: Int, second: Int, secondLength: Long): Int {
		val base = 65521L
		val remainder = secondLength % base
		var sum1 = first.toLong() and 0xffff
		var sum2 = (remainder * sum1) % base
		sum1 += (second.toLong() and 0xffff) + base - 1
		sum2 += ((first.toLong() ushr 16) and 0xffff) + ((second.toLong() ushr 16) and 0xffff) + base - remainder
		if (sum1 >= base) sum1 -= base
		if (sum1 >= base) sum1 -= base
		if (sum2 >= base shl 1) sum2 -= base shl 1
		if (sum2 >= base) sum2 -= base
		return (sum1 or (sum2 shl 16)).toInt()
	}
}
