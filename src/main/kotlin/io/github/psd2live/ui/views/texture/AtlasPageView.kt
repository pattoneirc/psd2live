package io.github.psd2live.ui.views.texture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.application.WorkspaceAtlasTile
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactMenuSection
import io.github.psd2live.ui.components.IconMeshWireframe
import io.github.psd2live.ui.components.IconTextureView
import io.github.psd2live.ui.components.IconLock
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.render.CanvasRenderService
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutScope
import io.github.psd2live.ui.state.TextureDensity
import io.github.psd2live.ui.state.TextureSnapshot
import io.github.psd2live.ui.state.TileDragDraft
import io.github.psd2live.ui.state.shownTiles
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.ui.utils.NativeFilePicker
import io.github.psd2live.ui.utils.rgbaImageBitmap
import io.github.psd2live.ui.utils.toImageBitmapFast
import io.github.psd2live.ui.views.CanvasNavigation
import io.github.psd2live.ui.views.CanvasOptionsRail
import io.github.psd2live.ui.views.RailDivider
import io.github.psd2live.ui.views.RailToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Page sizes the budget offers; the command accepts any power of two from 256. */
internal val ATLAS_PAGE_SIZES = listOf(1024, 2048, 4096, 8192, 16384)

/** The committed texture snapshot the texture views read, captured again after every commit, undo or rebuild. */
@Composable
internal fun rememberTextureSnapshot(state: PSD2LiveState, vm: PSD2LiveViewModel): TextureSnapshot? =
	remember(state.previewModel, state.historySnapshot?.headNodeId, state.textureWorkspace.revision, state.projectOpenGeneration) {
		vm.textureSnapshot()
	}

/** The selection as texture layer ids of [snapshot], primary first. */
internal fun textureSelection(state: PSD2LiveState, snapshot: TextureSnapshot): List<String> =
	(listOfNotNull(state.selectedLayerId) + state.selectedLayerIds)
		.mapNotNull { snapshot.textureLayerId(it, state.previewModel) }.distinct()
		.filter { snapshot.layer(it)?.deleted != true }

/**
 * The texture workspace's atlas page, edited the way the edit canvas edits the model. The page fills the view;
 * the page switcher, packing and budget menus, view toggles and a status pill float over it.
 *
 * - Click selects a tile's layer and dragging over empty page draws a selection box; Shift adds, Alt takes away.
 * - Dragging a tile pins it where it is dropped; dragging a selected tile's corner scales its texture density
 *   (and the rest of the selection's by the same ratio) along the slider's quarter-power-of-two grid.
 * - Each drag commits once, on release. Right-click opens the tile's or the page's menu.
 * - The wheel zooms around the pointer, the middle button or Space + drag pans, and the canvas camera shortcuts
 *   (frame, reset) and Ctrl+A work here too. Double-click frames a tile.
 */
@Composable
fun AtlasPageView(state: PSD2LiveState, vm: PSD2LiveViewModel, modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val snapshot = rememberTextureSnapshot(state, vm)
	Box(modifier.fillMaxSize().background(colors.windowBackground)) {
		if (snapshot == null || snapshot.atlas.pages.isEmpty()) {
			Text(tr("texture.atlas.empty"), style = typography.body.copy(fontSize = 11.5.sp), color = colors.textMuted,
				modifier = Modifier.align(Alignment.Center).padding(12.dp))
			return@Box
		}
		val page = state.textureWorkspace.selectedPage.coerceIn(0, snapshot.atlas.pages.lastIndex)
		AtlasPageCanvas(state, vm, snapshot, page)
	}
}

/**
 * Where the atlas page last showed, for the development tools that drive the real window: the view's top left in
 * window pixels and its camera (left, top, scale). Written on the UI thread, read by the tools.
 */
internal object AtlasPageProbe {
	@Volatile var origin: Offset = Offset.Zero
	@Volatile var camera: FloatArray = floatArrayOf(0f, 0f, 1f)
	/** Draws that showed a tile a gesture lifts, and of those the ones whose GPU frame already had it lifted. */
	val liftedDraws = java.util.concurrent.atomic.AtomicInteger()
	val liftedGpuFrames = java.util.concurrent.atomic.AtomicInteger()
	val liftedScenes: MutableSet<Any> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap()))
}

/** Page-to-view mapping: a texture pixel (x, y) shows at (left + x * scale, top + y * scale). */
private data class PageTransform(val left: Float, val top: Float, val scale: Float) {
	fun toView(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)
	fun toPage(point: Offset) = Offset((point.x - left) / scale, (point.y - top) / scale)
	fun rect(x: Float, y: Float, width: Float, height: Float) = Rect(toView(x, y), Size(width * scale, height * scale))
	fun rect(tile: WorkspaceAtlasTile) = rect(tile.x.toFloat(), tile.y.toFloat(), tile.width.toFloat(), tile.height.toFloat())
}

/**
 * A corner-handle drag in flight: [factor] scales every selected tile's density, about [anchor]'s tile corner;
 * [collides] when a tile would then meet another by their meshes (or leave the page), so the release changes nothing.
 */
private data class DensityDrag(val layerIds: List<String>, val primary: String, val anchor: Offset, val corner: Offset, val factor: Float,
                               val collides: Boolean = false)

/**
 * Where a corner drag puts [tile] (as the atlas shows it), in whole texture pixels: the grabbed tile keeps its opposite
 * corner, the others their top left. The preview, the overlap test and the commit all take this one placement.
 */
private fun scaledTile(tile: WorkspaceAtlasTile, drag: DensityDrag): WorkspaceAtlasTile {
	val width = Math.round(tile.width * drag.factor).coerceAtLeast(1); val height = Math.round(tile.height * drag.factor).coerceAtLeast(1)
	if (tile.layerId != drag.primary) return tile.copy(width = width, height = height)
	val x = if (drag.anchor.x > tile.x) Math.round(drag.anchor.x) - width else Math.round(drag.anchor.x)
	val y = if (drag.anchor.y > tile.y) Math.round(drag.anchor.y) - height else Math.round(drag.anchor.y)
	return tile.copy(x = x, y = y, width = width, height = height)
}

