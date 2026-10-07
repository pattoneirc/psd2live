package io.github.psd2live.ui.state

import androidx.compose.runtime.Immutable
import io.github.psd2live.application.WorkspaceAtlasSnapshot
import io.github.psd2live.application.WorkspaceAtlasTile
import io.github.psd2live.application.WorkspaceImageFit
import io.github.psd2live.application.WorkspaceLayerTexture
import io.github.psd2live.application.WorkspaceTextureView
import io.github.psd2live.core.RigPreviewModel
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.pow

/**
 * UI state of the texture workspace: which atlas page is shown, its overlays, the tile being dragged and the
 * outcome of the last texture command. Nothing here is project data; every change to textures goes through
 * `WorkspaceTexturePort.editTexture` and comes back as a new captured [TextureSnapshot].
 */
@Immutable
data class TextureWorkspaceState(
	val selectedPage: Int = 0,
	/** Tiles are tinted by how many atlas pixels they spend per canvas unit. */
	val heatmap: Boolean = false,
	val showOutlines: Boolean = true,
	/** The page's pixels are drawn; off, only the overlays show. */
	val showPage: Boolean = true,
	/** Each tile's meshes are drawn as a wireframe over their texels. */
	val showMeshes: Boolean = true,
	/** How "Arrange" lays tiles out: by the meshes' footprints (nesting) or by rectangles, all tiles or the selection. */
	val arrangeByMesh: Boolean = true,
	val arrangeSelectionOnly: Boolean = false,
	/** How "Replace image" lays an image of another aspect ratio on the layer, and whether it rebuilds the mesh. */
	val replaceFit: WorkspaceImageFit = WorkspaceImageFit.STRETCH,
	val replaceRebuildMesh: Boolean = false,
	/**
	 * Density ratios (layer id to factor) the atlas shows while the inspector's slider is dragged, before they are
	 * committed. A dragged tile or corner is the atlas view's own state and never comes here.
	 */
	val densityPreview: Map<String, Float> = emptyMap(),
	/**
	 * Tiles as queued texture edits leave them, by layer: the atlas shows each where its edit puts it from the
	 * moment it is made until that edit's version arrives, so no gesture waits for a rebuild.
	 */
	val pending: Map<String, PendingTile> = emptyMap(),
	/** Texture commands are queued or running; gestures go on and queue theirs behind them. */
	val busy: Boolean = false,
	/** Why the last texture command was refused, shown in the panel until the next command. */
	val error: String? = null,
	/** Notices the last committed layout reported (fit below 1, locks or pins that did not fit). */
	val notices: List<String> = emptyList(),
	/** Bumped after each texture commit so views capture again even when nothing else they read changed. */
	val revision: Int = 0,
)

/** A tile dragged to ([x], [y]) on [page], in texture pixels. [collides] when it overlaps another tile. */
@Immutable
data class TileDragDraft(
	val layerId: String,
	val page: Int,
	val x: Int,
	val y: Int,
	val collides: Boolean,
)

/**
 * A tile as a queued texture edit leaves it: on [page] at ([x], [y]), [width] x [height] texture pixels, at
 * [density] when the edit sets one. [token] names the queued edit; a later edit of the same layer replaces it.
 */
@Immutable
data class PendingTile(val page: Int, val x: Int, val y: Int, val width: Int, val height: Int, val density: Float?, val token: Long)

/** [tile] where [pending] puts it, or as it is. */
internal fun WorkspaceAtlasTile.shownAs(pending: Map<String, PendingTile>): WorkspaceAtlasTile {
	val p = pending[layerId] ?: return this
	if (p.page == page && p.x == x && p.y == y && p.width == width && p.height == height) return this
	val sx = p.width.toFloat() / width; val sy = p.height.toFloat() / height
	return copy(page = p.page, x = p.x, y = p.y, width = p.width, height = p.height, scaleX = scaleX * sx, scaleY = scaleY * sy,
		density = p.density ?: density)
}

/** The tiles on [page] as the atlas shows them: committed, with queued edits applied. */
internal fun TextureSnapshot.shownTiles(page: Int, pending: Map<String, PendingTile>): List<WorkspaceAtlasTile> =
	if (pending.isEmpty()) tiles(page)
	else atlas.tiles.filter { layer(it.layerId)?.deleted == false }.map { it.shownAs(pending) }.filter { it.page == page }

