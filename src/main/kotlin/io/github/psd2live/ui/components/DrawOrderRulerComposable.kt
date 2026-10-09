package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.i18n.tr
import io.github.psd2live.core.ComponentPalette
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import io.github.psd2live.ui.views.rememberMeshThumbnail
import org.umamo.runtime.model.Drawable
import java.awt.Cursor
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class DrawableOrderEntry(
	val drawable: Drawable,
	val layerId: String,
	val effectiveOrder: Float,
	val defaultOrder: Float,
	val isOverridden: Boolean,
	val isSelected: Boolean,
	val y: Float,
)

/**
 * Vertical Draw Order Ruler replicating Live2D Cubism Editor's vertical draw order slider.
 * - Auto-scales height to fit active layer orders instead of statically fixing 0..1000.
 * - Mouse Wheel: Zooms ruler scale in/out centered at mouse cursor with adaptive dynamic ticks.
 * - Left Mouse Drag: Adjusts selected layer's draw order value in real-time; the selected mark wins over marks it overlaps.
 * - Right Mouse Drag: Pans the ruler up/down along the scale.
 * - Right Click (or Double Click): Opens precision numeric input dialog.
 * - Hover Card: Shows the mesh of every mark under the pointer with its name and draw order.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DrawOrderRuler(
	model: RigPreviewModel?,
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
	width: androidx.compose.ui.unit.Dp = state.drawOrderRulerWidth.dp,
	onRequestSetOrder: ((targetId: String, name: String, currentOrder: Float, defaultOrder: Float, isOverridden: Boolean) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val textMeasurer = rememberTextMeasurer()

	var rulerHeightPx by remember { mutableStateOf(1f) }
	var rulerWidthPx by remember { mutableStateOf(1f) }
	var mousePos by remember { mutableStateOf<Offset?>(null) }
	var lastMousePos by remember { mutableStateOf<Offset?>(null) }

	var isLeftDragging by remember { mutableStateOf(false) }
	var isRightDragging by remember { mutableStateOf(false) }
	var rightPressPos by remember { mutableStateOf<Offset?>(null) }
	var activeDragTargetId by remember { mutableStateOf<String?>(null) }
	var lastClickTime by remember { mutableStateOf(0L) }

	val padTop = 18f
	val padBottom = 18f

	val drawables = model?.rig?.puppet?.drawables.orEmpty()

	// Compute initial framing based on layer orders
	fun computeFittedRange(): Pair<Float, Float> {
		if (model == null || drawables.isEmpty()) return 500f to 1000f
		val orders = drawables.map { drawable ->
			val layerId = model.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw
			state.getEffectiveDrawOrder(drawable.id.raw, layerId, drawable.drawOrder)
		}
		val minO = orders.minOrNull() ?: 0f
		val maxO = orders.maxOrNull() ?: 1000f
		val span = (maxO - minO).coerceAtLeast(10f)
		val center = (minO + maxO) / 2f
		val paddedSpan = (span * 1.35f).coerceIn(15f, 1500f)
		return center to paddedSpan
	}

	val initialFit = remember(model) { computeFittedRange() }
	var viewCenter by remember(model) { mutableStateOf(initialFit.first) }
	var viewSpan by remember(model) { mutableStateOf(initialFit.second) }

	val visibleMin = viewCenter - viewSpan / 2f
	val visibleMax = viewCenter + viewSpan / 2f

	fun orderToY(order: Float, h: Float): Float {
		val usable = (h - padTop - padBottom).coerceAtLeast(1f)
		val frac = (order - visibleMin) / viewSpan.coerceAtLeast(0.01f)
		return h - padBottom - frac * usable
	}

	fun yToOrder(y: Float, h: Float): Float {
		val usable = (h - padTop - padBottom).coerceAtLeast(1f)
		val frac = (h - padBottom - y) / usable
		return visibleMin + frac * viewSpan
	}

	// Compute order entries with dynamically mapped y coordinates
	val entries = remember(drawables, state.drawOrderOverrides, state.selectedLayerId, rulerHeightPx, visibleMin, visibleMax) {
		drawables.mapNotNull { drawable ->
			val layerId = model?.rig?.layerIdByDrawableId?.get(drawable.id.raw) ?: drawable.id.raw
			val effective = state.getEffectiveDrawOrder(drawable.id.raw, layerId, drawable.drawOrder)
			val isOverridden = state.drawOrderOverrides.containsKey(layerId) || state.drawOrderOverrides.containsKey(drawable.id.raw)
			val isSelected = state.selectedLayerId != null && (state.selectedLayerId == layerId || state.selectedLayerId == drawable.id.raw)
			val y = orderToY(effective, rulerHeightPx)
			DrawableOrderEntry(
				drawable = drawable,
				layerId = layerId,
				effectiveOrder = effective,
				defaultOrder = drawable.drawOrder,
				isOverridden = isOverridden,
				isSelected = isSelected,
				y = y,
			)
		}
	}

	val selectedEntry = entries.firstOrNull { it.isSelected }

	// The selected mark spans the whole ruler; elsewhere only the short marks by the track can be hit.
	// A selected mark that overlaps another wins, so a drag on it moves the selection, not its neighbour.
	fun pickEntry(pos: Offset, threshold: Float): DrawableOrderEntry? {
		val near = entries.filter { abs(it.y - pos.y) <= threshold }
		val closest = near.minByOrNull { abs(it.y - pos.y) } ?: return null
		val selected = near.firstOrNull { it.isSelected } ?: return closest
		val marksLeft = rulerTrackX(rulerWidthPx) - rulerMarkLength(rulerWidthPx)
		val onSelected = pos.x < marksLeft || abs(selected.y - pos.y) <= abs(closest.y - pos.y) + OVERLAP_PX
		return if (onSelected) selected else closest
	}

	val hoveredEntry = remember(mousePos, entries, rulerWidthPx) {
		mousePos?.let { pickEntry(it, 8f) }
	}
	// Every mark drawn over the hovered one, the hovered one first.
	val hoveredGroup = remember(hoveredEntry, entries) {
		val hit = hoveredEntry ?: return@remember emptyList()
		listOf(hit) + entries.filter { it !== hit && abs(it.y - hit.y) <= OVERLAP_PX }.sortedByDescending { it.effectiveOrder }
	}

	Box(
		modifier = modifier
			.tutorialTarget(TutorialTargetId.DRAW_ORDER_RULER)
			.width(width)
			.fillMaxHeight()
			.background(colors.panelElevated.copy(alpha = 0.5f))
			.border(BorderStroke(1.dp, colors.divider))
			.onGloballyPositioned {
				rulerHeightPx = it.size.height.toFloat().coerceAtLeast(1f)
				rulerWidthPx = it.size.width.toFloat().coerceAtLeast(1f)
			}
			.pointerHoverIcon(
				PointerIcon(
					when {
						isRightDragging -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
						isLeftDragging -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
						hoveredEntry != null -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
						else -> Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
					}
				)
			)
			// Mouse Wheel: Zoom scale in/out centered at mouse cursor
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				val scrollDelta = change.scrollDelta.y
				if (scrollDelta == 0f) return@onPointerEvent

				val mouseOrder = yToOrder(change.position.y, rulerHeightPx)
				val zoomFactor = if (scrollDelta < 0f) 0.85f else 1.18f
				val newSpan = (viewSpan * zoomFactor).coerceIn(4f, 5000f)
				val actualFactor = newSpan / viewSpan
				viewSpan = newSpan
				viewCenter = mouseOrder + (viewCenter - mouseOrder) * actualFactor
			}
			// Pointer Press: Left (select/drag value or double-click dialog) or Right (pan start)
			.onPointerEvent(PointerEventType.Press) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				val pos = change.position
				lastMousePos = pos

				if (event.button == PointerButton.Primary) {
					val now = System.currentTimeMillis()
					val isDoubleClick = (now - lastClickTime) < 350L
					lastClickTime = now

					val hit = pickEntry(pos, 8f)
					val targetEntry = hit ?: selectedEntry

					if (isDoubleClick) {
						if (targetEntry != null) {
							onRequestSetOrder?.invoke(
								targetEntry.layerId,
								targetEntry.drawable.name,
								targetEntry.effectiveOrder,
								targetEntry.defaultOrder,
								targetEntry.isOverridden,
							)
						} else {
							// Double click on empty track area: Reset/fit to all drawables
							val (c, s) = computeFittedRange()
							viewCenter = c
							viewSpan = s
						}
						return@onPointerEvent
					}

					if (hit != null && hit.layerId != state.selectedLayerId) {
						viewModel.selectLayer(hit.layerId)
					}

					val targetId = hit?.layerId ?: state.selectedLayerId
					if (targetId != null) {
						isLeftDragging = true
						activeDragTargetId = targetId
						viewModel.beginEditorField("layer.draw_order.ruler")
						val newOrder = yToOrder(pos.y, rulerHeightPx).roundToInt().toFloat().coerceIn(0f, 1000f)
						viewModel.setLayerDrawOrder(targetId, newOrder)
					}
				} else if (event.button == PointerButton.Secondary) {
					isRightDragging = true
					rightPressPos = pos
				}
			}
			// Pointer Move: Left Drag (adjust order) or Right Drag (pan ruler)
			.onPointerEvent(PointerEventType.Move) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				val pos = change.position
				val prev = lastMousePos ?: pos
				lastMousePos = pos
				mousePos = pos

				if (isLeftDragging && activeDragTargetId != null) {
					val newOrder = yToOrder(pos.y, rulerHeightPx).roundToInt().toFloat().coerceIn(0f, 1000f)
					viewModel.setLayerDrawOrder(activeDragTargetId!!, newOrder)
				} else if (isRightDragging) {
					val deltaY = pos.y - prev.y
					val usable = (rulerHeightPx - padTop - padBottom).coerceAtLeast(1f)
					val deltaOrder = (deltaY / usable) * viewSpan
					viewCenter += deltaOrder
				}
			}
			// Pointer Release: End Left Drag, or End Right Drag (if moved < 4px, trigger dialog)
			.onPointerEvent(PointerEventType.Release) { event ->
				val change = event.changes.firstOrNull()
				val pos = change?.position ?: lastMousePos ?: Offset.Zero

				if (event.button == PointerButton.Primary) {
					isLeftDragging = false
					activeDragTargetId = null
					viewModel.endEditorField("layer.draw_order.ruler")
				} else if (event.button == PointerButton.Secondary) {
					val press = rightPressPos
					isRightDragging = false
					rightPressPos = null
					if (press != null && (pos - press).getDistance() < 5f) {
						val target = pickEntry(pos, 12f) ?: selectedEntry
						if (target != null) {
							onRequestSetOrder?.invoke(
								target.layerId,
								target.drawable.name,
								target.effectiveOrder,
								target.defaultOrder,
								target.isOverridden,
							)
						}
					}
				}
			}
			.onPointerEvent(PointerEventType.Exit) {
				if (!isLeftDragging && !isRightDragging) {
					mousePos = null
				}
			}
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			val w = size.width
			val h = size.height

			val trackX = rulerTrackX(w)
			val usable = (h - padTop - padBottom).coerceAtLeast(1f)

			// Vertical track guide line
			drawLine(
				color = colors.divider,
				start = Offset(trackX, padTop),
				end = Offset(trackX, h - padBottom),
				strokeWidth = 1f,
			)

			// Compute dynamic adaptive tick interval based on pixel height
			val pixelsPerUnit = usable / viewSpan.coerceAtLeast(0.01f)
			val idealUnitStep = (46f / pixelsPerUnit).coerceAtLeast(0.01f)

			fun computeNiceStep(raw: Float): Float {
				val exponent = kotlin.math.floor(kotlin.math.log10(raw.toDouble())).toInt()
				val base = 10.0.pow(exponent.toDouble()).toFloat()
				val fraction = raw / base
				val niceFraction = when {
					fraction <= 1.2f -> 1f
					fraction <= 2.5f -> 2f
					fraction <= 6.0f -> 5f
					else -> 10f
				}
				return niceFraction * base
			}

			val majorStep = computeNiceStep(idealUnitStep).coerceAtLeast(1f)
			val minorStep = (majorStep / 2f).coerceAtLeast(0.5f)

			val firstMinor = kotlin.math.floor(visibleMin / minorStep).toInt()
			val lastMinor = kotlin.math.ceil(visibleMax / minorStep).toInt()

			// Draw adaptive ticks and labels
			for (i in firstMinor..lastMinor) {
				val order = i * minorStep
				val y = orderToY(order, h)
				if (y < padTop - 2f || y > h - padBottom + 2f) continue

				val isMajor = abs(order % majorStep) < (minorStep * 0.1f) || abs(order % majorStep - majorStep) < (minorStep * 0.1f)

				if (isMajor) {
					val tickLen = minOf(5f, w * 0.25f)
					drawLine(
						color = colors.borderHover.copy(alpha = 0.6f),
						start = Offset((trackX - tickLen).coerceAtLeast(0f), y),
						end = Offset(trackX, y),
						strokeWidth = 1f,
					)

					if (w >= 30f) {
						val label = if (majorStep >= 1f) order.roundToInt().toString() else "%.1f".format(order)
						val textResult = textMeasurer.measure(
							text = label,
							style = TextStyle(
								fontSize = 7.5.sp,
								fontFamily = FontFamily.Monospace,
								fontWeight = FontWeight.Normal,
								color = colors.textMuted.copy(alpha = 0.55f),
							)
						)
						val textX = trackX - 6f - textResult.size.width
						if (textX >= 1f) {
							drawText(
								textLayoutResult = textResult,
								topLeft = Offset(textX, y - textResult.size.height * 0.5f),
							)
						}
					}
				} else {
					val tickLen = minOf(2.5f, w * 0.15f)
					drawLine(
						color = colors.border.copy(alpha = 0.35f),
						start = Offset((trackX - tickLen).coerceAtLeast(0f), y),
						end = Offset(trackX, y),
						strokeWidth = 1f,
					)
				}
			}

			// Draw all layers as colored horizontal tick marks
			for (entry in entries) {
				if (entry.isSelected) continue // Selected layer drawn on top
				val y = entry.y
				if (y < padTop - 2f || y > h - padBottom + 2f) continue

				val awtColor = ComponentPalette.strong(entry.layerId)
				val markColor = Color(awtColor.red, awtColor.green, awtColor.blue)

				val markLen = rulerMarkLength(w)
				drawLine(
					color = markColor.copy(alpha = 0.85f),
					start = Offset((trackX - markLen).coerceAtLeast(0f), y),
					end = Offset(minOf(w, trackX + 2f), y),
					strokeWidth = 1.6f,
				)
			}

			// Draw selected layer indicator
			if (selectedEntry != null) {
				val selY = selectedEntry.y
				val isWithinViewport = selY in (padTop - 2f)..(h - padBottom + 2f)

				if (isWithinViewport) {
					// Highlight line across full ruler width
					drawLine(
						color = colors.accent,
						start = Offset(1f, selY),
						end = Offset(w - 1f, selY),
						strokeWidth = 2.2f,
					)

					// Pointer thumb at right edge
					val thumbW = minOf(4f, w * 0.22f)
					val pointerPath = Path().apply {
						moveTo(w, selY)
						lineTo(w - thumbW, selY - 3.5f)
						lineTo(w - thumbW, selY + 3.5f)
						close()
					}
					drawPath(pointerPath, color = colors.accent)

					// Indicator dot at left edge if wide enough
					if (w >= 20f) {
						drawCircle(
							color = colors.accent,
							radius = 2f,
							center = Offset(3f, selY),
						)
					}
				} else {
					// Off-screen indicator arrows
					val isAbove = selectedEntry.effectiveOrder > visibleMax
					val arrowY = if (isAbove) padTop + 2f else h - padBottom - 2f
					val arrowDir = if (isAbove) -1f else 1f

					val arrowW = minOf(4f, trackX)
					val offscreenArrow = Path().apply {
						moveTo(trackX, arrowY)
						lineTo((trackX - arrowW).coerceAtLeast(0f), arrowY - 3.5f * arrowDir)
						lineTo(minOf(w, trackX + 1f), arrowY - 3.5f * arrowDir)
						close()
					}
					drawPath(offscreenArrow, color = colors.accent.copy(alpha = 0.85f))
				}
			}
		}

		// Hover card: the mesh of every mark under the pointer
		val currentMouse = mousePos
		if (model != null && hoveredGroup.isNotEmpty() && currentMouse != null && !isLeftDragging && !isRightDragging) {
			Popup(
				popupPositionProvider = object : PopupPositionProvider {
					override fun calculatePosition(
						anchorBounds: IntRect,
						windowSize: IntSize,
						layoutDirection: LayoutDirection,
						popupContentSize: IntSize,
					): IntOffset {
						val right = anchorBounds.right + 6
						val x = if (right + popupContentSize.width <= windowSize.width) right
							else (anchorBounds.left - 6 - popupContentSize.width).coerceAtLeast(0)
						val y = (anchorBounds.top + currentMouse.y - popupContentSize.height / 2f).roundToInt()
							.coerceIn(8, (windowSize.height - popupContentSize.height - 8).coerceAtLeast(8))
						return IntOffset(x, y)
					}
				},
				properties = PopupProperties(focusable = false),
			) {
				DrawOrderHoverCard(model, hoveredGroup)
			}
		}
	}
}

private const val OVERLAP_PX = 2f
private const val HOVER_CARD_MAX_CELLS = 16

private fun rulerTrackX(width: Float): Float = (width - 4f).coerceAtLeast(3f)

private fun rulerMarkLength(width: Float): Float = (width * 0.45f).coerceIn(4f, 12f)

@Composable
private fun DrawOrderHoverCard(model: RigPreviewModel, group: List<DrawableOrderEntry>) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val shown = group.take(HOVER_CARD_MAX_CELLS)
	val columns = when {
		shown.size == 1 -> 1
		shown.size <= 4 -> 2
		shown.size <= 9 -> 3
		else -> 4
	}
	val cell = when (columns) {
		1 -> 150.dp
		2 -> 84.dp
		else -> 64.dp
	}
	val minOrder = group.minOf { it.effectiveOrder }.roundToInt()
	val maxOrder = group.maxOf { it.effectiveOrder }.roundToInt()
	Column(
		modifier = Modifier
			.background(colors.panelElevated, RoundedCornerShape(3.dp))
			.border(BorderStroke(1.dp, colors.borderHover), RoundedCornerShape(3.dp))
			.padding(6.dp),
		verticalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Text(
			text = buildString {
				append(tr("canvas.drawOrder.title"))
				append(": ")
				append(if (minOrder == maxOrder) "$minOrder" else "$minOrder – $maxOrder")
				if (group.size > 1) append(" · ").append(group.size)
			},
			style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
		)
		val showOrder = minOrder != maxOrder || group.any { it.isOverridden }
		shown.chunked(columns).forEach { row ->
			Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
				row.forEach { entry -> DrawOrderHoverCell(model, entry, cell, showOrder) }
			}
		}
		if (group.size > shown.size) {
			Text(
				text = "+${group.size - shown.size}",
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.textMuted,
			)
		}
	}
}

@Composable
private fun DrawOrderHoverCell(model: RigPreviewModel, entry: DrawableOrderEntry, side: Dp, showOrder: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val thumbnail = rememberMeshThumbnail(model, entry.drawable)
	val awtColor = ComponentPalette.strong(entry.layerId)
	val swatch = Color(awtColor.red, awtColor.green, awtColor.blue)
	Column(modifier = Modifier.width(side), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Canvas(
			modifier = Modifier
				.size(side)
				.background(colors.checkerDark, RoundedCornerShape(2.dp))
				.border(
					BorderStroke(if (entry.isSelected) 1.5.dp else 1.dp, if (entry.isSelected) colors.accent else colors.border),
					RoundedCornerShape(2.dp),
				),
		) {
			val image = thumbnail ?: return@Canvas
			val inset = 4.dp.toPx()
			val scale = (size.minDimension - inset * 2).coerceAtLeast(1f) / maxOf(image.width, image.height)
			val width = (image.width * scale).roundToInt().coerceAtLeast(1)
			val height = (image.height * scale).roundToInt().coerceAtLeast(1)
			drawImage(
				image,
				dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
				dstSize = IntSize(width, height),
			)
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
			Box(Modifier.size(6.dp).background(swatch, RoundedCornerShape(1.dp)))
			Text(
				text = entry.drawable.name,
				style = typography.caption.copy(fontSize = 9.sp, fontWeight = if (entry.isSelected) FontWeight.SemiBold else FontWeight.Normal),
				color = if (entry.isSelected) colors.accent else colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (showOrder) {
			Text(
				text = buildString {
					append(entry.effectiveOrder.roundToInt())
					if (entry.isOverridden) append(" · ").append(tr("canvas.drawOrder.default", entry.defaultOrder.roundToInt()))
				},
				style = typography.caption.copy(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
				color = colors.textMuted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/**
 * Modal Dialog for precise numeric input of Draw Order (0..1000).
 */
