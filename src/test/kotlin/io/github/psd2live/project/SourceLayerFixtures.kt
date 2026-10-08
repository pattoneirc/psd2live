package io.github.psd2live.project

import org.umamo.format.art.*

/** A visible raster layer as an import yields it: opaque, unclipped, normal blend, no mask, not derived. */
internal fun sourceLayer(id: String, order: Int, bounds: LayerBounds, raster: LayerRaster, rect: LayerCanvasRect? = null, name: String = id) =
	WorkspaceSourceLayer(LayerId(id), name, "", SourceLayerKind.Raster, true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		raster, null, null, false, rect)

/** A [width] x [height] layer at the canvas origin, opaque only inside [boxes] (`x0, y0, x1, y1`, end exclusive). */
internal fun islandLayer(id: String, order: Int, width: Int, height: Int, vararg boxes: IntArray): WorkspaceSourceLayer {
	val rgba = ByteArray(width * height * 4)
	for (box in boxes) for (y in box[1] until box[3]) for (x in box[0] until box[2]) {
		val offset = (y * width + x) * 4
		rgba[offset] = 120; rgba[offset + 1] = 90; rgba[offset + 2] = 60; rgba[offset + 3] = 255.toByte()
	}
	return sourceLayer(id, order, LayerBounds(0, 0, width, height), LayerRaster(width, height, rgba))
}

/**
 * RGBA of an opaque disc centred in a [size]² square, its squared radius [fill] times the inscribed circle's, coloured
 * by position so that resampling and misplacement show.
 */
internal fun discPixels(size: Int, fill: Float = 0.81f): ByteArray = ByteArray(size * size * 4).also { rgba ->
	val r = size / 2f
	for (y in 0 until size) for (x in 0 until size) {
		val dx = x + 0.5f - r; val dy = y + 0.5f - r
		if (dx * dx + dy * dy > r * r * fill) continue
		val o = (y * size + x) * 4
		rgba[o] = (40 + 160 * x / size).toByte(); rgba[o + 1] = 60; rgba[o + 2] = (200 - 120 * y / size).toByte(); rgba[o + 3] = -1
	}
}

internal fun discRaster(size: Int, fill: Float = 0.81f) = LayerRaster(size, size, discPixels(size, fill))