/** One captured version of the textures and atlas, as the texture views read it. */
class TextureSnapshot(private val view: WorkspaceTextureView) {
	val projectId: String get() = view.projectId
	val state: String get() = view.state
	val revision: String get() = view.revision
	val atlas: WorkspaceAtlasSnapshot = view.atlas()
	val tilesByLayer: Map<String, WorkspaceAtlasTile> = atlas.tiles.associateBy { it.layerId }
	private val layers = ConcurrentHashMap<String, Result<WorkspaceLayerTexture>>()

	/** The layer's committed texture, or null when the id names no texture layer of this version. */
	fun layer(layerId: String): WorkspaceLayerTexture? =
		layers.getOrPut(layerId) { runCatching { view.layer(layerId) } }.getOrNull()

	/**
	 * The tiles on [page] the views show: those of current texture layers. A soft-deleted layer, or the placeholder a
	 * split part supersedes, is no art of the model even while a tile is packed for it.
	 */
	fun tiles(page: Int): List<WorkspaceAtlasTile> = atlas.tiles.filter { it.page == page && layer(it.layerId)?.deleted == false }

	/** The cells [tile] occupies if it stood at ([x], [y]): its meshes' footprint when it was arranged by one, else its rectangle. */
	internal fun shape(tile: WorkspaceAtlasTile, x: Int, y: Int): io.github.psd2live.core.AtlasArrange.Shape {
		val layer = layer(tile.layerId)
		val rasterWidth = layer?.rasterWidth ?: tile.width; val rasterHeight = layer?.rasterHeight ?: tile.height
		return io.github.psd2live.core.AtlasArrange.shape(x, y, tile.width, tile.height, rasterWidth, rasterHeight, view.footprint(tile.layerId))
	}

	/** The raster [layerId]'s tile shows (at its tile size); the views draw tiles from it while the pages are not [upscaled]. */
	fun tileRaster(layerId: String): org.umamo.format.art.LayerRaster? = view.tileRaster(layerId)

	/** Whether the pages hold upscaled textures, which only the page images show. */
	val upscaled: Boolean get() = view.upscaled

	/** The page's canonical PNG; encoding a large page takes a while, so read it off the UI thread. */
	fun pagePng(page: Int): ByteArray = view.pagePng(page)

	/** Whether [atlas] is the atlas this version packs, so its page images can be shown as they are. */
	fun matches(atlas: io.github.psd2live.core.PackedAtlas?): Boolean = atlas != null && atlas.pages.size == this.atlas.pages.size &&
		atlas.placementByLayerId.size == tilesByLayer.size && this.atlas.tiles.all { tile ->
			atlas.placementByLayerId[tile.layerId]?.let { it.page == tile.page && it.x == tile.x && it.y == tile.y &&
				it.width == tile.width && it.height == tile.height } == true
		}

	/**
	 * The texture layer a selection id stands for: selections hold layer ids, but some views select a mesh by its
	 * drawable id, and derived meshes share their source layer's tile.
	 */
	fun textureLayerId(selectionId: String, preview: RigPreviewModel?): String? {
		if (selectionId in tilesByLayer) return selectionId
		preview?.rig?.layerIdByDrawableId?.get(selectionId)?.let { if (it in tilesByLayer || layer(it) != null) return it }
		return selectionId.takeIf { layer(it) != null }
	}

	/** Atlas texture pixels per canvas unit of [tile]: its scale times the layer's raster pixels per canvas unit. */
	fun texelsPerCanvasUnit(tile: WorkspaceAtlasTile): Float {
		val layer = layer(tile.layerId) ?: return tile.scaleX
		return ((tile.scaleX * layer.nativeDensityX + tile.scaleY * layer.nativeDensityY) / 2f)
	}
}

/** Density helpers shared by the atlas heatmap, its legend and the inspector slider. */
object TextureDensity {
	const val MIN = 1f / 64f
	const val MAX = 16f
	/** The heatmap's range in powers of two around one texel per canvas unit. */
	const val HEAT_STOPS = 2f

	fun log2(value: Float): Float = (ln(value.toDouble()) / ln(2.0)).toFloat()
	fun pow2(exponent: Float): Float = 2.0.pow(exponent.toDouble()).toFloat()

