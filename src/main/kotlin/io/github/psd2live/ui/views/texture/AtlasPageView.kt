package io.github.psd2live.ui.views.texture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
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
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactTabBar
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.TextureDensity
import io.github.psd2live.ui.state.TextureSnapshot
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.toImageBitmapFast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
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

/**
 * The texture workspace's atlas page: page tabs, budget and packing controls, and the page itself with each
 * tile outlined and, with the heatmap on, tinted by the atlas pixels it spends per canvas unit. A click selects
 * the tile's layer (Shift adds); dragging a tile pins it where it is dropped, committed once on release.
 * The wheel zooms around the pointer; the middle or right button pans.
 */
@Composable
fun AtlasPageView(state: PSD2LiveState, vm: PSD2LiveViewModel, modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val snapshot = rememberTextureSnapshot(state, vm)
	val texture = state.textureWorkspace
	Column(modifier.fillMaxSize().background(colors.panelBackground)) {
		if (snapshot == null || snapshot.atlas.pages.isEmpty()) {
			Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
				Text(tr("texture.atlas.empty"), style = typography.body.copy(fontSize = 11.5.sp), color = colors.textMuted)
			}
			return@Column
		}
		val atlas = snapshot.atlas
		val page = texture.selectedPage.coerceIn(0, atlas.pages.lastIndex)
		val busy = texture.busy || state.isAnalyzing || state.isGenerating

		if (atlas.pages.size > 1) {
			CompactTabBar(
				tabs = atlas.pages.map { tr("texture.atlas.page", it.index + 1, it.width) },
				selectedIndex = page,
				onTabSelected = vm::setTexturePage,
				modifier = Modifier.fillMaxWidth(),
			)
		}
		// Overlays and packing.
		Row(
			Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			CompactToggleChip(tr("texture.atlas.heatmap"), texture.heatmap, { vm.setTextureHeatmap(!texture.heatmap) })
			CompactToggleChip(tr("texture.atlas.outlines"), texture.showOutlines, { vm.setTextureOutlines(!texture.showOutlines) })
			CompactButton(tr("texture.atlas.pack"), onClick = { vm.packAtlas(snapshot) }, enabled = !busy, height = 22.dp)
			CompactCheckbox(texture.keepPins, vm::setTextureKeepPins, label = tr("texture.atlas.keepPins"))
		}
		// Budget.
		Row(
			Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			val budget = atlas.budget
			Text(tr("texture.atlas.pageSize"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
			CompactDropdown(
				items = (ATLAS_PAGE_SIZES + budget.pageSize).distinct().sorted(),
				selectedItem = budget.pageSize,
				onItemSelected = { vm.setAtlasBudget(snapshot, pageSize = it) },
				itemLabel = { "$it px" },
				enabled = !busy,
				modifier = Modifier.width(92.dp),
				height = 22.dp,
			)
			Text(tr("texture.atlas.maxPages"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
			CommitNumberField(budget.maxPages.toDouble(), { vm.setAtlasBudget(snapshot, maxPages = it.toInt()) },
				Modifier.width(54.dp), min = 1.0, max = 64.0, enabled = !busy)
			Text(tr("texture.atlas.padding"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
			CommitNumberField(budget.padding.toDouble(), { vm.setAtlasBudget(snapshot, padding = it.toInt()) },
				Modifier.width(54.dp), min = 0.0, max = 32.0, unit = "px", enabled = !busy)
		}
		// Fit and occupancy.
		val pageInfo = atlas.pages[page]
		Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(
				tr("texture.atlas.fit", "%.0f".format(atlas.fit * 100f)),
				style = typography.caption.copy(fontSize = 11.sp),
				color = if (atlas.fit < 1f) colors.warning else colors.textPrimary,
			)
			Text(
				"  ·  " + tr("texture.atlas.occupancy", pageInfo.tileCount, "%.0f".format(pageInfo.occupancy * 100f), pageInfo.width, pageInfo.height),
				style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
			)
		}
		AtlasPageCanvas(state, vm, snapshot, page, Modifier.weight(1f).fillMaxWidth())
		Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
			if (texture.heatmap) HeatLegend(Modifier.fillMaxWidth())
			val messages = (texture.error?.let { listOf(it to colors.error) }.orEmpty() +
				(texture.notices.ifEmpty { atlas.notices }).map { it to colors.warning })
			for ((message, color) in messages.take(4)) {
				Text(message, style = typography.caption.copy(fontSize = 10.5.sp), color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
			}
		}
	}
}

/** Page-to-view mapping: a texture pixel (x, y) shows at (left + x * scale, top + y * scale). */
private data class PageTransform(val left: Float, val top: Float, val scale: Float) {
	fun toView(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)
	fun toPage(point: Offset) = Offset((point.x - left) / scale, (point.y - top) / scale)
	fun rect(x: Int, y: Int, width: Int, height: Int) = Rect(toView(x.toFloat(), y.toFloat()), Size(width * scale, height * scale))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AtlasPageCanvas(state: PSD2LiveState, vm: PSD2LiveViewModel, snapshot: TextureSnapshot, page: Int, modifier: Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val pageInfo = snapshot.atlas.pages[page]
	val tiles = remember(snapshot, page) { snapshot.tiles(page) }
	val image = rememberPageImage(state, snapshot, page)
	val texture = state.textureWorkspace
	val selected = remember(state.selectedLayerIds, state.selectedLayerId, snapshot) {
		(state.selectedLayerIds + setOfNotNull(state.selectedLayerId)).mapNotNull { snapshot.textureLayerId(it, state.previewModel) }.toSet()
	}
	var zoom by remember(page) { mutableStateOf(1f) }
	var pan by remember(page) { mutableStateOf(Offset.Zero) }
	var hovered by remember(snapshot, page) { mutableStateOf<String?>(null) }
	var viewSize by remember { mutableStateOf(IntSize.Zero) }
	val padding = 8f
	val fitScale = if (viewSize.width <= 0) 1f else min((viewSize.width - padding * 2) / pageInfo.width, (viewSize.height - padding * 2) / pageInfo.height).coerceAtLeast(1e-4f)
	val scale = fitScale * zoom
	val transform = PageTransform((viewSize.width - pageInfo.width * scale) / 2f + pan.x, (viewSize.height - pageInfo.height * scale) / 2f + pan.y, scale)
	val currentTransform by rememberUpdatedState(transform)
	val currentSnapshot by rememberUpdatedState(snapshot)
	val currentTiles by rememberUpdatedState(tiles)
	val busy = texture.busy || state.isAnalyzing || state.isGenerating
	val currentBusy by rememberUpdatedState(busy)

	fun hit(point: Offset): WorkspaceAtlasTile? {
		val at = currentTransform.toPage(point)
		// Smaller tiles first: a tile nested in a larger one's padding stays reachable.
		return currentTiles.filter { at.x >= it.x && at.y >= it.y && at.x < it.x + it.width && at.y < it.y + it.height }
			.minByOrNull { it.width.toLong() * it.height }
	}

	Box(
		modifier
			.background(colors.windowBackground)
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				val delta = change.scrollDelta.y
				if (delta == 0f) return@onPointerEvent
				val before = currentTransform.toPage(change.position)
				val next = (zoom * if (delta < 0f) 1.2f else 1f / 1.2f).coerceIn(0.25f, 64f)
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
			.pointerInput(page) {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					val event = currentEvent
					val panning = event.button == PointerButton.Secondary || event.button == PointerButton.Tertiary
					if (panning) {
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
					val tile = hit(down.position)
					val shift = event.keyboardModifiers.isShiftPressed
					if (tile == null) {
						if (!shift) vm.selectLayer(null)
						return@awaitEachGesture
					}
					vm.selectLayer(tile.layerId, additive = shift)
					val snapshotAtStart = currentSnapshot
					val start = currentTransform.toPage(down.position)
					var dragging = false
					while (true) {
						val move = awaitPointerEvent()
						val change = move.changes.firstOrNull { it.id == down.id } ?: break
						if (!change.pressed || !move.buttons.isPrimaryPressed) break
						val distance = (change.position - down.position).getDistance()
						if (!dragging && (distance < viewConfiguration.touchSlop || currentBusy)) continue
						dragging = true
						val at = currentTransform.toPage(change.position)
						vm.dragTextureTile(snapshotAtStart, tile.layerId, tile.x + at.x - start.x, tile.y + at.y - start.y,
							snap = 6f / currentTransform.scale)
						change.consume()
					}
					if (dragging) vm.endTextureTileDrag(snapshotAtStart) else vm.cancelTextureTileDrag()
				}
			},
	) {
		Canvas(Modifier.fillMaxSize()) {
			if (viewSize != IntSize(size.width.toInt(), size.height.toInt())) viewSize = IntSize(size.width.toInt(), size.height.toInt())
			val pageRect = transform.rect(0, 0, pageInfo.width, pageInfo.height)
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
				drawRect(colors.border, pageRect.topLeft, pageRect.size, style = Stroke(1f))
				val draft = texture.dragDraft?.takeIf { it.page == page }
				for (tile in tiles) {
					val rect = transform.rect(tile.x, tile.y, tile.width, tile.height)
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
							CornerRadius(badge / 4f))
					}
					if (draft?.layerId == tile.layerId) {
						drawRect(colors.textMuted.copy(alpha = 0.35f), rect.topLeft, rect.size)
					}
				}
				if (draft != null) {
					val tile = snapshot.tilesByLayer[draft.layerId]
					if (tile != null) {
						val rect = transform.rect(draft.x, draft.y, tile.width, tile.height)
						val color = if (draft.collides) colors.error else colors.accent
						drawRect(color.copy(alpha = 0.22f), rect.topLeft, rect.size)
						drawRect(color, rect.topLeft, rect.size, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
					}
				}
			}
		}
		// What the pointer is over, or the dragged tile's position.
		val statusTile = texture.dragDraft?.layerId ?: hovered
		val info = statusTile?.let { id -> snapshot.tilesByLayer[id]?.let { tile ->
			val name = snapshot.layer(id)?.name ?: id
			val draft = texture.dragDraft?.takeIf { it.layerId == id }
			val at = if (draft != null) "(${draft.x}, ${draft.y})" else "(${tile.x}, ${tile.y})"
			tr("texture.atlas.tileInfo", name, tile.width, tile.height, at, TextureDensity.format(snapshot.texelsPerCanvasUnit(tile)))
		} }
		if (info != null) {
			Text(
				info,
				style = typography.caption.copy(fontSize = 10.5.sp),
				color = colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.align(Alignment.BottomStart).background(colors.panelElevated.copy(alpha = 0.9f)).padding(horizontal = 6.dp, vertical = 2.dp),
			)
		}
	}
}

/** A checkerboard behind the page, so transparent texels read as empty. */
private fun DrawScope.drawChecker(rect: Rect, light: Color, dark: Color) {
	drawRect(dark, rect.topLeft, rect.size)
	val cell = 8.dp.toPx()
	val left = max(rect.left, 0f); val top = max(rect.top, 0f)
	val right = min(rect.right, size.width); val bottom = min(rect.bottom, size.height)
	var y = top
	var row = 0
	while (y < bottom) {
		var x = left + if (row % 2 == 0) 0f else cell
		while (x < right) {
			drawRect(light, Offset(x, y), Size(min(cell, right - x), min(cell, bottom - y)))
			x += cell * 2
		}
		y += cell; row++
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