/** What a right-click opened its menu on: a tile's layer, or the page itself. */
private data class AtlasMenu(val at: Offset, val layerId: String?)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AtlasPageCanvas(state: PSD2LiveState, vm: PSD2LiveViewModel, snapshot: TextureSnapshot, page: Int) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val texture = state.textureWorkspace
	val atlas = snapshot.atlas
	val pageInfo = atlas.pages[page]
	// The tiles as the atlas shows them: committed, with the queued edits' results laid over them until they land.
	val tiles = remember(snapshot, page, texture.pending) { snapshot.shownTiles(page, texture.pending) }
	// Tiles are drawn from their layers' rasters, so a moved or rescaled tile shows at once, without waiting for
	// the page to be composed and converted; only upscaled pages, which hold pixels no raster has, are drawn whole.
	// The GPU renderer draws the page's texels and wireframes when it can, as it does the edit canvas's artwork; the
	// Compose drawing below stays the fallback. An upscaled page needs the live page image to cut its tiles from.
	val softwareCanvas by AppSettings.softwareCanvasFlow.collectAsState()
	LaunchedEffect(softwareCanvas) { if (!softwareCanvas) CanvasRenderService.ensureStarted() }
	val gpuStatus by CanvasRenderService.status.collectAsState()
	val livePage = remember(state.previewModel, snapshot, page) {
		if (!snapshot.upscaled) null else state.previewModel?.atlas?.takeIf(snapshot::matches)?.pages?.getOrNull(page)?.image
	}
	val gpuReady = !softwareCanvas && gpuStatus is CanvasRenderService.Status.Ready && (!snapshot.upscaled || livePage != null)
	val renderKey = remember { "atlas-page-" + java.util.UUID.randomUUID() }
	DisposableEffect(renderKey) { onDispose { CanvasRenderService.release(renderKey) } }
	val gpuFrame by remember(renderKey) { CanvasRenderService.frames(renderKey) }.collectAsState()
	val gpuImage = remember(gpuFrame) { gpuFrame?.bitmap?.takeIf { !it.isClosed }?.asComposeImageBitmap() }
	val gpuSubmission = remember(renderKey) { AtlasSceneSubmission(renderKey) }
	val tileImages = rememberTileImages(snapshot, enabled = !gpuReady)
	val pageImage = rememberPageImage(state, snapshot, page, enabled = snapshot.upscaled && !gpuReady)
	val masks = remember(snapshot, tiles) { tiles.filter { it.shaped }.associate { it.layerId to maskPath(snapshot.shape(it, it.x, it.y)) } }
	val maskCells = remember(snapshot, tiles) { tiles.filter { it.shaped }.associate { it.layerId to maskRects(snapshot.shape(it, it.x, it.y), it) } }
	val selection = remember(state.selectedLayerIds, state.selectedLayerId, snapshot) { textureSelection(state, snapshot) }
	val selected = remember(selection) { selection.toSet() }
	val shownIds = remember(tiles) { tiles.mapTo(HashSet()) { it.layerId } }
	val meshes = rememberMeshes(state, snapshot, page)
	// Queued texture edits never hold a gesture back: it queues behind them. Only a whole rebuild does.
	val busy = state.isAnalyzing || state.isGenerating

	var zoom by remember(page) { mutableStateOf(1f) }
	var pan by remember(page) { mutableStateOf(Offset.Zero) }
	var hovered by remember(snapshot, page) { mutableStateOf<String?>(null) }
	var viewSize by remember { mutableStateOf(IntSize.Zero) }
	var marquee by remember { mutableStateOf<Rect?>(null) }
	// A gesture's state lives here, not in the application state, so moving the pointer recomposes only this view. It
	// must outlive every version: the gesture handler below keeps the state objects it first saw, so state remembered
	// per snapshot would leave every gesture after the first commit drawing nothing. Once released, the result shows
	// as a queued edit (TextureWorkspaceState.pending) until its version lands.
	var densityDrag by remember { mutableStateOf<DensityDrag?>(null) }
	var tileDrag by remember { mutableStateOf<TileDragDraft?>(null) }
	var space by remember { mutableStateOf(false) }
	var menu by remember { mutableStateOf<AtlasMenu?>(null) }
	/** Where the pointer was at the last step of a pan, while one is in progress. */
	var panFrom by remember { mutableStateOf<Offset?>(null) }
	/** The pointer over the view, for the cursor; null once it leaves. */
	var pointer by remember { mutableStateOf<Offset?>(null) }
	val focusRequester = remember { FocusRequester() }

	val margin = 24f
	val fitScale = if (viewSize.width <= 0) 1f else
		min((viewSize.width - margin * 2) / pageInfo.width, (viewSize.height - margin * 2) / pageInfo.height).coerceAtLeast(1e-4f)
	val scale = fitScale * zoom
	val transform = PageTransform((viewSize.width - pageInfo.width * scale) / 2f + pan.x, (viewSize.height - pageInfo.height * scale) / 2f + pan.y, scale)
	val currentTransform by rememberUpdatedState(transform)
	val currentSnapshot by rememberUpdatedState(snapshot)
	val currentTiles by rememberUpdatedState(tiles)
	val currentSelected by rememberUpdatedState(selected)
	val currentSelection by rememberUpdatedState(selection)
	val currentBusy by rememberUpdatedState(busy)
	val currentSpace by rememberUpdatedState(space)
	val handleRadius = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }

	fun hit(point: Offset): WorkspaceAtlasTile? {
		val at = currentTransform.toPage(point)
		// Smaller tiles first: a tile nested in a larger one's padding stays reachable.
		return currentTiles.filter { at.x >= it.x && at.y >= it.y && at.x < it.x + it.width && at.y < it.y + it.height }
			.minByOrNull { it.width.toLong() * it.height }
	}

	/** The selected tile whose grip is under [point], with the grip's corner and the one opposite it, in page pixels. */
	fun hitCorner(point: Offset): Triple<WorkspaceAtlasTile, Offset, Offset>? {
		for (tile in currentTiles.filter { it.layerId in currentSelected }) {
			for (grip in grips(tile, currentTransform, handleRadius)) {
				if ((grip.at - point).getDistance() <= handleRadius * 1.6f) return Triple(tile, grip.corner, grip.opposite)
			}
		}
		return null
	}

	/** Zooms so [rect] (page pixels) fills the view, centred. Reads the view and page as they are now. */
	fun frame(rect: Rect) {
		if (viewSize.width <= 0 || rect.width <= 0f || rect.height <= 0f) return
		val fit = min((viewSize.width - margin * 2) / pageInfo.width, (viewSize.height - margin * 2) / pageInfo.height).coerceAtLeast(1e-4f)
		val target = min((viewSize.width - margin * 4) / rect.width, (viewSize.height - margin * 4) / rect.height)
		val nextZoom = (target / fit).coerceIn(CanvasNavigation.MIN_ZOOM.toFloat(), CanvasNavigation.MAX_ZOOM.toFloat())
		val nextScale = fit * nextZoom
		zoom = nextZoom
		pan = Offset(
			viewSize.width / 2f - rect.center.x * nextScale - (viewSize.width - pageInfo.width * nextScale) / 2f,
			viewSize.height / 2f - rect.center.y * nextScale - (viewSize.height - pageInfo.height * nextScale) / 2f,
		)
	}
	fun resetView() { zoom = 1f; pan = Offset.Zero }
	fun frameSelection() {
		val chosen = currentTiles.filter { it.layerId in currentSelected }
		if (chosen.isEmpty()) { resetView(); return }
		frame(Rect(chosen.minOf { it.x }.toFloat(), chosen.minOf { it.y }.toFloat(),
			chosen.maxOf { it.x + it.width }.toFloat(), chosen.maxOf { it.y + it.height }.toFloat()))
	}

	var lastClick by remember { mutableStateOf<Pair<String, Long>?>(null) }

	Box(
		Modifier
			.fillMaxSize()
			.onGloballyPositioned { AtlasPageProbe.origin = it.positionInWindow() }
			.focusRequester(focusRequester)
			.onKeyEvent { event ->
				if (state.keyCapture != null) return@onKeyEvent false
				if (event.key == Key.Spacebar) { space = event.type == KeyEventType.KeyDown; return@onKeyEvent true }
				if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
				if (event.key == Key.Escape) {
					when {
						tileDrag != null || densityDrag != null || marquee != null -> {
							tileDrag = null; densityDrag = null; marquee = null
						}
						else -> vm.selectLayer(null)
					}
					return@onKeyEvent true
				}
				when (state.keymap.match(event, ShortcutScope.CANVAS)) {
					ShortcutAction.FRAME_VIEW -> { frameSelection(); true }
					ShortcutAction.RESET_CAMERA -> { resetView(); true }
					ShortcutAction.SELECT_ALL -> { vm.selectLayers(tiles.map { it.layerId }); true }
					else -> false
				}
			}
			.focusable()
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				val delta = change.scrollDelta.y
				if (delta == 0f) return@onPointerEvent
				val before = currentTransform.toPage(change.position)
				val next = CanvasNavigation.wheelZoom(zoom.toDouble(), delta).toFloat()
				zoom = next
				// Keep the texel under the pointer where it was.
				val nextScale = fitScale * next
				val left = (viewSize.width - pageInfo.width * nextScale) / 2f
				val top = (viewSize.height - pageInfo.height * nextScale) / 2f
				pan = Offset(change.position.x - left - before.x * nextScale, change.position.y - top - before.y * nextScale)
				change.consume()
			}
			// The middle button (or Space with the primary one) pans and the right one opens the menu. Like the
			// edit canvas, these are read from the raw press, move and release events: a gesture's awaitFirstDown
			// does not see a press of another button than the primary one.
			.onPointerEvent(PointerEventType.Press) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				focusRequester.requestFocus()
				if (CanvasNavigation.pans(event.button, currentSpace)) {
					panFrom = change.position
					change.consume()
				} else if (event.button == PointerButton.Secondary) {
					val tile = hit(change.position)
					if (tile != null && tile.layerId !in currentSelected) vm.selectLayer(tile.layerId)
					menu = AtlasMenu(change.position, tile?.layerId)
					change.consume()
				}
			}
			.onPointerEvent(PointerEventType.Move) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				pointer = change.position
				val from = panFrom
				if (from != null) {
					pan += change.position - from
					panFrom = change.position
					change.consume()
					return@onPointerEvent
				}
				hovered = hit(change.position)?.layerId
			}
			.onPointerEvent(PointerEventType.Release) { event ->
				if (panFrom != null && (event.button == PointerButton.Tertiary || event.button == PointerButton.Primary || !event.buttons.areAnyPressed)) panFrom = null
			}
			.onPointerEvent(PointerEventType.Exit) { hovered = null; pointer = null }
			.pointerHoverIcon(PointerIcon(atlasCursor(
				panning = panFrom != null, space = space, moving = tileDrag != null, boxing = marquee != null,
				scaling = densityDrag?.let { it.corner to it.anchor },
				corner = pointer?.takeIf { !busy }?.let { at -> hitCorner(at)?.let { it.second to it.third } },
				overTile = pointer?.let { hit(it) } != null,
			)))
			.pointerInput(page, pageInfo.width, pageInfo.height) {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					val event = currentEvent
					focusRequester.requestFocus()
					// Panning and the menu belong to the press handler above.
					if (panFrom != null || CanvasNavigation.pans(event.button, currentSpace) || event.button == PointerButton.Secondary) return@awaitEachGesture
					if (event.button != null && event.button != PointerButton.Primary) return@awaitEachGesture
					val select = CanvasNavigation.selectMode(event.keyboardModifiers)
					val snapshotAtStart = currentSnapshot

					// A selected tile's corner: scale the selection's density.
					val corner = if (select == CanvasNavigation.SelectMode.REPLACE && !currentBusy) hitCorner(down.position) else null
					if (corner != null) {
						val (tile, at, anchor) = corner
						val start = (at - anchor).getDistance()
						val primaryDensity = vm.shownTextureDensity(snapshotAtStart, tile.layerId)
						var drag = DensityDrag(currentSelection, tile.layerId, anchor, at, 1f)
						densityDrag = drag
						while (true) {
							val move = awaitPointerEvent()
							val change = move.changes.firstOrNull { it.id == down.id } ?: break
							if (!change.pressed || !move.buttons.isPrimaryPressed) break
							val distance = (currentTransform.toPage(change.position) - anchor).getDistance()
							val density = TextureDensity.dragged(primaryDensity, start, distance)
							drag = drag.copy(factor = density / primaryDensity)
							val placed = currentTiles.filter { it.layerId in drag.layerIds }.associate { it.layerId to scaledTile(it, drag) }
							drag = drag.copy(collides = vm.texturePlacementCollides(snapshotAtStart, placed))
							densityDrag = drag
							change.consume()
						}
						if (drag.factor != 1f) {
							// The grabbed tile moves to keep its opposite corner; the commit takes the spot the preview showed.
							val primary = currentTiles.firstOrNull { it.layerId == drag.primary }?.let { scaledTile(it, drag) }
							val origins = primary?.takeIf { it.x != tile.x || it.y != tile.y }?.let { mapOf(it.layerId to (it.x to it.y)) }.orEmpty()
							vm.scaleTextureDensity(snapshotAtStart, drag.layerIds, drag.factor, origins)
						}
						densityDrag = null
						return@awaitEachGesture
					}

					val tile = hit(down.position)
					if (tile == null) {
						// Empty page: a selection box, or a click that clears the selection.
						var box: Rect? = null
						while (true) {
							val move = awaitPointerEvent()
							val change = move.changes.firstOrNull { it.id == down.id } ?: break
							if (!change.pressed || !move.buttons.isPrimaryPressed) break
							if (box == null && (change.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
							box = Rect(Offset(min(down.position.x, change.position.x), min(down.position.y, change.position.y)),
								Offset(max(down.position.x, change.position.x), max(down.position.y, change.position.y)))
							marquee = box
							change.consume()
						}
						marquee = null
						if (box == null) {
							if (select == CanvasNavigation.SelectMode.REPLACE) vm.selectLayer(null)
						} else {
							val from = currentTransform.toPage(box.topLeft); val to = currentTransform.toPage(box.bottomRight)
							val inside = currentTiles.filter { it.x < to.x && it.x + it.width > from.x && it.y < to.y && it.y + it.height > from.y }
							val ids = inside.map { it.layerId }
							when (select) {
								CanvasNavigation.SelectMode.REPLACE -> vm.selectLayers(ids)
								CanvasNavigation.SelectMode.ADD -> vm.selectLayers(ids, additive = true)
								CanvasNavigation.SelectMode.SUBTRACT -> vm.deselectLayers(ids)
							}
						}
						return@awaitEachGesture
					}

					val wasSelected = tile.layerId in currentSelected
					when {
						select == CanvasNavigation.SelectMode.SUBTRACT -> vm.selectLayer(tile.layerId, subtractive = true)
						select == CanvasNavigation.SelectMode.ADD -> vm.selectLayer(tile.layerId, additive = true)
						!wasSelected -> vm.selectLayer(tile.layerId)
					}
					val start = currentTransform.toPage(down.position)
					var dragging = false
					while (true) {
						val move = awaitPointerEvent()
						val change = move.changes.firstOrNull { it.id == down.id } ?: break
						if (!change.pressed || !move.buttons.isPrimaryPressed) break
						val distance = (change.position - down.position).getDistance()
						if (!dragging && (distance < viewConfiguration.touchSlop || currentBusy || select == CanvasNavigation.SelectMode.SUBTRACT)) continue
						dragging = true
						val at = currentTransform.toPage(change.position)
						tileDrag = vm.draggedTextureTile(snapshotAtStart, tile.layerId, tile.x + at.x - start.x, tile.y + at.y - start.y)
						change.consume()
					}
					val dropped = tileDrag
					tileDrag = null
					if (dragging && dropped != null) {
						vm.moveTextureTile(snapshotAtStart, dropped)
						lastClick = null
					} else {
						// A plain click on a tile of a larger selection keeps only that tile, once the press is a click.
						if (wasSelected && select == CanvasNavigation.SelectMode.REPLACE && currentSelected.size > 1) vm.selectLayer(tile.layerId)
						val now = System.currentTimeMillis()
						val previous = lastClick
						if (previous != null && previous.first == tile.layerId && now - previous.second < 350L) {
							frame(Rect(tile.x.toFloat(), tile.y.toFloat(), (tile.x + tile.width).toFloat(), (tile.y + tile.height).toFloat()))
							lastClick = null
						} else lastClick = tile.layerId to now
					}
				}
			},
	) {
		Canvas(Modifier.fillMaxSize()) {
			if (viewSize != IntSize(size.width.toInt(), size.height.toInt())) viewSize = IntSize(size.width.toInt(), size.height.toInt())
			AtlasPageProbe.camera = floatArrayOf(transform.left, transform.top, transform.scale)
			val pageRect = transform.rect(0f, 0f, pageInfo.width.toFloat(), pageInfo.height.toFloat())
			val draft = tileDrag?.takeIf { it.page == page }
			val scaling = densityDrag
			val previewed = texture.densityPreview
			/** Where [tile] shows while a gesture moves or scales it, in page pixels; null where it stands. */
			fun moved(tile: WorkspaceAtlasTile): Rect? {
				if (draft?.layerId == tile.layerId) return Rect(draft.x.toFloat(), draft.y.toFloat(), (draft.x + tile.width).toFloat(), (draft.y + tile.height).toFloat())
				// The inspector's density slider: each tile grows or shrinks from its top left.
				previewed[tile.layerId]?.takeIf { it != 1f && scaling == null }?.let { factor ->
					return Rect(Offset(tile.x.toFloat(), tile.y.toFloat()), Size(tile.width * factor, tile.height * factor))
				}
				if (scaling == null || tile.layerId !in scaling.layerIds) return null
				val at = scaledTile(tile, scaling)
				return Rect(at.x.toFloat(), at.y.toFloat(), (at.x + at.width).toFloat(), (at.y + at.height).toFloat())
			}
			val filter = if (transform.scale >= 2f) FilterQuality.None else FilterQuality.Low
			/** Draws what [tile]'s cells hold - its raster, or that part of the page - into [at] (page pixels). */
			fun DrawScope.tilePixels(tile: WorkspaceAtlasTile, at: Rect, alpha: Float) {
				val image = tileImages[tile.layerId]
				val source = if (image == null) pageImage ?: return else null
				withTransform({
					translate(at.left, at.top); scale(at.width / tile.width, at.height / tile.height, Offset.Zero); translate(-tile.x.toFloat(), -tile.y.toFloat())
				}) {
					clipRect(tile.x.toFloat(), tile.y.toFloat(), (tile.x + tile.width).toFloat(), (tile.y + tile.height).toFloat()) {
						val mask = masks[tile.layerId]
						val draw: DrawScope.() -> Unit = {
							if (image != null) drawImage(image, dstOffset = IntOffset(tile.x, tile.y), dstSize = IntSize(tile.width, tile.height),
								alpha = alpha, filterQuality = filter)
							else drawImage(source!!, srcOffset = (snapshot.tilesByLayer[tile.layerId] ?: tile).let { IntOffset(it.x, it.y) },
								srcSize = (snapshot.tilesByLayer[tile.layerId] ?: tile).let { IntSize(it.width, it.height) },
								dstOffset = IntOffset(tile.x, tile.y), dstSize = IntSize(tile.width, tile.height), alpha = alpha, filterQuality = filter)
						}
						if (mask != null) clipPath(mask) { draw() } else draw()
					}
				}
			}
			clipRect {
				drawChecker(pageRect, colors.checkerLight, colors.checkerDark)
				val art = if (texture.heatmap) 0.45f else 1f
				if (tiles.any { moved(it) != null }) AtlasPageProbe.liftedDraws.incrementAndGet()
				if (gpuReady) {
					val w = size.width.toInt(); val h = size.height.toInt()
					val viewport = CanvasViewport(transform.scale.toDouble(), transform.left.toDouble(), transform.top.toDouble(),
						pageInfo.width.toFloat(), pageInfo.height.toFloat())
					val liftedKey = tiles.mapNotNull { tile -> moved(tile)?.let { tile.layerId to it } }
					gpuSubmission.submit(listOf(snapshot, page, w, h, viewport, texture.showPage, texture.heatmap, texture.showMeshes, meshes,
						selected, liftedKey, livePage, colors.accent, colors.textPrimary)) {
						atlasScene(AtlasSceneInput(snapshot, tiles, w, h, viewport, texture.showPage, texture.heatmap,
							meshes?.takeIf { texture.showMeshes }, maskCells, selected, livePage,
							colors.textPrimary.copy(alpha = 0.35f).toArgb(), colors.accent.copy(alpha = 0.9f).toArgb()), ::moved)
							.also { if (liftedKey.isNotEmpty()) AtlasPageProbe.liftedScenes += it }
					}
					val frame = gpuFrame
					if (liftedKey.isNotEmpty()) {
						if (frame != null && frame.scene in AtlasPageProbe.liftedScenes) AtlasPageProbe.liftedGpuFrames.incrementAndGet()
					}
					val image = gpuImage
					if (frame != null && image != null && !frame.bitmap.isClosed) {
						// The frame may be a step behind the camera: move it to where the camera is now, so a pan or zoom
						// follows the pointer at once and the exact frame replaces it when it lands.
						val k = (viewport.scale / frame.viewport.scale).toFloat()
						val tx = (viewport.offsetX - frame.viewport.offsetX * k).toFloat()
						val ty = (viewport.offsetY - frame.viewport.offsetY * k).toFloat()
						if (k == 1f && tx == 0f && ty == 0f) drawImage(image)
						else withTransform({ translate(tx, ty); scale(k, k, pivot = Offset.Zero) }) { drawImage(image) }
					}
				} else withTransform({ translate(transform.left, transform.top); scale(transform.scale, transform.scale, Offset.Zero) }) {
					if (texture.showPage && tileImages.isEmpty() && pageImage != null) {
						// Upscaled pages hold pixels no layer raster has; they are drawn as they are.
						drawImage(pageImage, dstSize = IntSize(pageInfo.width, pageInfo.height), alpha = art, filterQuality = filter)
						// Packed space no shown tile owns (a deleted layer's leftover), and tiles a gesture lifts, read as empty page.
						for (tile in atlas.tiles) if (tile.page == page && (tile.layerId !in shownIds || moved(tile) != null))
							drawRect(colors.checkerDark, Offset(tile.x.toFloat(), tile.y.toFloat()), Size(tile.width.toFloat(), tile.height.toFloat()))
					} else if (texture.showPage) {
						for (tile in tiles) if (moved(tile) == null) tilePixels(tile, Rect(tile.x.toFloat(), tile.y.toFloat(),
							(tile.x + tile.width).toFloat(), (tile.y + tile.height).toFloat()), art)
					}
				}
				drawRect(colors.border, pageRect.topLeft, pageRect.size, style = Stroke(1f))
				for (tile in tiles) {
					val lifted = moved(tile)
					val rect = transform.rect(tile)
					if (texture.heatmap && lifted == null && !gpuReady) drawRect(heatColor(TextureDensity.heat(snapshot.texelsPerCanvasUnit(tile))).copy(alpha = 0.55f), rect.topLeft, rect.size)
					val isSelected = tile.layerId in selected
					val isHovered = tile.layerId == hovered
					if (lifted != null) {
						// Where the tile was: a ghost frame.
						drawRect(colors.textMuted.copy(alpha = 0.6f), rect.topLeft, rect.size, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))))
						continue
					}
					if (texture.showOutlines || isSelected || isHovered) {
						val color = when {
							isSelected -> colors.accent
							isHovered -> colors.textPrimary
							else -> colors.textMuted.copy(alpha = 0.7f)
						}
						// A tile arranged by its meshes may share its rectangle; the dotted frame says so.
						drawRect(color, rect.topLeft, rect.size, style = Stroke(if (isSelected) 2f else 1f,
							pathEffect = if (tile.shaped && !isSelected) PathEffect.dashPathEffect(floatArrayOf(2f, 3f)) else null))
					}
					val badge = (6.dp.toPx()).coerceAtMost(min(rect.width, rect.height) / 2.5f)
					if (tile.locked && badge >= 3f) {
						drawRoundRect(colors.highlight, Offset(rect.right - badge * 1.25f, rect.top + badge * 0.25f), Size(badge, badge),
							androidx.compose.ui.geometry.CornerRadius(badge / 4f))
					}
					// Corner handles: the density grips of a selected tile, as the edit canvas draws its transform box.
					if (isSelected && draft == null && scaling == null && !busy) {
						for (grip in grips(tile, transform, handleRadius)) {
							val at = grip.at
							drawRect(colors.panelBackground, at - Offset(handleRadius / 2f + 1f, handleRadius / 2f + 1f), Size(handleRadius + 2f, handleRadius + 2f))
							drawRect(colors.accent, at - Offset(handleRadius / 2f, handleRadius / 2f), Size(handleRadius, handleRadius))
						}
					}
				}
				if (texture.showMeshes && meshes != null && !gpuReady) {
					// The wireframes are in page pixels; one transform draws them all as hairlines - a zero-width stroke,
					// one device pixel at any scale, which Skia draws without outlining a stroke.
					withTransform({ translate(transform.left, transform.top); scale(transform.scale, transform.scale, Offset.Zero) }) {
						for (tile in tiles) {
							val path = meshes.paths[tile.layerId] ?: continue
							val chosen = tile.layerId in selected
							val lifted = moved(tile)
							// The wireframe is where the committed tile is; a queued edit or a gesture moves it with the tile.
							val committed = snapshot.tilesByLayer[tile.layerId] ?: tile
							val at = lifted ?: Rect(tile.x.toFloat(), tile.y.toFloat(), (tile.x + tile.width).toFloat(), (tile.y + tile.height).toFloat())
							val color = if (chosen || lifted != null) colors.accent.copy(alpha = 0.9f) else colors.textPrimary.copy(alpha = 0.35f)
							if (committed == tile && lifted == null) drawPath(path, color, style = HAIRLINE)
							else withTransform({
								translate(at.left, at.top); scale(at.width / committed.width, at.height / committed.height, Offset.Zero)
								translate(-committed.x.toFloat(), -committed.y.toFloat())
							}) { drawPath(path, color, style = HAIRLINE) }
						}
					}
				}
				// The lifted tiles, live: their pixels where the gesture puts them, over everything they pass.
				for (tile in tiles) {
					val at = moved(tile) ?: continue
					if (texture.showPage && !gpuReady) withTransform({ translate(transform.left, transform.top); scale(transform.scale, transform.scale, Offset.Zero) }) {
						tilePixels(tile, at, 1f)
					}
					val rect = transform.rect(at.left, at.top, at.width, at.height)
					// Where the tiles' meshes would meet another's, the release changes nothing: red says so beforehand.
					val color = if ((draft?.layerId == tile.layerId && draft.collides) || (scaling?.collides == true && tile.layerId in scaling.layerIds))
						colors.error else colors.accent
					if (!texture.showPage) drawRect(color.copy(alpha = 0.18f), rect.topLeft, rect.size)
					drawRect(color, rect.topLeft, rect.size, style = Stroke(2f))
				}
				marquee?.let { box ->
					drawRect(colors.accent.copy(alpha = 0.12f), box.topLeft, box.size)
					drawRect(colors.accent, box.topLeft, box.size, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3f))))
				}
			}
		}

		// Top left: arranging, as the edit canvas's mode bar - the one-shot Arrange with its options, the automatic
		// mode, and the pages.
		FloatingBar(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp)) {
			var arrangeMenu by remember { mutableStateOf(false) }
			Box {
				AccentSplitButton(
					label = tr("texture.atlas.arrange"),
					icon = { if (texture.arrangeByMesh) IconArrangeMesh(it) else IconArrangeRect(it) },
					onClick = { vm.arrangeAtlas(snapshot, selection) },
					open = arrangeMenu,
					onMenu = { arrangeMenu = !arrangeMenu },
					enabled = !busy,
					tooltip = tr("texture.atlas.arrangeHint"),
					menuTooltip = tr("texture.atlas.arrangeMenu"),
				)
				// The menu holds only how the next Arrange works - a choice of method and a scope switch; the button runs it.
				FloatingMenu(arrangeMenu, { arrangeMenu = false }, width = 270.dp) {
					FloatingMenuSection(tr("texture.atlas.arrangeMethod"))
					FloatingMenuRadio(tr("texture.atlas.arrangeByMesh"), texture.arrangeByMesh,
						{ vm.setTextureArrangeOptions(true, texture.arrangeSelectionOnly) }, hint = tr("texture.atlas.arrangeMeshHint"),
						icon = { IconArrangeMesh(it) })
					FloatingMenuRadio(tr("texture.atlas.arrangeByRect"), !texture.arrangeByMesh,
						{ vm.setTextureArrangeOptions(false, texture.arrangeSelectionOnly) }, hint = tr("texture.atlas.arrangeRectHint"),
						icon = { IconArrangeRect(it) })
					FloatingMenuDivider()
					FloatingMenuSection(tr("texture.atlas.arrangeScope"))
					FloatingMenuSwitch(tr("texture.atlas.arrangeSelection"), texture.arrangeSelectionOnly,
						{ vm.setTextureArrangeOptions(texture.arrangeByMesh, !texture.arrangeSelectionOnly) }, hint = tr("texture.atlas.arrangeSelectionHint"))
				}
			}
			BarDivider()
			// The automatic arrangement is a mode, so a switch; Arrange is one action that keeps its result.
			BarSwitch(tr("texture.atlas.auto"), atlas.auto, { vm.setAtlasAuto(snapshot, !atlas.auto) }, enabled = !busy,
				tooltip = tr("texture.atlas.autoHint"))
			if (atlas.pages.size > 1) {
				BarDivider()
				for (info in atlas.pages) {
					BarChip("${info.index + 1}", info.index == page, { vm.setTexturePage(info.index) },
						tooltip = tr("texture.atlas.page", info.index + 1, info.width))
				}
			}
		}

		// Top right: the budget.
		FloatingBar(Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 8.dp)) {
			var budgetMenu by remember { mutableStateOf(false) }
			Box {
				BarChip(tr("texture.atlas.budgetSummary", atlas.budget.pageSize, atlas.pages.size, atlas.budget.maxPages), false,
					{ budgetMenu = !budgetMenu }, tooltip = tr("texture.atlas.budget"), chevron = true, open = budgetMenu,
					icon = { IconAtlasPages(it) })
				FloatingMenu(budgetMenu, { budgetMenu = false }, width = 270.dp, alignment = Alignment.TopEnd) {
					FloatingMenuSection(tr("texture.atlas.budget"))
					Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { AtlasBudgetControls(vm, snapshot, !busy, labelWidth = 96.dp) }
				}
			}
		}

		// Top centre: why the last command was refused, and what the layout reported.
		val messages = (texture.error?.let { listOf(it to colors.error) }.orEmpty() +
			(texture.notices.ifEmpty { atlas.notices }).map { it to colors.warning }).take(3)
		var dismissed by remember(snapshot.state) { mutableStateOf(false) }
		if (messages.isNotEmpty() && (!dismissed || texture.error != null)) {
			NoticeBanner(messages, onClose = { dismissed = true; vm.clearTextureError() },
				modifier = Modifier.align(Alignment.TopCenter).padding(top = 42.dp, start = 8.dp, end = 8.dp))
		}

		// Bottom right: the display rail of the edit canvas, with the heat scale beside it while the heatmap shows.
		if (texture.heatmap) {
			Box(Modifier.align(Alignment.BottomEnd).padding(end = 50.dp, bottom = 8.dp).width(170.dp)
				.frostedGlass(RoundedCornerShape(6.dp), elevation = 2.dp, alpha = 0.82f)
				.padding(horizontal = 8.dp, vertical = 5.dp)) { HeatLegend() }
		}
		CanvasOptionsRail(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp)) {
			RailToggle(tr("texture.atlas.showPage"), texture.showPage, { IconTextureView(tint = it) }) { vm.setTextureShowPage(!texture.showPage) }
			RailToggle(tr("texture.atlas.meshes"), texture.showMeshes, { IconMeshWireframe(tint = it) }) { vm.setTextureShowMeshes(!texture.showMeshes) }
			RailToggle(tr("texture.atlas.outlines"), texture.showOutlines, { IconTileOutlines(it) }) { vm.setTextureOutlines(!texture.showOutlines) }
			RailDivider()
			RailToggle(tr("texture.atlas.heatmap"), texture.heatmap, { IconHeatmap() }) { vm.setTextureHeatmap(!texture.heatmap) }
		}

		// Bottom left: what the pointer is over or the gesture is doing; otherwise the page at a glance.
		val status = statusText(snapshot, tileDrag, densityDrag, hovered, page, zoom * fitScale)
		Text(
			status.first,
			style = typography.caption.copy(fontSize = 11.sp),
			color = if (status.second) colors.warning else colors.textPrimary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp, end = if (texture.heatmap) 230.dp else 50.dp)
				.frostedGlass(RoundedCornerShape(6.dp), elevation = 2.dp, alpha = 0.78f).padding(horizontal = 8.dp, vertical = 4.dp),
		)

		AtlasContextMenu(menu, { menu = null }, state, vm, snapshot, selection, busy, onFrame = ::frameSelection, onReset = ::resetView)
	}
}

