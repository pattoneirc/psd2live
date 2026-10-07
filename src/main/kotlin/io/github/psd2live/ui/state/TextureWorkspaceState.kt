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

	/** The cells [tile] writes on the page if it stood at ([x], [y]): its arranged footprint's, else its whole rectangle. */
	internal fun shape(tile: WorkspaceAtlasTile, x: Int, y: Int): io.github.psd2live.core.AtlasArrange.Shape =
		shapeOf(tile, x, y, view.footprint(tile.layerId))

	/**
	 * The cells [tile]'s meshes use if it stood at ([x], [y]) at its size: what a drag or a resize collides by. These
	 * are the very cells the commit keeps tiles apart by, so a spot the view allows is the spot that lands.
	 */
	internal fun collisionShape(tile: WorkspaceAtlasTile, x: Int, y: Int): io.github.psd2live.core.AtlasArrange.Shape {
		// A drag asks for the standing tiles' shapes on every pointer move; they are the same each time.
		val key = CollisionKey(tile.layerId, x, y, tile.width, tile.height)
		collisionShapes[key]?.let { return it }
		if (collisionShapes.size > 4096) collisionShapes.clear()
		return shapeOf(tile, x, y, view.meshFootprint(tile.layerId)).also { collisionShapes[key] = it }
	}

	private data class CollisionKey(val layerId: String, val x: Int, val y: Int, val width: Int, val height: Int)
	private val collisionShapes = ConcurrentHashMap<CollisionKey, io.github.psd2live.core.AtlasArrange.Shape>()

	private fun shapeOf(tile: WorkspaceAtlasTile, x: Int, y: Int, footprint: io.github.psd2live.project.TextureFootprint?): io.github.psd2live.core.AtlasArrange.Shape {
		val layer = layer(tile.layerId)
		val rasterWidth = layer?.rasterWidth ?: tile.width; val rasterHeight = layer?.rasterHeight ?: tile.height
		return io.github.psd2live.core.AtlasArrange.shape(x, y, tile.width, tile.height, rasterWidth, rasterHeight, footprint)
	}

	/** Whether [tile], standing at ([x], [y]) at its size, would share a cell (with the padding) with any of [others]. */
	internal fun collides(tile: WorkspaceAtlasTile, x: Int, y: Int, others: List<WorkspaceAtlasTile>): Boolean {
		val grown = io.github.psd2live.core.AtlasArrange.dilate(collisionShape(tile, x, y),
			io.github.psd2live.core.AtlasArrange.paddingCells(atlas.budget.padding))
		return others.any { other -> other.layerId != tile.layerId && other.page == tile.page &&
			io.github.psd2live.core.AtlasArrange.overlaps(grown, collisionShape(other, other.x, other.y)) }
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

	/** [value] within MIN..MAX; densities are continuous, so a drag or the slider follows the pointer without steps. */
	fun clamp(value: Float): Float = if (!(value > 0f)) MIN else value.coerceIn(MIN, MAX)

	/**
	 * The density a corner drag asks for: [current] scaled by how far the pointer is from the tile's opposite
	 * corner, [distance], against that corner's own distance, [startDistance].
	 */
	fun dragged(current: Float, startDistance: Float, distance: Float): Float =
		if (!(startDistance > 0f)) current else clamp(current * (distance / startDistance).coerceAtLeast(1e-3f))
}

/**
 * Where a tile dragged to ([x], [y]) lands: exactly there, kept inside the page - no snapping, so it follows the
 * pointer smoothly - and whether its meshes' cells then meet another tile's ([collides]).
 */
internal fun placeDraggedTile(tile: WorkspaceAtlasTile, x: Float, y: Float, pageWidth: Int, pageHeight: Int,
                              collides: (Int, Int) -> Boolean): TileDragDraft {
	val ix = Math.round(x.coerceIn(0f, (pageWidth - tile.width).coerceAtLeast(0).toFloat()))
	val iy = Math.round(y.coerceIn(0f, (pageHeight - tile.height).coerceAtLeast(0).toFloat()))
	return TileDragDraft(tile.layerId, tile.page, ix, iy, collides(ix, iy))
}
