package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.AppMenuHeader
import io.github.psd2live.ui.components.AppMenuItem
import io.github.psd2live.ui.components.AppMenuSeparator
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.ICON_FINE
import io.github.psd2live.ui.components.IconPen
import io.github.psd2live.ui.theme.LocalToolColors

/** The hierarchy toolbar's sort button: a menu of orders and a reverse switch. */
@Composable
internal fun HierarchySortMenu(sort: HierarchySort, onChange: (HierarchySort) -> Unit) {
	val colors = LocalToolColors.current
	var open by remember { mutableStateOf(false) }
	val label = buildString {
		append(tr("canvas.hierarchy.sort")).append(": ").append(tr(sort.mode.labelKey))
		if (sort.reversed) append(" · ").append(tr("canvas.hierarchy.sort.reversed"))
	}
	Box(contentAlignment = Alignment.Center) {
		PanelToolButton(
			label = tr("canvas.hierarchy.sort"),
			showLabel = false,
			onClick = { open = !open },
			enabled = true,
			active = open,
			tooltip = label,
		) {
			IconHierarchySort(sort, tint = if (sort.isDefault) colors.textMuted else colors.accent)
		}
		if (open) {
			Popup(
				alignment = Alignment.BottomEnd,
				offset = IntOffset(0, 4),
				onDismissRequest = { open = false },
				properties = PopupProperties(focusable = true),
			) {
				Surface(
					color = colors.panelElevated,
					border = BorderStroke(1.dp, colors.border),
					shape = RoundedCornerShape(3.dp),
					elevation = 8.dp,
				) {
					Column(modifier = Modifier.widthIn(min = 180.dp, max = 260.dp)) {
						AppMenuHeader(tr("canvas.hierarchy.sort"))
						for (mode in HierarchySortMode.entries) {
							AppMenuItem(
								text = tr(mode.labelKey),
								icon = { tint -> IconSortMode(mode, tint) },
								isChecked = mode == sort.mode,
								onClick = {
									onChange(sort.copy(mode = mode))
									open = false
								},
							)
						}
						AppMenuSeparator()
						AppMenuItem(
							text = tr("canvas.hierarchy.sort.reversed"),
							icon = { tint -> IconSortReverse(tint) },
							isChecked = sort.reversed,
							onClick = { onChange(sort.copy(reversed = !sort.reversed)) },
						)
						AppMenuHeader(tr("canvas.hierarchy.sort.note"))
					}
				}
			}
		}
	}
}

/** Bars shortening downward beside an arrow along the order, pointing back up when reversed. */
@Composable
private fun IconHierarchySort(sort: HierarchySort, tint: Color) {
	GridIcon(Modifier.size(13.dp), tint) {
		line(2.5f, 4f, 10f, 4f)
		line(2.5f, 9f, 8f, 9f)
		line(2.5f, 14f, 6f, 14f)
		orderArrow(13.5f, 3f, 13.5f, 15f, sort.reversed)
	}
}

/** Each order's sign: where it starts and which way it runs. */
@Composable
private fun IconSortMode(mode: HierarchySortMode, tint: Color) {
	GridIcon(Modifier.size(13.dp), tint) {
		when (mode) {
			HierarchySortMode.MODEL -> {
				for (y in floatArrayOf(4f, 9f, 14f)) {
					dot(3.5f, y, 1.2f)
					line(6.5f, y, 15f, y)
				}
			}
			HierarchySortMode.HEIGHT -> {
				// A head over a body, the arrow running from top to bottom.
				ring(5.5f, 4.5f, 2f)
				line(5.5f, 7f, 5.5f, 12f)
				line(5.5f, 12f, 3.5f, 15.5f, ICON_FINE)
				line(5.5f, 12f, 7.5f, 15.5f, ICON_FINE)
				line(3f, 9f, 8f, 9f, ICON_FINE)
				orderArrow(13f, 2.5f, 13f, 15.5f, false)
			}
			HierarchySortMode.HORIZONTAL -> {
				line(2.5f, 3f, 2.5f, 15f, ICON_FINE)
				for (x in floatArrayOf(5.5f, 9.5f)) panel(x, 5f, 2.6f, 3.6f, 0.6f)
				orderArrow(4f, 12.5f, 15.5f, 12.5f, false)
			}
			HierarchySortMode.DRAW_ORDER -> {
				panel(6f, 2.5f, 9.5f, 7.5f)
				panel(2.5f, 7.5f, 9.5f, 7.5f)
			}
			HierarchySortMode.NAME -> {
				// An A over a Z, the arrow running from one to the other.
				line(2.5f, 8f, 5f, 2f)
				line(5f, 2f, 7.5f, 8f)
				line(3.5f, 6f, 6.5f, 6f, ICON_FINE)
				line(2.5f, 10.5f, 7.5f, 10.5f)
				line(7.5f, 10.5f, 2.5f, 15.5f)
				line(2.5f, 15.5f, 7.5f, 15.5f)
				orderArrow(13f, 2.5f, 13f, 15.5f, false)
			}
		}
	}
}

@Composable
private fun IconSortReverse(tint: Color) {
	GridIcon(Modifier.size(13.dp), tint) {
		orderArrow(5.5f, 2.5f, 5.5f, 15.5f, false)
		orderArrow(12.5f, 2.5f, 12.5f, 15.5f, true)
	}
}

/** A shaft from ([x1], [y1]) to ([x2], [y2]) with its head at the far end, or at the near end when [back]. */
private fun IconPen.orderArrow(x1: Float, y1: Float, x2: Float, y2: Float, back: Boolean) {
	line(x1, y1, x2, y2)
	if (back) chevron(x1, y1, x1 - x2, y1 - y2) else chevron(x2, y2, x2 - x1, y2 - y1)
}