/**
 * The pointer the atlas page shows, as the edit canvas picks its own ([io.github.psd2live.ui.CanvasEditor.activeCursor]):
 * move while panning or dragging a tile, the open hand while Space is held, a crosshair for a selection box,
 * diagonal arrows on a density grip ([scaling] or the hovered [corner], each with its opposite corner) and a hand
 * over a tile that can be picked or dragged.
 */
private fun atlasCursor(panning: Boolean, space: Boolean, moving: Boolean, boxing: Boolean, scaling: Pair<Offset, Offset>?,
                        corner: Pair<Offset, Offset>?, overTile: Boolean): java.awt.Cursor {
	fun cursor(type: Int) = java.awt.Cursor.getPredefinedCursor(type)
	fun diagonal(grip: Pair<Offset, Offset>): java.awt.Cursor {
		val (at, opposite) = grip
		// Top left and bottom right share one diagonal, top right and bottom left the other.
		return cursor(if ((at.x - opposite.x) * (at.y - opposite.y) > 0f) java.awt.Cursor.NW_RESIZE_CURSOR else java.awt.Cursor.NE_RESIZE_CURSOR)
	}
	return when {
		panning || moving -> cursor(java.awt.Cursor.MOVE_CURSOR)
		space -> cursor(java.awt.Cursor.HAND_CURSOR)
		scaling != null -> diagonal(scaling)
		boxing -> cursor(java.awt.Cursor.CROSSHAIR_CURSOR)
		corner != null -> diagonal(corner)
		overTile -> cursor(java.awt.Cursor.HAND_CURSOR)
		else -> java.awt.Cursor.getDefaultCursor()
	}
}

