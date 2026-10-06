package io.github.psd2live.core

import org.umamo.format.art.SourceArt
import org.umamo.format.art.isEffectivelyVisible
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage

object PreviewRenderer {
	/** What a composite reads of one layer; the raster array counts by identity (arrays compare by reference). */
	private data class LayerKey(val rgba: ByteArray, val width: Int, val left: Int, val top: Int, val opacity: Float)
	private data class CompositeKey(val width: Int, val height: Int, val layers: List<LayerKey>)

	private val composites = object : LinkedHashMap<CompositeKey, java.lang.ref.SoftReference<BufferedImage>>(4, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CompositeKey, java.lang.ref.SoftReference<BufferedImage>>?) = size > 3
	}

	/**
	 * The flattened artwork. A rebuild composites the generation input and the current source more than once,
	 * so recent results are shared: the returned image must not be modified.
	 */
	fun composite(source: SourceArt): BufferedImage {
		val key = CompositeKey(source.widthPx, source.heightPx, source.layers.filter { source.isEffectivelyVisible(it) && it.opacity > 0f }
			.map { LayerKey(it.raster.rgba, it.raster.width, it.bounds.left, it.bounds.top, it.opacity.coerceIn(0f, 1f)) })
		synchronized(composites) { composites[key]?.get()?.let { return it } }
		return compositeOf(source).also { image -> synchronized(composites) { composites[key] = java.lang.ref.SoftReference(image) } }
	}

	private fun compositeOf(source: SourceArt): BufferedImage {
		val canvas = BufferedImage(source.widthPx, source.heightPx, BufferedImage.TYPE_INT_ARGB)
		val graphics = canvas.createGraphics()
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
		try {
			for (layer in source.layers) {
				if (!source.isEffectivelyVisible(layer) || layer.opacity <= 0f) continue
				graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, layer.opacity.coerceIn(0f, 1f))
				graphics.drawImage(rasterImage(layer.raster.width, layer.raster.height, layer.raster.rgba), layer.bounds.left, layer.bounds.top, null)
			}
		} finally {
			graphics.dispose()
		}
		return canvas
	}

	fun rasterImage(width: Int, height: Int, rgba: ByteArray): BufferedImage {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		val argb = IntArray(width * height)
		for (index in argb.indices) {
			val offset = index * 4
			val red = rgba[offset].toInt() and 0xff
			val green = rgba[offset + 1].toInt() and 0xff
			val blue = rgba[offset + 2].toInt() and 0xff
			val alpha = rgba[offset + 3].toInt() and 0xff
			argb[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
		}
		image.setRGB(0, 0, width, height, argb, 0, width)
		return image
	}
}

