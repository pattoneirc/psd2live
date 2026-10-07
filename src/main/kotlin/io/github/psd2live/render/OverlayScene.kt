package io.github.psd2live.render

/**
 * Guide geometry the GPU draws over the artwork, in paint order: each item is one draw, and a later item lies
 * over an earlier one, as the Java2D guides it replaces painted them.
 *
 * Coordinates are world units (the artwork's own space, y up), so the guides move with the camera exactly as
 * the artwork does. Sizes (widths, radii) are device pixels.
 */
class OverlayScene(val items: List<OverlayItem>) {
	companion object {
		val EMPTY = OverlayScene(emptyList())
	}
}

sealed interface OverlayItem

/**
 * Line segments in one colour and width.
 *
 * @property argb     Unpremultiplied ARGB.
 * @property width    Stroke width in device pixels.
 * @property segments x0, y0, x1, y1 per segment, world units.
 */
class LineBatch(val argb: Int, val width: Float, val segments: FloatArray) : OverlayItem

/**
 * Discs in one style.
 *
 * @property radius  Outer radius in device pixels.
 * @property ring    Width of the outer ring drawn in [strokeArgb]; 0 for a plain disc.
 * @property centers x, y per point, world units.
 */
class PointBatch(val fillArgb: Int, val strokeArgb: Int, val radius: Float, val ring: Float, val centers: FloatArray) : OverlayItem

/**
 * Closed outlines filled in one colour by the even-odd rule, so concave outlines and holes fill right. Where two
 * outlines of one batch overlap they cancel, as even-odd does; shapes meant to overlap go in separate batches.
 *
 * @property contours x, y per vertex of each closed outline, world units.
 */
class FillBatch(val argb: Int, val contours: List<FloatArray>) : OverlayItem

/**
 * A polyline stroked with round caps and joins, painting each pixel at most once, as a Java2D stroke does: its
 * translucent halo does not darken where the pieces meet.
 *
 * @property points x, y per vertex, world units.
 */
class PolylineBatch(val argb: Int, val width: Float, val points: FloatArray, val closed: Boolean) : OverlayItem

/** Pixels a [TextureQuad] samples. */
sealed interface QuadTexture

/**
 * A layer raster: straight (unpremultiplied) RGBA, top row first. The renderer uploads it once per [rgba] instance
 * while the view keeps drawing it.
 */
class RasterTexture(val width: Int, val height: Int, val rgba: ByteArray) : QuadTexture

/** An atlas page image, uploaded once per instance like the artwork pass's pages. */
class ImageTexture(val image: java.awt.image.BufferedImage) : QuadTexture

/**
 * [texture]'s area [u0]..[u1] x [v0]..[v1] (0..1, v down the image) stretched over the world rectangle from
 * ([x0], [y0]) to ([x1], [y1]), where ([x0], [y0]) takes (u0, v0).
 *
 * @property alpha   Opacity the texels are drawn with.
 * @property nearest Magnify texel by texel instead of smoothly, for a close look at single pixels.
 * @property clip    World rectangles (x0, y0, x1, y1 each) outside which nothing is drawn; null draws all of it.
 * @property corners The four world corners for a turned quad - those of (u0, v0), (u1, v0), (u1, v1), (u0, v1), x and y
 *   each - instead of the upright rectangle from ([x0], [y0]) to ([x1], [y1]).
 */
class TextureQuad(
	val texture: QuadTexture,
	val x0: Float, val y0: Float, val x1: Float, val y1: Float,
	val u0: Float = 0f, val v0: Float = 0f, val u1: Float = 1f, val v1: Float = 1f,
	val alpha: Float = 1f,
	val nearest: Boolean = false,
	val clip: FloatArray? = null,
	val corners: FloatArray? = null,
) : OverlayItem