/** A density grip: where it shows in the view, and the tile corner it drags with the corner opposite it, in page pixels. */
private class Grip(val at: Offset, val corner: Offset, val opposite: Offset)

/**
 * [tile]'s density grips: one at each corner, or, while the tile shows too small for four apart, one just outside
 * its bottom right corner, so even a tiny tile can be scaled.
 */
private fun grips(tile: WorkspaceAtlasTile, transform: PageTransform, handle: Float): List<Grip> {
	val rect = transform.rect(tile)
	if (rect.width >= handle * 3 && rect.height >= handle * 3)
		return corners(tile).map { (corner, opposite) -> Grip(transform.toView(corner.x, corner.y), corner, opposite) }
	val (corner, opposite) = corners(tile)[2]
	return listOf(Grip(rect.bottomRight + Offset(handle, handle), corner, opposite))
}

/** A tile's four corners, each with the corner opposite it, in page pixels. */
private fun corners(tile: WorkspaceAtlasTile): List<Pair<Offset, Offset>> {
	val l = tile.x.toFloat(); val t = tile.y.toFloat(); val r = (tile.x + tile.width).toFloat(); val b = (tile.y + tile.height).toFloat()
	return listOf(Offset(l, t) to Offset(r, b), Offset(r, t) to Offset(l, b), Offset(r, b) to Offset(l, t), Offset(l, b) to Offset(r, t))
}

