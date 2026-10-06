package io.github.psd2live.format.compile

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Deterministic resampling of straight-alpha RGBA8888 rasters, separable per axis and done on
 * premultiplied values so transparent pixels never bleed their colour into the result.
 *
 * Each output pixel `i` covers the source interval `[origin + i * step, origin + (i + 1) * step)`.
 * Where the step is at least one source pixel (shrinking) the pixel is the area average of that
 * interval; where it is smaller (enlarging) the pixel is the bilinear sample at its centre. Source
 * pixels outside the raster read as transparent, so an interval hanging over the edge fades out.
 * The arithmetic is plain float math in a fixed order, so equal inputs give equal bytes on every JVM.
 */
public object RasterResample {
	/** [rgba] ([width] x [height]) stretched onto [newWidth] x [newHeight]. Returns [rgba] itself when the size is unchanged. */
	public fun resize(rgba: ByteArray, width: Int, height: Int, newWidth: Int, newHeight: Int): ByteArray {
		if (newWidth == width && newHeight == height) return rgba
		return resample(rgba, width, height, newWidth, newHeight,
			0.0, width.toDouble() / newWidth, 0.0, height.toDouble() / newHeight)
	}

	/**
	 * A [newWidth] x [newHeight] raster whose pixel (i, j) covers the source rectangle starting at
	 * ([originX] + i * [stepX], [originY] + j * [stepY]) of size [stepX] x [stepY] source pixels.
	 */
	public fun resample(
		rgba: ByteArray, width: Int, height: Int, newWidth: Int, newHeight: Int,
		originX: Double, stepX: Double, originY: Double, stepY: Double,
	): ByteArray {
		require(width >= 0 && height >= 0 && newWidth >= 0 && newHeight >= 0) { "Raster sizes must not be negative" }
		require(rgba.size.toLong() == width.toLong() * height * 4) { "RGBA buffer does not match its size" }
		require(stepX.isFinite() && stepY.isFinite() && stepX > 0.0 && stepY > 0.0) { "Resample step must be positive" }
		val out = ByteArray(Math.multiplyExact(Math.multiplyExact(newWidth, newHeight), 4))
		if (width == 0 || height == 0 || newWidth == 0 || newHeight == 0) return out
		val columns = weights(width, newWidth, originX, stepX)
		val rows = weights(height, newHeight, originY, stepY)
		// Premultiplied source, read lazily per row through the horizontal pass.
		val horizontal = FloatArray(Math.multiplyExact(Math.multiplyExact(newWidth, height), 4))
		val line = FloatArray(width * 4)
		for (y in 0 until height) {
			if (Thread.currentThread().isInterrupted) throw InterruptedException()
			val base = y * width * 4
			for (x in 0 until width) {
				val offset = base + x * 4
				val alpha = (rgba[offset + 3].toInt() and 0xff) / 255f
				line[x * 4] = (rgba[offset].toInt() and 0xff) * alpha
				line[x * 4 + 1] = (rgba[offset + 1].toInt() and 0xff) * alpha
				line[x * 4 + 2] = (rgba[offset + 2].toInt() and 0xff) * alpha
				line[x * 4 + 3] = alpha
			}
			val target = y * newWidth * 4
			for (i in 0 until newWidth) {
				val w = columns[i]
				var r = 0f; var g = 0f; var b = 0f; var a = 0f
				var k = 0
				while (k < w.indices.size) {
					val source = w.indices[k] * 4; val weight = w.weights[k]
					r += line[source] * weight; g += line[source + 1] * weight; b += line[source + 2] * weight; a += line[source + 3] * weight
					k++
				}
				val o = target + i * 4
				horizontal[o] = r; horizontal[o + 1] = g; horizontal[o + 2] = b; horizontal[o + 3] = a
			}
		}
		for (j in 0 until newHeight) {
			if (Thread.currentThread().isInterrupted) throw InterruptedException()
			val w = rows[j]
			for (i in 0 until newWidth) {
				var r = 0f; var g = 0f; var b = 0f; var a = 0f
				var k = 0
				while (k < w.indices.size) {
					val source = (w.indices[k] * newWidth + i) * 4; val weight = w.weights[k]
					r += horizontal[source] * weight; g += horizontal[source + 1] * weight
					b += horizontal[source + 2] * weight; a += horizontal[source + 3] * weight
					k++
				}
				val o = (j * newWidth + i) * 4
				val alpha = a.coerceIn(0f, 1f)
				val alphaByte = (alpha * 255f).roundToInt().coerceIn(0, 255)
				if (alphaByte == 0) continue
				out[o] = (r / a).roundToInt().coerceIn(0, 255).toByte()
				out[o + 1] = (g / a).roundToInt().coerceIn(0, 255).toByte()
				out[o + 2] = (b / a).roundToInt().coerceIn(0, 255).toByte()
				out[o + 3] = alphaByte.toByte()
			}
		}
		return out
	}

	private class Weights(val indices: IntArray, val weights: FloatArray)

	private fun weights(size: Int, count: Int, origin: Double, step: Double): Array<Weights> = Array(count) { i ->
		val start = origin + i * step
		val end = start + step
		if (step >= 1.0) {
			// Area average of [start, end), over the part inside the raster; the rest counts as transparent.
			val first = max(0, floor(start).toInt())
			val last = min(size - 1, floor(end - 1e-9).toInt())
			if (last < first) return@Array Weights(IntArray(0), FloatArray(0))
			val indices = IntArray(last - first + 1) { first + it }
			val weights = FloatArray(indices.size) { k ->
				val p = indices[k].toDouble()
				((min(end, p + 1.0) - max(start, p)) / step).toFloat()
			}
			Weights(indices, weights)
		} else {
			// Bilinear at the centre, with the raster edge clamped; a centre outside the raster is transparent.
			val centre = (start + end) * 0.5
			if (centre < 0.0 || centre > size) return@Array Weights(IntArray(0), FloatArray(0))
			val position = (centre - 0.5).coerceIn(0.0, (size - 1).toDouble())
			val left = floor(position).toInt()
			val right = min(size - 1, left + 1)
			val fraction = (position - left).toFloat()
			if (right == left || fraction == 0f) Weights(intArrayOf(left), floatArrayOf(1f))
			else Weights(intArrayOf(left, right), floatArrayOf(1f - fraction, fraction))
		}
	}
}