	/** -1..1: -1 at a quarter texel per canvas unit or less, 0 at one, 1 at four or more. */
	fun heat(texelsPerUnit: Float): Float =
		if (!(texelsPerUnit > 0f)) -1f else (log2(texelsPerUnit) / HEAT_STOPS).coerceIn(-1f, 1f)

	/** A density rounded for display: two decimals below 10, one above. */
	fun format(value: Float): String = if (value >= 10f) "%.1f".format(value) else "%.2f".format(value)

	/** [value] on the quarter-power-of-two grid the slider and the corner handles step along, within MIN..MAX. */
	fun snap(value: Float): Float {
		if (!(value > 0f)) return MIN
		return pow2(Math.round(log2(value) * 4f) / 4f).coerceIn(MIN, MAX)
	}

	/**
	 * The density a corner drag asks for: [current] scaled by how far the pointer is from the tile's opposite
	 * corner, [distance], against that corner's own distance, [startDistance], snapped to the slider's grid.
	 */
	fun dragged(current: Float, startDistance: Float, distance: Float): Float =
		if (!(startDistance > 0f)) current else snap(current * (distance / startDistance).coerceAtLeast(1e-3f))
}

/** Tiles placed on a page collide when their rectangles, grown by the padding, overlap. */
internal fun tilesOverlap(ax: Int, ay: Int, aw: Int, ah: Int, b: WorkspaceAtlasTile, padding: Int): Boolean =
	ax < b.x + b.width + padding && b.x < ax + aw + padding && ay < b.y + b.height + padding && b.y < ay + ah + padding

/**
 * Where a tile dragged to ([x], [y]) lands: inside the page, snapped to a neighbour's edge (plus padding) within
 * [snap] texture pixels, and whether it then overlaps another tile. Every other tile keeps its place once a tile
 * is moved, so any overlap collides: of rectangles, or of [shape]s for tiles arranged by their meshes.
 */
internal fun placeDraggedTile(
	tile: WorkspaceAtlasTile, x: Float, y: Float, pageWidth: Int, pageHeight: Int,
	others: List<WorkspaceAtlasTile>, padding: Int, snap: Float,
	shape: ((WorkspaceAtlasTile, Int, Int) -> io.github.psd2live.core.AtlasArrange.Shape)? = null,
): TileDragDraft {
	var px = x.coerceIn(0f, (pageWidth - tile.width).coerceAtLeast(0).toFloat())
	var py = y.coerceIn(0f, (pageHeight - tile.height).coerceAtLeast(0).toFloat())
	fun nearest(value: Float, size: Int, edges: List<Float>): Float {
		var best = value; var distance = snap
		for (edge in edges) for (candidate in listOf(edge, edge - size)) {
			val d = kotlin.math.abs(candidate - value)
			if (d < distance) { distance = d; best = candidate }
		}
		return best
	}
	val neighbours = others.filter { it.layerId != tile.layerId && it.page == tile.page }
	px = nearest(px, tile.width, listOf(0f, pageWidth.toFloat()) + neighbours.flatMap {
		listOf((it.x + it.width + padding).toFloat(), (it.x - padding).toFloat(), it.x.toFloat())
	}).coerceIn(0f, (pageWidth - tile.width).coerceAtLeast(0).toFloat())
	py = nearest(py, tile.height, listOf(0f, pageHeight.toFloat()) + neighbours.flatMap {
		listOf((it.y + it.height + padding).toFloat(), (it.y - padding).toFloat(), it.y.toFloat())
	}).coerceIn(0f, (pageHeight - tile.height).coerceAtLeast(0).toFloat())
	val ix = Math.round(px); val iy = Math.round(py)
	val collides = neighbours.any { other ->
		if ((!tile.shaped && !other.shaped) || shape == null) tilesOverlap(ix, iy, tile.width, tile.height, other, padding)
		else io.github.psd2live.core.AtlasArrange.overlaps(io.github.psd2live.core.AtlasArrange.dilate(shape(tile, ix, iy),
			io.github.psd2live.core.AtlasArrange.paddingCells(padding)), shape(other, other.x, other.y))
	}
	return TileDragDraft(tile.layerId, tile.page, ix, iy, collides)
}