/** The status pill: the gesture, the tile under the pointer, or the page; true when it warns. */
private fun statusText(snapshot: TextureSnapshot, draft: TileDragDraft?, scaling: DensityDrag?,
                       hovered: String?, page: Int, scale: Float): Pair<String, Boolean> {
	val zoom = "${Math.round(scale * 100f)}%"
	if (scaling != null) {
		val layer = snapshot.layer(scaling.primary)
		val density = (layer?.override?.density ?: 1f) * scaling.factor
		val tile = snapshot.tilesByLayer[scaling.primary]
		val perUnit = tile?.let { snapshot.texelsPerCanvasUnit(it) * scaling.factor }
		return tr("texture.atlas.scaling", multiplier(density), perUnit?.let(TextureDensity::format) ?: "-", scaling.layerIds.size) to false
	}
	val id = draft?.layerId ?: hovered
	val tile = id?.let(snapshot.tilesByLayer::get)
	if (id != null && tile != null) {
		val name = snapshot.layer(id)?.name ?: id
		val at = if (draft != null) "(${draft.x}, ${draft.y})" else "(${tile.x}, ${tile.y})"
		return tr("texture.atlas.tileInfo", name, tile.width, tile.height, at, TextureDensity.format(snapshot.texelsPerCanvasUnit(tile))) to (draft?.collides == true)
	}
	val info = snapshot.atlas.pages[page]
	return (tr("texture.atlas.fit", "%.0f".format(snapshot.atlas.fit * 100f)) + "  ·  " +
		tr("texture.atlas.occupancy", snapshot.tiles(page).size, "%.0f".format(info.occupancy * 100f), info.width, info.height) +
		"  ·  " + zoom) to (snapshot.atlas.fit < 1f)
}

