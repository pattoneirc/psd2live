package io.github.psd2live.ui.views.texture

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import io.github.psd2live.application.WorkspaceAtlasTile
import io.github.psd2live.core.AtlasArrange
import io.github.psd2live.core.TileTurn
import androidx.compose.ui.geometry.Offset
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

/**
 * The cells [shape] covers, in page pixels (left, top, right, bottom per rectangle): inside [tile]'s rectangle for an
 * upright tile, the turned shape's own cells for a turned one.
 */
internal fun maskRects(shape: AtlasArrange.Shape, tile: WorkspaceAtlasTile): FloatArray {
	val cell = AtlasArrange.CELL.toFloat()
	val out = ArrayList<Float>()
	val box = TileTurn.bounds(tile.x.toFloat(), tile.y.toFloat(), tile.width.toFloat(), tile.height.toFloat(), tile.rotation)
	val right = maxOf(box[2], (tile.x + tile.width).toFloat()); val bottom = maxOf(box[3], (tile.y + tile.height).toFloat())
	for (r in 0 until shape.height) {
		val run = shape.runs[r]
		val top = maxOf((shape.row + r) * cell, minOf(box[1], tile.y.toFloat())); val end = minOf((shape.row + r + 1) * cell, bottom)
		if (end <= top) continue
		for (k in run.indices step 2) {
			val left = maxOf((shape.column + run[k]) * cell, minOf(box[0], tile.x.toFloat())); val stop = minOf((shape.column + run[k + 1]) * cell, right)
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
 * Where a tile shows: its upright rectangle in page pixels - fractional while a gesture runs - turned [rotation]
 * degrees about its centre ([TileTurn]). Committed, queued, session and gesture tiles are all one of these.
 */
internal data class TileFrame(val x: Float, val y: Float, val width: Float, val height: Float, val rotation: Float) {
	fun toPage(tx: Float, ty: Float): FloatArray = TileTurn.toPage(x, y, width, height, rotation, tx, ty)
	fun toTile(px: Float, py: Float): FloatArray = TileTurn.toTile(x, y, width, height, rotation, px, py)
	/** Top left, top right, bottom right, bottom left, page pixels. */
	fun corners(): FloatArray = TileTurn.corners(x, y, width, height, rotation)
	fun contains(px: Float, py: Float): Boolean = toTile(px, py).let { it[0] >= 0f && it[1] >= 0f && it[0] < width && it[1] < height }
	val centre: Offset get() = Offset(x + width / 2f, y + height / 2f)

	/** The page point [from] shows at ([px], [py]), where this frame shows the same point of the tile. */
	fun retarget(from: TileFrame, px: Float, py: Float): FloatArray {
		val local = from.toTile(px, py)
		return toPage(local[0] * width / from.width, local[1] * height / from.height)
	}

	companion object {
		fun of(tile: WorkspaceAtlasTile) = TileFrame(tile.x.toFloat(), tile.y.toFloat(), tile.width.toFloat(), tile.height.toFloat(), tile.rotation)
	}
}

/**
 * The scene of [input] with [moved] giving where a gesture puts a tile (null where it stands): the tiles in place,
 * the heatmap over them, their wireframes, then the lifted tiles and theirs over everything.
 */
internal fun atlasScene(input: AtlasSceneInput, moved: (WorkspaceAtlasTile) -> TileFrame?): AtlasScene {
	val items = ArrayList<OverlayItem>()
	val snapshot = input.snapshot
	val nearest = input.viewport.scale >= 2.0
	val art = if (input.heatmap) 0.45f else 1f
	val page = input.pageImage
	/** World corners (page y flipped) of [frame], top left, top right, bottom right, bottom left. */
	fun world(frame: TileFrame) = frame.corners().also { c -> for (i in 1 until 8 step 2) c[i] = -c[i] }
	/** [tile]'s pixels into [frame]; a tile standing at its own frame keeps to its cells when it has a footprint. */
	fun pixels(tile: WorkspaceAtlasTile, frame: TileFrame, alpha: Float, own: Boolean) {
		val clip = if (!own) null else input.masks[tile.layerId]?.let { cells -> FloatArray(cells.size) { i -> if (i % 2 == 0) cells[i] else -cells[i] } }
		val corners = world(frame)
		// An upscaled page holds the tile where it was committed.
		val source = snapshot.tilesByLayer[tile.layerId] ?: tile
		val quad = if (page != null) TextureQuad(ImageTexture(page), corners[0], corners[1], corners[4], corners[5],
			source.x.toFloat() / page.width, source.y.toFloat() / page.height,
			(source.x + source.width).toFloat() / page.width, (source.y + source.height).toFloat() / page.height, alpha, nearest, clip, corners)
		else {
			val raster = snapshot.tileRaster(tile.layerId) ?: return
			TextureQuad(RasterTexture(raster.width, raster.height, raster.rgba), corners[0], corners[1], corners[4], corners[5],
				alpha = alpha, nearest = nearest, clip = clip, corners = corners)
		}
		items += quad
	}
	/** [tile]'s wireframe: it lies where the committed tile is, and goes along to [frame] when the tile shows elsewhere. */
	fun wires(tile: WorkspaceAtlasTile, frame: TileFrame, argb: Int) {
		val segments = input.meshes?.segments?.get(tile.layerId) ?: return
		val committed = TileFrame.of(snapshot.tilesByLayer[tile.layerId] ?: tile)
		val drawn = if (committed == frame) segments else FloatArray(segments.size).also { out ->
			for (i in segments.indices step 2) {
				val p = frame.retarget(committed, segments[i], -segments[i + 1])
				out[i] = p[0]; out[i + 1] = -p[1]
			}
		}
		items += LineBatch(argb, 1f, drawn)
	}
	val lifted = input.tiles.mapNotNull { tile -> moved(tile)?.let { tile to it } }
	val liftedIds = lifted.mapTo(HashSet()) { it.first.layerId }
	val standing = input.tiles.filter { it.layerId !in liftedIds }
	if (input.showPage) for (tile in standing) pixels(tile, TileFrame.of(tile), art, own = true)
	if (input.heatmap) for (tile in standing) {
		val argb = heatColor(TextureDensity.heat(snapshot.texelsPerCanvasUnit(tile))).copy(alpha = 0.55f).toArgb()
		items += FillBatch(argb, listOf(world(TileFrame.of(tile))))
	}
	if (input.meshes != null) {
		for (tile in standing) if (tile.layerId !in input.selected) wires(tile, TileFrame.of(tile), input.wireColor)
		for (tile in standing) if (tile.layerId in input.selected) wires(tile, TileFrame.of(tile), input.accentColor)
	}
	for ((tile, frame) in lifted) {
		if (input.showPage) pixels(tile, frame, 1f, own = false)
		if (input.meshes != null) wires(tile, frame, input.accentColor)
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