@Composable
fun DrawOrderInputDialog(
	targetId: String,
	targetName: String,
	initialOrder: Float,
	defaultOrder: Float,
	isOverridden: Boolean,
	onConfirm: (Float) -> Unit,
	onReset: () -> Unit,
	onDismiss: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	var textValue by remember(initialOrder) { mutableStateOf(initialOrder.roundToInt().toString()) }
	val currentFloat = textValue.toFloatOrNull() ?: initialOrder
	val isValid = currentFloat in 0f..1000f

	val confirm = {
		if (isValid) {
			onConfirm(currentFloat)
			onDismiss()
		}
	}
	val awtColor = ComponentPalette.strong(targetId)
	ModalDialogFrame(
		title = tr("canvas.drawOrder.title"),
		subtitle = targetName,
		onDismiss = onDismiss,
		width = 340.dp,
		onConfirm = confirm,
		titleTrailing = {
			Box(Modifier.size(8.dp).background(Color(awtColor.red, awtColor.green, awtColor.blue), RoundedCornerShape(2.dp)))
		},
		footer = {
			CompactButton(text = tr("project.cancel"), onClick = onDismiss)
			CompactButton(text = tr("canvas.drawOrder.title"), onClick = confirm, enabled = isValid, isPrimary = true)
		},
	) {
		Text(
			text = tr("canvas.drawOrder.inputPrompt") + " · " + tr("canvas.drawOrder.default", defaultOrder.roundToInt()),
			style = typography.caption.copy(fontSize = 10.sp),
			color = colors.textMuted,
		)
		// Exact Numeric Input Field
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			CompactTextField(
				value = textValue,
				onValueChange = { input ->
					val filtered = input.filter { it.isDigit() || it == '.' }
					textValue = filtered
				},
				placeholder = "0..1000",
				isMono = true,
				modifier = Modifier.weight(1f),
				height = 28.dp,
			)

			if (isOverridden) {
				CompactButton(
					text = tr("canvas.drawOrder.reset"),
					onClick = {
						onReset()
						onDismiss()
					},
					height = 28.dp,
				)
			}
		}

		// Quick adjustment step buttons
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			fun adjust(delta: Int) {
				val curr = textValue.toIntOrNull() ?: initialOrder.roundToInt()
				val updated = (curr + delta).coerceIn(0, 1000)
				textValue = updated.toString()
			}

			CompactButton(text = "-100", onClick = { adjust(-100) }, modifier = Modifier.weight(1f), height = 22.dp)
			CompactButton(text = "-10", onClick = { adjust(-10) }, modifier = Modifier.weight(1f), height = 22.dp)
			CompactButton(text = "-1", onClick = { adjust(-1) }, modifier = Modifier.weight(1f), height = 22.dp)
			CompactButton(text = "+1", onClick = { adjust(1) }, modifier = Modifier.weight(1f), height = 22.dp)
			CompactButton(text = "+10", onClick = { adjust(10) }, modifier = Modifier.weight(1f), height = 22.dp)
			CompactButton(text = "+100", onClick = { adjust(100) }, modifier = Modifier.weight(1f), height = 22.dp)
		}
	}
}