/** The right-click menu: the selected tiles' texture commands, or the page's packing and view commands. */
@Composable
private fun AtlasContextMenu(
	menu: AtlasMenu?, onDismiss: () -> Unit, state: PSD2LiveState, vm: PSD2LiveViewModel, snapshot: TextureSnapshot,
	selection: List<String>, busy: Boolean, onFrame: () -> Unit, onReset: () -> Unit,
) {
	val colors = LocalToolColors.current
	val frameKey = state.keymap.labelFor(ShortcutAction.FRAME_VIEW)
	val resetKey = state.keymap.labelFor(ShortcutAction.RESET_CAMERA)
	TreeContextMenu(menu != null, onDismiss, clickOffset = menu?.at ?: Offset.Zero, frosted = true) {
		fun run(action: () -> Unit) { onDismiss(); action() }
		if (menu?.layerId != null && selection.isNotEmpty()) {
			val layers = selection.mapNotNull(snapshot::layer)
			val single = layers.singleOrNull()
			CompactMenuSection(if (single != null) single.name else tr("texture.inspector.multiple", layers.size))
			CompactMenuItem(tr("texture.menu.density2x"), onClick = { run { vm.scaleTextureDensity(snapshot, selection, 2f) } },
				enabled = !busy && layers.any { (it.override.density ?: 1f) < TextureDensity.MAX })
			CompactMenuItem(tr("texture.menu.densityHalf"), onClick = { run { vm.scaleTextureDensity(snapshot, selection, 0.5f) } },
				enabled = !busy && layers.any { (it.override.density ?: 1f) > TextureDensity.MIN })
			CompactMenuItem(tr("texture.inspector.resetDensity"), onClick = { run { vm.setTextureDensity(snapshot, selection, null) } },
				enabled = !busy && layers.any { it.override.density != null })
			val locked = layers.all { it.override.lock }
			CompactMenuItem(tr("texture.inspector.lock"), onClick = { run { vm.setTextureLock(snapshot, selection, !locked) } },
				enabled = !busy, active = locked, icon = { IconLock(locked = locked, tint = if (locked) colors.accent else colors.textMuted) })
			CompactMenuDivider()
			CompactMenuItem(tr("texture.menu.arrangeSelection"), onClick = { run { vm.arrangeAtlas(snapshot, selection, onlySelection = true) } },
				enabled = !busy)
			if (single != null) {
				CompactMenuItem(tr("texture.inspector.replace"), onClick = { run {
					NativeFilePicker.chooseTransparentImages().firstOrNull()?.let { vm.replaceLayerImage(snapshot, single.layerId, it) }
				} }, enabled = !busy)
			}
			CompactMenuItem(tr("texture.menu.frame"), onClick = { run(onFrame) }, trailingText = frameKey)
		} else {
			CompactMenuSection(tr("texture.menu.page"))
			CompactMenuItem(tr("texture.atlas.arrange"), onClick = { run { vm.arrangeAtlas(snapshot, onlySelection = false) } }, enabled = !busy)
			CompactMenuDivider()
			CompactMenuItem(tr("texture.menu.selectAll"), onClick = { run { vm.selectLayers(snapshot.tiles(state.textureWorkspace.selectedPage
				.coerceIn(0, snapshot.atlas.pages.lastIndex)).map { it.layerId }) } }, trailingText = state.keymap.labelFor(ShortcutAction.SELECT_ALL))
			CompactMenuItem(tr("texture.menu.fitPage"), onClick = { run(onReset) }, trailingText = resetKey)
		}
	}
}

