package io.github.psd2live.ui.views.texture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.application.WorkspaceAtlasTile
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactMenuSection
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconLock
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutScope
import io.github.psd2live.ui.state.TextureDensity
import io.github.psd2live.ui.state.TextureSnapshot
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.ui.utils.NativeFilePicker
import io.github.psd2live.ui.utils.toImageBitmapFast
import io.github.psd2live.ui.views.CanvasNavigation
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

/** Page-to-view mapping: a texture pixel (x, y) shows at (left + x * scale, top + y * scale). */
private data class PageTransform(val left: Float, val top: Float, val scale: Float) {
	fun toView(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)
	fun toPage(point: Offset) = Offset((point.x - left) / scale, (point.y - top) / scale)
	fun rect(x: Float, y: Float, width: Float, height: Float) = Rect(toView(x, y), Size(width * scale, height * scale))
	fun rect(tile: WorkspaceAtlasTile) = rect(tile.x.toFloat(), tile.y.toFloat(), tile.width.toFloat(), tile.height.toFloat())
}

/** A corner-handle drag in flight: [factor] scales every selected tile's density, about [anchor]'s tile corner. */
private data class DensityDrag(val layerIds: List<String>, val primary: String, val anchor: Offset, val corner: Offset, val factor: Float)

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
	val tiles = remember(snapshot, page) { snapshot.tiles(page) }
	val image = rememberPageImage(state, snapshot, page)
	val selection = remember(state.selectedLayerIds, state.selectedLayerId, snapshot) { textureSelection(state, snapshot) }
	val selected = remember(selection) { selection.toSet() }
	val busy = texture.busy || state.isAnalyzing || state.isGenerating

	var zoom by remember(page) { mutableStateOf(1f) }
	var pan by remember(page) { mutableStateOf(Offset.Zero) }
	var hovered by remember(snapshot, page) { mutableStateOf<String?>(null) }
	var viewSize by remember { mutableStateOf(IntSize.Zero) }
	var marquee by remember { mutableStateOf<Rect?>(null) }
	var densityDrag by remember(snapshot) { mutableStateOf<DensityDrag?>(null) }
	var space by remember { mutableStateOf(false) }
	var menu by remember { mutableStateOf<AtlasMenu?>(null) }
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

	/** The selected tile whose corner is under [point], with that corner and the one opposite it, in page pixels. */
	fun hitCorner(point: Offset): Triple<WorkspaceAtlasTile, Offset, Offset>? {
		for (tile in currentTiles.filter { it.layerId in currentSelected }) {
			val rect = currentTransform.rect(tile)
			if (rect.width < handleRadius * 3 || rect.height < handleRadius * 3) continue
			for ((corner, opposite) in corners(tile)) {
				if ((currentTransform.toView(corner.x, corner.y) - point).getDistance() <= handleRadius * 1.4f) return Triple(tile, corner, opposite)
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
			.focusRequester(focusRequester)
			.onKeyEvent { event ->
				if (state.keyCapture != null) return@onKeyEvent false
				if (event.key == Key.Spacebar) { space = event.type == KeyEventType.KeyDown; return@onKeyEvent true }
				if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
				if (event.key == Key.Escape) {
					when {
						texture.dragDraft != null || densityDrag != null || marquee != null -> {
							vm.cancelTextureTileDrag(); densityDrag = null; marquee = null
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
			.onPointerEvent(PointerEventType.Move) { event ->
				hovered = event.changes.firstOrNull()?.let { hit(it.position)?.layerId }
			}
			.onPointerEvent(PointerEventType.Exit) { hovered = null }
			.pointerInput(page, pageInfo.width, pageInfo.height) {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					val event = currentEvent
					focusRequester.requestFocus()
					// Middle button, or Space with the primary one, pans - as on the edit canvas.
					if (CanvasNavigation.pans(event.button, currentSpace)) {
						var last = down.position
						while (true) {
							val move = awaitPointerEvent()
							val change = move.changes.firstOrNull { it.id == down.id } ?: break
							if (!change.pressed) break
							pan += change.position - last
							last = change.position
							change.consume()
						}
						return@awaitEachGesture
					}
					if (event.button == PointerButton.Secondary) {
						val tile = hit(down.position)
						if (tile != null && tile.layerId !in currentSelected) vm.selectLayer(tile.layerId)
						menu = AtlasMenu(down.position, tile?.layerId)
						return@awaitEachGesture
					}
					if (event.button != null && event.button != PointerButton.Primary) return@awaitEachGesture
					val select = CanvasNavigation.selectMode(event.keyboardModifiers)
					val snapshotAtStart = currentSnapshot

					// A selected tile's corner: scale the selection's density.
					val corner = if (select == CanvasNavigation.SelectMode.REPLACE && !currentBusy) hitCorner(down.position) else null
					if (corner != null) {
						val (tile, at, anchor) = corner
						val start = (at - anchor).getDistance()
						val primaryDensity = snapshotAtStart.layer(tile.layerId)?.override?.density ?: 1f
						var drag = DensityDrag(currentSelection, tile.layerId, anchor, at, 1f)
						densityDrag = drag
						while (true) {
							val move = awaitPointerEvent()
							val change = move.changes.firstOrNull { it.id == down.id } ?: break
							if (!change.pressed || !move.buttons.isPrimaryPressed) break
							val distance = (currentTransform.toPage(change.position) - anchor).getDistance()
							val density = TextureDensity.dragged(primaryDensity, start, distance)
							drag = drag.copy(factor = density / primaryDensity)
							densityDrag = drag
							change.consume()
						}
						densityDrag = null
						if (drag.factor != 1f) vm.scaleTextureDensity(snapshotAtStart, drag.layerIds, drag.factor)
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
						vm.dragTextureTile(snapshotAtStart, tile.layerId, tile.x + at.x - start.x, tile.y + at.y - start.y,
							snap = 6f / currentTransform.scale)
						change.consume()
					}
					if (dragging) {
						vm.endTextureTileDrag(snapshotAtStart)
						lastClick = null
					} else {
						vm.cancelTextureTileDrag()
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
			val pageRect = transform.rect(0f, 0f, pageInfo.width.toFloat(), pageInfo.height.toFloat())
			clipRect {
				drawChecker(pageRect, colors.checkerLight, colors.checkerDark)
				if (image != null) {
					drawImage(
						image,
						dstOffset = IntOffset(pageRect.left.toInt(), pageRect.top.toInt()),
						dstSize = IntSize(pageRect.width.toInt().coerceAtLeast(1), pageRect.height.toInt().coerceAtLeast(1)),
						// The heatmap dims the art so the tint reads on any colour.
						alpha = if (texture.heatmap) 0.45f else 1f,
						filterQuality = if (transform.scale >= 2f) FilterQuality.None else FilterQuality.Low,
					)
				}
				// Packed space no shown tile owns (a deleted layer's leftover) reads as empty page.
				val shown = tiles.mapTo(HashSet()) { it.layerId }
				for (tile in atlas.tiles) {
					if (tile.page != page || tile.layerId in shown) continue
					val rect = transform.rect(tile)
					drawChecker(rect, colors.checkerLight, colors.checkerDark)
				}
				drawRect(colors.border, pageRect.topLeft, pageRect.size, style = Stroke(1f))
				val draft = texture.dragDraft?.takeIf { it.page == page }
				val scaling = densityDrag
				for (tile in tiles) {
					val rect = transform.rect(tile)
					if (texture.heatmap) drawRect(heatColor(TextureDensity.heat(snapshot.texelsPerCanvasUnit(tile))).copy(alpha = 0.55f), rect.topLeft, rect.size)
					val isSelected = tile.layerId in selected
					val isHovered = tile.layerId == hovered
					if (texture.showOutlines || isSelected || isHovered) {
						val color = when {
							isSelected -> colors.accent
							isHovered -> colors.textPrimary
							else -> colors.textMuted.copy(alpha = 0.7f)
						}
						drawRect(color, rect.topLeft, rect.size, style = Stroke(if (isSelected) 2f else 1f))
					}
					val badge = (6.dp.toPx()).coerceAtMost(min(rect.width, rect.height) / 2.5f)
					if (tile.pinned && badge >= 3f) {
						drawCircle(colors.warning, badge / 2f, Offset(rect.left + badge * 0.75f, rect.top + badge * 0.75f))
					}
					if (tile.locked && badge >= 3f) {
						drawRoundRect(colors.highlight, Offset(rect.right - badge * 1.25f, rect.top + badge * 0.25f), Size(badge, badge),
							androidx.compose.ui.geometry.CornerRadius(badge / 4f))
					}
					if (draft?.layerId == tile.layerId || scaling?.layerIds?.contains(tile.layerId) == true) {
						drawRect(colors.textMuted.copy(alpha = 0.35f), rect.topLeft, rect.size)
					}
					// Corner handles: the density grips of a selected tile, as the edit canvas draws its transform box.
					if (isSelected && draft == null && scaling == null && rect.width >= handleRadius * 3 && rect.height >= handleRadius * 3) {
						for ((corner, _) in corners(tile)) {
							val at = transform.toView(corner.x, corner.y)
							drawRect(colors.panelBackground, at - Offset(handleRadius / 2f + 1f, handleRadius / 2f + 1f), Size(handleRadius + 2f, handleRadius + 2f))
							drawRect(colors.accent, at - Offset(handleRadius / 2f, handleRadius / 2f), Size(handleRadius, handleRadius))
						}
					}
				}
				if (draft != null) {
					val tile = snapshot.tilesByLayer[draft.layerId]
					if (tile != null) {
						val rect = transform.rect(draft.x.toFloat(), draft.y.toFloat(), tile.width.toFloat(), tile.height.toFloat())
						val color = if (draft.collides) colors.error else colors.accent
						drawRect(color.copy(alpha = 0.22f), rect.topLeft, rect.size)
						drawRect(color, rect.topLeft, rect.size, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
					}
				}
				if (scaling != null) {
					// The tiles at the dragged density: the grabbed one grows from its opposite corner, the others from their own.
					for (id in scaling.layerIds) {
						val tile = tiles.firstOrNull { it.layerId == id } ?: continue
						val width = tile.width * scaling.factor; val height = tile.height * scaling.factor
						val origin = if (id == scaling.primary) Offset(
							if (scaling.anchor.x > tile.x) scaling.anchor.x - width else scaling.anchor.x,
							if (scaling.anchor.y > tile.y) scaling.anchor.y - height else scaling.anchor.y,
						) else Offset(tile.x.toFloat(), tile.y.toFloat())
						val rect = transform.rect(origin.x, origin.y, width, height)
						drawRect(colors.accent.copy(alpha = 0.18f), rect.topLeft, rect.size)
						drawRect(colors.accent, rect.topLeft, rect.size, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
					}
				}
				marquee?.let { box ->
					drawRect(colors.accent.copy(alpha = 0.12f), box.topLeft, box.size)
					drawRect(colors.accent, box.topLeft, box.size, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3f))))
				}
			}
		}

		// Top left: pages.
		if (atlas.pages.size > 1) {
			Row(
				Modifier.align(Alignment.TopStart).padding(8.dp)
					.frostedGlass(RoundedCornerShape(6.dp), elevation = 2.dp, alpha = 0.82f).padding(3.dp),
				horizontalArrangement = Arrangement.spacedBy(3.dp),
			) {
				for (info in atlas.pages) {
					CompactToggleChip(tr("texture.atlas.pageShort", info.index + 1), info.index == page, { vm.setTexturePage(info.index) },
						showCheckWhenSelected = false, tooltip = tr("texture.atlas.page", info.index + 1, info.width))
				}
			}
		}

		// Top right: packing and the budget.
		Row(
			Modifier.align(Alignment.TopEnd).padding(8.dp)
				.frostedGlass(RoundedCornerShape(6.dp), elevation = 2.dp, alpha = 0.82f).padding(3.dp),
			horizontalArrangement = Arrangement.spacedBy(3.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			var packMenu by remember { mutableStateOf(false) }
			var budgetMenu by remember { mutableStateOf(false) }
			Box {
				CompactButton(tr("texture.atlas.pack") + " ▾", onClick = { packMenu = true }, enabled = !busy, height = 22.dp)
				TreeContextMenu(packMenu, { packMenu = false }, frosted = true) {
					CompactMenuItem(tr("texture.atlas.packKeepPins"), onClick = { packMenu = false; vm.packAtlas(snapshot, keepPins = true) })
					CompactMenuItem(tr("texture.atlas.packAll"), onClick = { packMenu = false; vm.packAtlas(snapshot, keepPins = false) },
						enabled = atlas.tiles.any { it.pinned })
				}
			}
			Box {
				CompactButton("${atlas.budget.pageSize} · ${atlas.pages.size}/${atlas.budget.maxPages} ▾", onClick = { budgetMenu = true }, height = 22.dp)
				TreeContextMenu(budgetMenu, { budgetMenu = false }, frosted = true, minWidth = 250.dp, maxWidth = 300.dp) {
					CompactMenuSection(tr("texture.atlas.budget"))
					Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { AtlasBudgetControls(vm, snapshot, !busy, labelWidth = 92.dp) }
				}
			}
		}

		// Top centre: why the last command was refused, and what the layout reported.
		val messages = (texture.error?.let { listOf(it to colors.error) }.orEmpty() +
			(texture.notices.ifEmpty { atlas.notices }).map { it to colors.warning }).take(3)
		if (messages.isNotEmpty()) {
			Row(
				Modifier.align(Alignment.TopCenter).padding(top = if (atlas.pages.size > 1) 44.dp else 40.dp, start = 8.dp, end = 8.dp)
					.widthIn(max = 460.dp)
					.frostedGlass(RoundedCornerShape(6.dp), elevation = 2.dp, alpha = 0.9f).padding(horizontal = 8.dp, vertical = 4.dp),
				verticalAlignment = Alignment.Top,
			) {
				Column(Modifier.weight(1f, fill = false)) {
					for ((message, color) in messages) {
						Text(message, style = typography.caption.copy(fontSize = 10.5.sp), color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
					}
				}
				if (texture.error != null) {
					Box(Modifier.padding(start = 6.dp).clickable { vm.clearTextureError() }) { IconClose(tint = colors.textMuted) }
				}
			}
		}

		// Bottom right: display toggles and the heat scale, like the edit canvas's view rail.
		Column(
			Modifier.align(Alignment.BottomEnd).padding(8.dp),
			horizontalAlignment = Alignment.End,
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			if (texture.heatmap) {
				Box(Modifier.width(170.dp).frostedGlass(RoundedCornerShape(6.dp), elevation = 2.dp, alpha = 0.82f)
					.padding(horizontal = 8.dp, vertical = 5.dp)) { HeatLegend() }
			}
			Row(
				Modifier.frostedGlass(RoundedCornerShape(6.dp), elevation = 2.dp, alpha = 0.82f).padding(3.dp),
				horizontalArrangement = Arrangement.spacedBy(3.dp),
			) {
				CompactToggleChip(tr("texture.atlas.heatmap"), texture.heatmap, { vm.setTextureHeatmap(!texture.heatmap) },
					leadingIcon = { IconHeatmap() }, showCheckWhenSelected = false)
				CompactToggleChip(tr("texture.atlas.outlines"), texture.showOutlines, { vm.setTextureOutlines(!texture.showOutlines) },
					leadingIcon = { IconTileOutlines(colors.textPrimary) }, showCheckWhenSelected = false)
			}
		}

		// Bottom left: what the pointer is over or the gesture is doing; otherwise the page at a glance.
		val status = statusText(snapshot, texture.dragDraft, densityDrag, hovered, page, zoom * fitScale)
		Text(
			status.first,
			style = typography.caption.copy(fontSize = 11.sp),
			color = if (status.second) colors.warning else colors.textPrimary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp, end = 200.dp)
				.frostedGlass(RoundedCornerShape(6.dp), elevation = 2.dp, alpha = 0.78f).padding(horizontal = 8.dp, vertical = 4.dp),
		)

		AtlasContextMenu(menu, { menu = null }, state, vm, snapshot, selection, busy, onFrame = ::frameSelection, onReset = ::resetView)
	}
}

/** A tile's four corners, each with the corner opposite it, in page pixels. */
private fun corners(tile: WorkspaceAtlasTile): List<Pair<Offset, Offset>> {
	val l = tile.x.toFloat(); val t = tile.y.toFloat(); val r = (tile.x + tile.width).toFloat(); val b = (tile.y + tile.height).toFloat()
	return listOf(Offset(l, t) to Offset(r, b), Offset(r, t) to Offset(l, b), Offset(r, b) to Offset(l, t), Offset(l, b) to Offset(r, t))
}

/** The status pill: the gesture, the tile under the pointer, or the page; true when it warns. */
private fun statusText(snapshot: TextureSnapshot, draft: io.github.psd2live.ui.state.TileDragDraft?, scaling: DensityDrag?,
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
			if (single != null) {
				val tile = single.tile
				if (tile?.pinned == true) {
					CompactMenuItem(tr("texture.inspector.unpin"), onClick = { run { vm.releaseTexturePin(snapshot, single.layerId) } }, enabled = !busy)
				} else {
					CompactMenuItem(tr("texture.menu.pin"), onClick = { run { vm.pinTextureTile(snapshot, single.layerId) } }, enabled = !busy && tile != null)
				}
				CompactMenuItem(tr("texture.inspector.replace"), onClick = { run {
					NativeFilePicker.chooseTransparentImages().firstOrNull()?.let { vm.replaceLayerImage(snapshot, single.layerId, it) }
				} }, enabled = !busy)
			}
			CompactMenuItem(tr("texture.menu.frame"), onClick = { run(onFrame) }, trailingText = frameKey)
		} else {
			CompactMenuSection(tr("texture.menu.page"))
			CompactMenuItem(tr("texture.atlas.packKeepPins"), onClick = { run { vm.packAtlas(snapshot, keepPins = true) } }, enabled = !busy)
			CompactMenuItem(tr("texture.atlas.packAll"), onClick = { run { vm.packAtlas(snapshot, keepPins = false) } },
				enabled = !busy && snapshot.atlas.tiles.any { it.pinned })
			CompactMenuDivider()
			CompactMenuItem(tr("texture.menu.selectAll"), onClick = { run { vm.selectLayers(snapshot.tiles(state.textureWorkspace.selectedPage
				.coerceIn(0, snapshot.atlas.pages.lastIndex)).map { it.layerId }) } }, trailingText = state.keymap.labelFor(ShortcutAction.SELECT_ALL))
			CompactMenuItem(tr("texture.menu.fitPage"), onClick = { run(onReset) }, trailingText = resetKey)
		}
	}
}

/** A checkerboard behind the page, so transparent texels read as empty; cells keep to the view's grid. */
private fun DrawScope.drawChecker(rect: Rect, light: Color, dark: Color) {
	val left = max(rect.left, 0f); val top = max(rect.top, 0f)
	val right = min(rect.right, size.width); val bottom = min(rect.bottom, size.height)
	if (right <= left || bottom <= top) return
	drawRect(dark, Offset(left, top), Size(right - left, bottom - top))
	val cell = 8.dp.toPx()
	var row = floor(top / cell).toInt()
	while (row * cell < bottom) {
		var column = floor(left / cell).toInt()
		if ((row + column) % 2 != 0) column++
		while (column * cell < right) {
			val x0 = max(column * cell, left); val y0 = max(row * cell, top)
			val x1 = min((column + 1) * cell, right); val y1 = min((row + 1) * cell, bottom)
			if (x1 > x0 && y1 > y0) drawRect(light, Offset(x0, y0), Size(x1 - x0, y1 - y0))
			column += 2
		}
		row++
	}
}

/**
 * The page's pixels as a Compose image, made off the UI thread: the preview model's page when it is this
 * version's atlas, otherwise the page PNG of the captured version.
 */
@Composable
private fun rememberPageImage(state: PSD2LiveState, snapshot: TextureSnapshot, page: Int): ImageBitmap? {
	val atlas = state.previewModel?.atlas
	val value by produceState<ImageBitmap?>(null, snapshot, atlas, page) {
		value = withContext(Dispatchers.Default) {
			runCatching {
				val live = atlas?.takeIf { snapshot.matches(it) }?.pages?.getOrNull(page)?.image
				(live ?: ImageIO.read(ByteArrayInputStream(snapshot.pagePng(page))))?.toImageBitmapFast()
			}.getOrNull()
		}
	}
	return value
}
