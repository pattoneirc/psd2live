package io.github.psd2live.ui

import io.github.psd2live.core.RigCanvasSupport

import io.github.psd2live.core.CanvasViewport

import io.github.psd2live.ui.utils.toSkiaImage
import io.github.psd2live.core.PackedAtlas
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.textureLayer
import org.jetbrains.skia.*
import org.umamo.render.eval.DeformedGeometry
import io.github.psd2live.render.ArtworkDraw
import io.github.psd2live.render.ArtworkDrawList
import io.github.psd2live.render.ArtworkOptions
import io.github.psd2live.render.HOVER_TINT_STRENGTH
import org.umamo.runtime.model.DrawableId


/**
 * Draw the editing texture channel on Compose's Skia canvas. A masked part draws into a layer its masks' union
 * then trims with DST_IN, instead of clipping to a path of every mask triangle.
 * The pages are converted on the first paint: a canvas the GPU draws makes one per atlas and never paints with it.
 */
internal class SkiaRigPainter(private val atlas: PackedAtlas) : AutoCloseable {
    private class Pages(val images: List<Image>, val shaders: List<Shader>)
    private var pages: Pages? = null
    private fun pages(): Pages = pages ?: atlas.pages.map { it.image.toSkiaImage() }.let { images ->
        Pages(images, images.map { it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null) })
    }.also { pages = it }

    fun paint(
        canvas: Canvas,
        model: RigPreviewModel,
        geometry: DeformedGeometry,
        viewport: CanvasViewport,
        alpha: Float = 1f,
        visibleLayerIds: Set<String>? = null,
        drawOrderOverrides: Map<String, Float> = emptyMap(),
        dimUnselected: Boolean = false,
        highlightedLayerIds: Set<String>? = null,
        dimmedAlphaMultiplier: Float = 0.22f,
        tintLayerIds: Set<String>? = null,
        tintColor: Int = 0,
        tintAlpha: Float = HOVER_TINT_STRENGTH,
        sources: SourcePixelImages? = null,
    ) = paint(canvas, model, geometry, viewport, ArtworkDrawList.build(model, geometry, ArtworkOptions(
        alpha, visibleLayerIds, drawOrderOverrides, dimUnselected, highlightedLayerIds, dimmedAlphaMultiplier,
        tintLayerIds, tintColor, tintAlpha,
    )), sources)

    /**
     * Draws [draws], the list the GPU renderer draws too, so the two paths cannot disagree about what shows.
     * With [sources], each mesh samples its layer's own raster instead of the atlas tile (source pixels).
     */
    fun paint(canvas: Canvas, model: RigPreviewModel, geometry: DeformedGeometry, viewport: CanvasViewport, draws: List<ArtworkDraw>,
              sources: SourcePixelImages? = null) {
        val pages = pages()
        val images = pages.images
        val shaders = pages.shaders
        val byId = model.rig.puppet.drawables.associateBy { it.id }
        val maskPaint = Paint()
        val solidPaint = Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = false }
        Paint().use { paint ->
            try {
                for (draw in draws) {
                    val drawable = byId[draw.drawableId] ?: continue
                    val mesh = drawable.mesh ?: continue
                    val world = geometry.worldPositions[drawable.id] ?: continue
                    val image = images.getOrNull(draw.page) ?: continue
                    // Source pixels: page texel (u * W, v * H) lies at the raster pixel the tile's placement maps it to, through any turn.
                    val source = sources?.forDrawable(drawable.id.raw, draw.page)
                    val positions = FloatArray(mesh.indices.size * 2)
                    val uvs = FloatArray(positions.size)
                    // Expanded vertices avoid the unsigned-short index limit for large authored meshes.
                    mesh.indices.forEachIndexed { index, vertex ->
                        positions[index * 2] = viewport.x(world[vertex * 2]).toFloat()
                        positions[index * 2 + 1] = viewport.yFromWorld(world[vertex * 2 + 1]).toFloat()
                        val u = mesh.uvs[vertex * 2] * image.width
                        val v = mesh.uvs[vertex * 2 + 1] * image.height
                        if (source == null) {
                            uvs[index * 2] = u
                            uvs[index * 2 + 1] = v
                        } else {
                            val raster = source.placement.toRaster(u, v)
                            uvs[index * 2] = raster[0]
                            uvs[index * 2 + 1] = raster[1]
                        }
                    }
                    val masked = draw.maskIds.isNotEmpty()
                    val saved = canvas.save()
                    try {
                        if (masked) {
                            // The part draws into a layer the size of its own triangles; its masks then keep only
                            // what they cover (DST_IN over their union), as the GPU painter's stencil does.
                            canvas.saveLayer(bounds(positions), null)
                        }
                        paint.shader = source?.shader ?: shaders[draw.page]
                        paint.setAlphaf(draw.opacity)
                        canvas.drawVertices(VertexMode.TRIANGLES, positions, null, uvs, null, BlendMode.MODULATE, paint)
                        // Hover annotation: wash the very triangles just drawn with the component colour
                        // instead of boxing them. Re-drawing the mesh keeps the tint on the artwork's own
                        // silhouette — a part lights up rather than growing a rectangle — and because it
                        // is masked with the part, a masked part is tinted only where it actually shows.
                        if (draw.tintColor != 0) {
                            paint.shader = null
                            paint.color = draw.tintColor
                            paint.setAlphaf(draw.tintAlpha)
                            canvas.drawVertices(VertexMode.TRIANGLES, positions, null, null, null, BlendMode.SRC_OVER, paint)
                        }
                        if (masked) {
                            maskPaint.blendMode = BlendMode.DST_IN
                            canvas.saveLayer(null, maskPaint)
                            for (id in draw.maskIds) {
                                val maskMesh = byId[id]?.mesh ?: continue
                                val points = geometry.worldPositions[id] ?: continue
                                canvas.drawVertices(VertexMode.TRIANGLES, maskPositions(maskMesh.indices, points, viewport), null, null, null,
                                    BlendMode.SRC_OVER, solidPaint)
                            }
                            canvas.restore()
                        }
                    } finally { canvas.restoreToCount(saved) }
                }
            } finally { maskPaint.close(); solidPaint.close() }
        }
    }

    /** Mask triangles in view pixels, expanded like the part's own. */
    private fun maskPositions(indices: IntArray, points: FloatArray, viewport: CanvasViewport): FloatArray {
        val out = FloatArray(indices.size * 2)
        indices.forEachIndexed { index, vertex ->
            out[index * 2] = viewport.x(points[vertex * 2]).toFloat()
            out[index * 2 + 1] = viewport.yFromWorld(points[vertex * 2 + 1]).toFloat()
        }
        return out
    }

    private fun bounds(positions: FloatArray): Rect {
        var left = Float.POSITIVE_INFINITY; var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
        for (i in positions.indices step 2) {
            left = minOf(left, positions[i]); right = maxOf(right, positions[i])
            top = minOf(top, positions[i + 1]); bottom = maxOf(bottom, positions[i + 1])
        }
        return if (left > right) Rect.makeWH(0f, 0f) else Rect.makeLTRB(left - 1f, top - 1f, right + 1f, bottom + 1f)
    }

    override fun close() {
        val pages = pages ?: return
        this.pages = null
        pages.shaders.forEach { it.close() }
        pages.images.forEach { it.close() }
    }
}