/** A zero-width stroke: one device pixel at any scale. */
private val HAIRLINE = Stroke(0f)

/** A checkerboard behind the page, so transparent texels read as empty; cells keep to the view's grid. */
private fun DrawScope.drawChecker(rect: Rect, light: Color, dark: Color) {
	val left = max(rect.left, 0f); val top = max(rect.top, 0f)
	val right = min(rect.right, size.width); val bottom = min(rect.bottom, size.height)
	if (right <= left || bottom <= top) return
	// One draw: a two-by-two cell tile repeated from the view's origin, instead of a rectangle per cell.
	drawRect(checkerBrush(Math.round(8.dp.toPx()).coerceAtLeast(1), light, dark), Offset(left, top), Size(right - left, bottom - top))
}

private var checkerCache: Pair<Triple<Int, Color, Color>, androidx.compose.ui.graphics.ShaderBrush>? = null

/** The checkerboard of [cell]-pixel squares as a repeating brush, kept while the cell and colours stay the same. */
private fun checkerBrush(cell: Int, light: Color, dark: Color): androidx.compose.ui.graphics.ShaderBrush {
	val key = Triple(cell, light, dark)
	checkerCache?.takeIf { it.first == key }?.let { return it.second }
	val image = ImageBitmap(cell * 2, cell * 2)
	androidx.compose.ui.graphics.Canvas(image).apply {
		val paint = androidx.compose.ui.graphics.Paint()
		paint.color = dark; drawRect(0f, 0f, cell * 2f, cell * 2f, paint)
		paint.color = light; drawRect(cell.toFloat(), 0f, cell * 2f, cell.toFloat(), paint); drawRect(0f, cell.toFloat(), cell.toFloat(), cell * 2f, paint)
	}
	val brush = androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(image,
		androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated))
	checkerCache = key to brush
	return brush
}

