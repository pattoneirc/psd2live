package io.github.psd2live.ui.views.texture

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import io.github.psd2live.application.WorkspaceAtlasTile
import io.github.psd2live.core.AtlasArrange
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.render.AtlasScene
import io.github.psd2live.render.CanvasRenderService
import io.github.psd2live.render.FillBatch
import io.github.psd2live.render.ImageTexture
import io.github.psd2live.render.LineBatch
import io.github.psd2live.render.OverlayItem
import io.github.psd2live.render.OverlayScene
import io.github.psd2live.render.RasterTexture
import io.github.psd2live.render.TextureQuad
import io.github.psd2live.ui.state.TextureDensity
import io.github.psd2live.ui.state.TextureSnapshot
import java.awt.image.BufferedImage

/*
 * The atlas page on the GPU renderer: what the page shows of its tiles - their pixels, the density heatmap and the
 * mesh wireframes - as one [AtlasScene], drawn off the UI thread like the edit canvas's artwork. The light marks
 * over it (outlines, selection, grips, the selection box) stay Compose drawing, so a hover redraws no texel.
 *
 * World units are page pixels with y up: page pixel (x, y) is world (x, -y), as the renderer's paint document is.
 */

/**
 * Each tile's mesh wireframe on a page: [paths] in page pixels for the software painter, [segments] (x0, y0, x1, y1
 * per edge, world units) for the GPU, both with every edge once.
 */
internal class AtlasMeshes(val paths: Map<String, Path>, val segments: Map<String, FloatArray>)

/** The cells [shape] covers, in page pixels (left, top, right, bottom per rectangle), inside [tile]'s rectangle. */
internal fun maskRects(shape: AtlasArrange.Shape, tile: WorkspaceAtlasTile): FloatArray {
	val cell = AtlasArrange.CELL.toFloat()
	val out = ArrayList<Float>()
	val right = (tile.x + tile.width).toFloat(); val bottom = (tile.y + tile.height).toFloat()
	for (r in 0 until shape.height) {
		val run = shape.runs[r]
		val top = maxOf((shape.row + r) * cell, tile.y.toFloat()); val end = minOf((shape.row + r + 1) * cell, bottom)
		if (end <= top) continue
		for (k in run.indices step 2) {
			val left = maxOf((shape.column + run[k]) * cell, tile.x.toFloat()); val stop = minOf((shape.column + run[k + 1]) * cell, right)
			if (stop > left) { out += left; out += top; out += stop; out += end }
		}
	}
	return out.toFloatArray()
}

/** Everything of the page's look that the GPU frame holds; a frame is drawn again only when one of these changes. */
internal class AtlasSceneInput(
	val snapshot: TextureSnapshot,
	val tiles: List<WorkspaceAtlasTile>,
	val width: Int,
	val height: Int,
	val viewport: CanvasViewport,
	val showPage: Boolean,
	val heatmap: Boolean,
	val meshes: AtlasMeshes?,
	val masks: Map<String, FloatArray>,
	val selected: Set<String>,
	/** The live atlas page an upscaled page's tiles are cut from; null samples each layer's raster. */
	val pageImage: BufferedImage?,
	val wireColor: Int,
	val accentColor: Int,
)

/**
 * The scene of [input] with [moved] giving where a gesture puts a tile (page pixels; null where it stands): the
 * tiles in place, the heatmap over them, their wireframes, then the lifted tiles and theirs over everything.
 */
internal fun atlasScene(input: AtlasSceneInput, moved: (WorkspaceAtlasTile) -> Rect?): AtlasScene {
	val items = ArrayList<OverlayItem>()
	val snapshot = input.snapshot
	val nearest = input.viewport.scale >= 2.0
	val art = if (input.heatmap) 0.45f else 1f
	val page = input.pageImage
	/** [tile]'s pixels into [at] (page pixels), inside its cells when it was arranged by its meshes. */
	fun pixels(tile: WorkspaceAtlasTile, at: Rect, alpha: Float) {
		val sx = at.width / tile.width; val sy = at.height / tile.height
		val clip = input.masks[tile.layerId]?.let { cells ->
			FloatArray(cells.size) { i ->
				when (i % 4) {
					0, 2 -> at.left + (cells[i] - tile.x) * sx
					else -> -(at.top + (cells[i] - tile.y) * sy)
				}
			}
		}
		val quad = if (page != null) TextureQuad(ImageTexture(page), at.left, -at.top, at.right, -at.bottom,
			tile.x.toFloat() / page.width, tile.y.toFloat() / page.height,
			(tile.x + tile.width).toFloat() / page.width, (tile.y + tile.height).toFloat() / page.height, alpha, nearest, clip)
		else {
			val raster = snapshot.tileRaster(tile.layerId) ?: return
			TextureQuad(RasterTexture(raster.width, raster.height, raster.rgba), at.left, -at.top, at.right, -at.bottom,
				alpha = alpha, nearest = nearest, clip = clip)
		}
		items += quad
	}
	/** [tile]'s wireframe, moved with it to [at] when a gesture lifts it. */
	fun wires(tile: WorkspaceAtlasTile, at: Rect?, argb: Int) {
		val segments = input.meshes?.segments?.get(tile.layerId) ?: return
		val drawn = if (at == null) segments else {
			val sx = at.width / tile.width; val sy = at.height / tile.height
			FloatArray(segments.size) { i ->
				if (i % 2 == 0) at.left + (segments[i] - tile.x) * sx else -(at.top + (-segments[i] - tile.y) * sy)
			}
		}
		items += LineBatch(argb, 1f, drawn)
	}
	fun rect(tile: WorkspaceAtlasTile) = Rect(tile.x.toFloat(), tile.y.toFloat(), (tile.x + tile.width).toFloat(), (tile.y + tile.height).toFloat())

	val lifted = input.tiles.mapNotNull { tile -> moved(tile)?.let { tile to it } }
	val liftedIds = lifted.mapTo(HashSet()) { it.first.layerId }
	val standing = input.tiles.filter { it.layerId !in liftedIds }
	if (input.showPage) for (tile in standing) pixels(tile, rect(tile), art)
	if (input.heatmap) for (tile in standing) {
		val argb = heatColor(TextureDensity.heat(snapshot.texelsPerCanvasUnit(tile))).copy(alpha = 0.55f).toArgb()
		val r = rect(tile)
		items += FillBatch(argb, listOf(floatArrayOf(r.left, -r.top, r.right, -r.top, r.right, -r.bottom, r.left, -r.bottom)))
	}
	if (input.meshes != null) {
		for (tile in standing) if (tile.layerId !in input.selected) wires(tile, null, input.wireColor)
		for (tile in standing) if (tile.layerId in input.selected) wires(tile, null, input.accentColor)
	}
	for ((tile, at) in lifted) {
		if (input.showPage) pixels(tile, at, 1f)
		if (input.meshes != null) wires(tile, at, input.accentColor)
	}
	return AtlasScene(input.width, input.height, input.viewport, OverlayScene(items))
}

/** Hands the GPU renderer a new atlas scene only when what it shows changed; redraws in between submit nothing. */
internal class AtlasSceneSubmission(private val viewId: String) {
	private var key: List<Any?>? = null

	fun submit(key: List<Any?>, scene: () -> AtlasScene) {
		if (this.key == key) return
		this.key = key
		CanvasRenderService.submit(viewId, scene())
	}
}