/**
 * Each textured layer's own raster as a Skia image, for edit canvases that show source pixels instead of the
 * atlas. A mesh maps to its layer's raster through the layer's atlas placement, so the UVs need no rebuild; a
 * mesh whose layer has no placement on the page it samples keeps the atlas. Images are made on first use.
 */
internal class SourcePixelImages(private val model: RigPreviewModel) : AutoCloseable {
    class Source(val placement: io.github.psd2live.core.AtlasPlacement, val image: Image, val shader: Shader)

    private val rasters = model.analysis.layers.associate { it.source.id.raw to it.source.textureLayer.raster }
    private val byLayer = HashMap<String, Source?>()

    fun forDrawable(drawableId: String, page: Int): Source? {
        val layerId = model.rig.layerIdByDrawableId[drawableId] ?: return null
        val placement = model.atlas.placementByLayerId[layerId]?.takeIf { it.page == page && it.scaleX > 0f && it.scaleY > 0f } ?: return null
        return byLayer.getOrPut(layerId) {
            val raster = rasters[layerId]?.takeIf { it.width > 0 && it.height > 0 } ?: return@getOrPut null
            val image = Image.makeRaster(ImageInfo(raster.width, raster.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
                raster.rgba, raster.width * 4)
            Source(placement, image, image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null))
        }
    }

    override fun close() {
        byLayer.values.forEach { source -> source?.shader?.close(); source?.image?.close() }
        byLayer.clear()
    }
}

/**
 * The camera translation a cached pass was drawn at, and everything else it depends on. While the user pans,
 * a pass whose [stableKey] still matches is drawn shifted instead of being drawn again.
 */
internal class PanShift(val stableKey: List<Any?>, val offsetX: Double, val offsetY: Double, val panning: Boolean)

/**
 * Keeps the unchanged texture pass for this one viewport.
 *
 * A new key is recorded as Skia draw commands and replayed while it keeps changing (a drag, a scrub).
 * Replaying is not free: every textured mesh is sampled from the atlas again and every masked part composites
 * a layer trimmed by its masks, on each frame the canvas draws, which includes every hover
 * and overlay change. So once the same key is drawn twice it is rasterized, and later frames draw one image.
 */
internal class CachedSkiaPicture : AutoCloseable {
    private var key: List<Any?>? = null
    private var picture: Picture? = null
    private var raster: Image? = null
    private var shift: PanShift? = null

    fun draw(
        canvas: Canvas, key: List<Any?>, width: Int, height: Int, pan: PanShift? = null, record: (Canvas) -> Unit,
    ) {
        val cached = shift
        if (pan != null && pan.panning && cached != null && (picture != null || raster != null) && cached.stableKey == pan.stableKey &&
            (cached.offsetX != pan.offsetX || cached.offsetY != pan.offsetY)) {
            val saved = canvas.save()
            try {
                canvas.translate((pan.offsetX - cached.offsetX).toFloat(), (pan.offsetY - cached.offsetY).toFloat())
                drawCached(canvas)
            } finally { canvas.restoreToCount(saved) }
            return
        }
        if (picture == null || this.key != key) {
            val next = PictureRecorder().use { recorder ->
                record(recorder.beginRecording(Rect.makeWH(width.toFloat(), height.toFloat())))
                recorder.finishRecordingAsPicture()
            }
            picture?.close()
            raster?.close()
            raster = null
            picture = next
            this.key = key
            shift = pan
        } else if (raster == null && width > 0 && height > 0) {
            raster = Surface.makeRasterN32Premul(width, height).use { surface ->
                picture?.let { surface.canvas.drawPicture(it) }
                surface.makeImageSnapshot()
            }
        }
        drawCached(canvas)
    }

    private fun drawCached(canvas: Canvas) {
        val image = raster
        if (image != null) canvas.drawImage(image, 0f, 0f) else picture?.let { canvas.drawPicture(it) }
    }

    override fun close() {
        picture?.close()
        picture = null
        raster?.close()
        raster = null
        key = null
        shift = null
    }
}