/**
 * Each tile's meshes on [page] as one wireframe per layer: the preview model's triangles at their texture
 * coordinates, when that model's atlas is this version's. Built off the UI thread, once per model and page; null
 * until they are, or while the model is another version's.
 */
@Composable
private fun rememberMeshes(state: PSD2LiveState, snapshot: TextureSnapshot, page: Int): AtlasMeshes? {
	val model = state.previewModel
	val built by produceState<Pair<Any, AtlasMeshes>?>(null, model, snapshot, page) {
		if (model == null || !snapshot.matches(model.atlas)) return@produceState
		val key = Triple(model, snapshot, page)
		value = key to withContext(Dispatchers.Default) {
			val image = model.atlas.pages.getOrNull(page)?.image ?: return@withContext AtlasMeshes(emptyMap(), emptyMap())
			val paths = HashMap<String, Path>()
			val segments = HashMap<String, ArrayList<Float>>()
			for (drawable in model.rig.puppet.drawables) {
				val mesh = drawable.mesh ?: continue
				if (drawable.texturePage != page) continue
				val layerId = model.rig.layerIdByDrawableId[drawable.id.raw] ?: continue
				val path = paths.getOrPut(layerId) { Path() }
				val lines = segments.getOrPut(layerId) { ArrayList() }
				val uv = mesh.uvs
				fun x(v: Int) = uv[v * 2] * image.width
				fun y(v: Int) = uv[v * 2 + 1] * image.height
				// Each edge once: neighbouring triangles share theirs, and a wireframe is mostly shared edges.
				val edges = io.github.psd2live.render.MeshWireframe.uniqueEdges(mesh.indices)
				for (e in edges.indices step 2) {
					val a = edges[e]; val b = edges[e + 1]
					if (a * 2 + 1 >= uv.size || b * 2 + 1 >= uv.size) continue
					path.moveTo(x(a), y(a)); path.lineTo(x(b), y(b))
					lines += x(a); lines += -y(a); lines += x(b); lines += -y(b)
				}
			}
			AtlasMeshes(paths, segments.mapValues { it.value.toFloatArray() })
		}
	}
	return built?.takeIf { it.first == Triple(model, snapshot, page) }?.second
}

/**
 * Each tile's raster as a Compose image, made off the UI thread. Images are kept by raster identity, so a commit
 * converts only the rasters it changed; empty while the pages are upscaled, which only the page image shows, and
 * while the GPU draws the page from the rasters themselves.
 */
@Composable
private fun rememberTileImages(snapshot: TextureSnapshot, enabled: Boolean): Map<String, ImageBitmap> {
	val value by produceState(emptyMap<String, ImageBitmap>(), snapshot, enabled) {
		value = if (snapshot.upscaled || !enabled) emptyMap() else withContext(Dispatchers.Default) {
			snapshot.atlas.tiles.mapNotNull { tile ->
				val raster = snapshot.tileRaster(tile.layerId) ?: return@mapNotNull null
				tile.layerId to synchronized(tileImageCache) {
					tileImageCache.getOrPut(raster.rgba) { rgbaImageBitmap(raster.width, raster.height, raster.rgba) }
				}
			}.toMap()
		}
	}
	return value
}

private val tileImageCache = java.util.WeakHashMap<ByteArray, ImageBitmap>()

/** The cells of a tile arranged by its meshes, in page pixels: the only part of its rectangle it shows. */
private fun maskPath(shape: io.github.psd2live.core.AtlasArrange.Shape): Path = Path().apply {
	val cell = io.github.psd2live.core.AtlasArrange.CELL.toFloat()
	for (r in 0 until shape.height) {
		val run = shape.runs[r]
		for (k in run.indices step 2) addRect(Rect((shape.column + run[k]) * cell, (shape.row + r) * cell,
			(shape.column + run[k + 1]) * cell, (shape.row + r + 1) * cell))
	}
}

/**
 * The page's pixels as a Compose image, made off the UI thread: the preview model's page when it is this
 * version's atlas, otherwise the page PNG of the captured version.
 */
@Composable
private fun rememberPageImage(state: PSD2LiveState, snapshot: TextureSnapshot, page: Int, enabled: Boolean): ImageBitmap? {
	val atlas = state.previewModel?.atlas
	val value by produceState<ImageBitmap?>(null, snapshot, atlas, page, enabled) {
		if (!enabled) { value = null; return@produceState }
		value = withContext(Dispatchers.Default) {
			runCatching {
				val live = atlas?.takeIf { snapshot.matches(it) }?.pages?.getOrNull(page)?.image
				(live ?: ImageIO.read(ByteArrayInputStream(snapshot.pagePng(page))))?.toImageBitmapFast()
			}.getOrNull()
		}
	}
	return value
}
