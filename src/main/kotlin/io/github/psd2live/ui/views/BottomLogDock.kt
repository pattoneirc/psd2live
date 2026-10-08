package io.github.psd2live.ui.views

import io.github.psd2live.ui.utils.toImageBitmapFast
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.unit.Dp
import io.github.psd2live.ui.components.ICON_FINE
import io.github.psd2live.ui.state.AppSettings
import kotlinx.coroutines.flow.drop
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.AppMenuItem
import io.github.psd2live.ui.components.CheckerboardBackground
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.copySheets
import io.github.psd2live.ui.components.trashCan
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private enum class LogFilter {
	ALL,
	SYSTEM,
	EDITOR,
	AGENT_MCP,
	IMAGES_ONLY,
}

/** The thresholds the level menu offers, lowest first; SUCCESS shows with INFO. */
private val LEVEL_THRESHOLDS = listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARNING, LogLevel.ERROR)

@Composable
private fun LogLevel.label(): String = when (this) {
	LogLevel.DEBUG -> tr("log.dock.level.debug")
	LogLevel.INFO, LogLevel.SUCCESS -> tr("log.dock.level.info")
	LogLevel.WARNING -> tr("log.dock.level.warning")
	LogLevel.ERROR -> tr("log.dock.level.error")
}

/** One shown line: consecutive identical lines without images fold into the first, counted. */
private class LogRow(val first: AppLogEntry, val latest: AppLogEntry, val count: Int)

private fun AppLogEntry.repeats(other: AppLogEntry) = imageBytes == null && other.imageBytes == null &&
	source == other.source && level == other.level && tag == other.tag && message == other.message && detail == other.detail

private fun foldRepeats(entries: List<AppLogEntry>): List<LogRow> {
	val rows = ArrayList<LogRow>(entries.size)
	for (entry in entries) {
		val last = rows.lastOrNull()
		if (last != null && last.latest.repeats(entry)) rows[rows.lastIndex] = LogRow(last.first, entry, last.count + 1)
		else rows += LogRow(entry, entry, 1)
	}
	return rows
}

private data class FilterTabItem(
	val filter: LogFilter,
	val label: String,
	val badge: String? = null,
)

@Composable
fun BottomLogDock(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
	fillDock: Boolean = false,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current

	var currentFilter by remember { mutableStateOf(LogFilter.ALL) }
	var minLevel by remember { mutableStateOf(LogLevel.INFO) }
	var searchQuery by remember { mutableStateOf("") }
	var autoScroll by remember { mutableStateOf(true) }
	var columns by remember { mutableStateOf(LogColumnLayout.decode(AppSettings.logColumns)) }

	val listState = rememberLazyListState()

	val filteredEntries = remember(state.logEntries, currentFilter, minLevel, searchQuery) {
		state.logEntries.filter { entry ->
			if (entry.level.severity < minLevel.severity) return@filter false
			val matchesFilter = when (currentFilter) {
				LogFilter.ALL -> true
				LogFilter.SYSTEM -> entry.source == LogSource.SYSTEM
				LogFilter.EDITOR -> entry.source == LogSource.EDITOR
				LogFilter.AGENT_MCP -> entry.source == LogSource.MCP_SERVER || entry.source == LogSource.AGENT
				LogFilter.IMAGES_ONLY -> entry.imageBytes != null
			}
			val matchesSearch = if (searchQuery.isBlank()) true else {
				entry.message.contains(searchQuery, ignoreCase = true) ||
					entry.tag.contains(searchQuery, ignoreCase = true) ||
					entry.detail?.contains(searchQuery, ignoreCase = true) == true
			}
			matchesFilter && matchesSearch
		}
	}

	val rows = remember(filteredEntries) { foldRepeats(filteredEntries) }
	val expandedIds = remember { mutableStateMapOf<String, Boolean>() }

	// Our own scroll to the end must not read as the user leaving it.
	var following by remember { mutableStateOf(false) }
	LaunchedEffect(rows.size, rows.lastOrNull()?.count, autoScroll) {
		if (autoScroll && rows.isNotEmpty()) {
			following = true
			try {
				// The offset clamps to the list's end, so a last line taller than the view is shown to its bottom.
				listState.scrollToItem(rows.size - 1, Int.MAX_VALUE)
			} finally {
				following = false
			}
		}
	}
	// Scrolling up leaves the newest line and stops following; scrolling back down to the end follows again.
	LaunchedEffect(listState) {
		// Only the end of a scroll decides, not the state the list starts in.
		snapshotFlow { listState.isScrollInProgress }.drop(1).collect { scrolling ->
			if (!scrolling && !following) autoScroll = !listState.canScrollForward
		}
	}

	fun copyLogs() {
		val text = filteredEntries.joinToString("\n") { entry ->
			val time = TIME_FORMATTER.format(entry.timestamp)
			"[$time] [${entry.level}] [${entry.source}] [${entry.tag}] ${entry.message}"
		}
		val selection = StringSelection(text)
		Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
	}

	var splitterCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	val expanded = state.logPanelExpanded || fillDock
	val imageCount = remember(state.logEntries) { state.logEntries.count { it.imageBytes != null } }
	val filterTabs = listOf(
		FilterTabItem(LogFilter.ALL, tr("log.dock.filter.all")),
		FilterTabItem(LogFilter.SYSTEM, tr("log.dock.filter.system")),
		FilterTabItem(LogFilter.EDITOR, tr("log.dock.filter.editor")),
		FilterTabItem(LogFilter.AGENT_MCP, tr("log.dock.filter.agent")),
		FilterTabItem(
			LogFilter.IMAGES_ONLY,
			tr("log.dock.filter.image"),
			badge = if (imageCount > 0) "$imageCount" else null,
		),
	)

	Column(
		modifier = modifier
			.fillMaxWidth()
			.background(colors.panelBackground)
			.border(BorderStroke(1.dp, colors.divider)),
	) {
		if (state.logPanelExpanded && !fillDock) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(4.dp)
					.background(colors.divider)
					.onGloballyPositioned { splitterCoords = it }
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
					.pointerInput(density) {
						awaitEachGesture {
							val down = awaitFirstDown()
							val splitter = splitterCoords ?: return@awaitEachGesture
							if (!splitter.isAttached) return@awaitEachGesture
							val startMouseY = splitter.positionInWindow().y + down.position.y
							val startHeight = viewModel.state.value.logPanelHeight
							while (true) {
								val event = awaitPointerEvent()
								val change = event.changes.firstOrNull { it.id == down.id } ?: break
								if (!change.pressed) break
								change.consume()
								if (splitter.isAttached) {
									val currentMouseY = splitter.positionInWindow().y + change.position.y
									val deltaYPx = currentMouseY - startMouseY
									val deltaDp = with(density) { (-deltaYPx).toDp() }.value
									val newHeight = (startHeight + deltaDp).coerceIn(80f, 450f)
									viewModel.setLogPanelHeight(newHeight)
								}
							}
						}
					},
			)
		}

		PanelToolbar(
			iconCount = 3,
			search = if (expanded) PanelSearch(searchQuery, { searchQuery = it }, tr("log.dock.search"), fitContent = true) else null,
		) {
			if (!fillDock) {
				PanelIconButton(
					onClick = { viewModel.setLogPanelExpanded(!state.logPanelExpanded) },
					tooltip = tr("log.dock.title"),
				) {
					IconChevron(expanded = state.logPanelExpanded, tint = colors.textPrimary)
				}
				PanelToolbarTitle(tr("log.dock.title"))
			}
			if (expanded) {
				OverflowFilterTabs(
					items = filterTabs,
					selected = currentFilter,
					onSelect = { currentFilter = it },
					modifier = Modifier.weight(1f).fillMaxHeight(),
				)
				LogLevelMenu(
					entries = state.logEntries,
					selected = minLevel,
					onSelect = { minLevel = it },
				)
				PanelToolButton(
					label = tr("log.dock.autoScroll"),
					showLabel = false,
					onClick = { autoScroll = !autoScroll },
					enabled = true,
					active = autoScroll,
					tooltip = tr("log.dock.autoScroll"),
				) {
					IconLogAutoScroll(tint = if (autoScroll) colors.accent else colors.textMuted, following = autoScroll)
				}
				PanelToolbarSeparator()
				PanelIconButton(onClick = { viewModel.clearLogs() }, tooltip = tr("log.dock.clear")) {
					IconLogClear(tint = colors.textPrimary)
				}
				PanelIconButton(onClick = { copyLogs() }, tooltip = tr("log.dock.copy")) {
					IconLogCopy(tint = colors.textPrimary)
				}
			}
		}

		if (expanded) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.then(if (fillDock) Modifier.weight(1f) else Modifier.height(state.logPanelHeight.dp))
					.background(colors.inputBackground),
			) {
				LogColumnHeader(
					layout = columns,
					onChange = { columns = it },
					onCommit = { AppSettings.logColumns = columns.encode() },
				)
				Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
				if (rows.isEmpty()) {
					Box(
						modifier = Modifier.fillMaxSize(),
						contentAlignment = Alignment.Center,
					) {
						Text(
							text = tr(if (state.logEntries.isEmpty()) "log.dock.empty" else "log.dock.noMatch"),
							style = typography.caption.copy(fontSize = 11.sp),
							color = colors.textMuted,
						)
					}
				} else {
					SelectionContainer(modifier = Modifier.fillMaxSize().padding(vertical = 2.dp)) {
						LazyColumn(
							state = listState,
							modifier = Modifier.fillMaxSize(),
						) {
							items(rows, key = { it.first.id }) { row ->
								LogEntryRow(
									entry = row.latest,
									columns = columns,
									count = row.count,
									expanded = expandedIds[row.first.id] == true,
									onToggle = { expandedIds[row.first.id] = expandedIds[row.first.id] != true },
									onImageClick = { bytes, label ->
										viewModel.openLightbox(bytes, label)
									},
								)
							}
						}
					}
				}
				}
			}
		}
	}
}

@Composable
private fun OverflowFilterTabs(
	items: List<FilterTabItem>,
	selected: LogFilter,
	onSelect: (LogFilter) -> Unit,
	modifier: Modifier = Modifier,
) {
	val density = LocalDensity.current
	val typography = LocalToolTypography.current
	val textMeasurer = rememberTextMeasurer()
	val measureStyle = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

	BoxWithConstraints(modifier) {
		val maxPx = constraints.maxWidth
		val horizontalPadPx = with(density) { 16.dp.roundToPx() }
		val badgeExtraPx = with(density) { 14.dp.roundToPx() }
		val ellipsisWidthPx = with(density) { 28.dp.roundToPx() }
		val widths = items.map { item ->
			textMeasurer.measure(text = item.label, style = measureStyle).size.width +
				horizontalPadPx +
				if (item.badge != null) badgeExtraPx else 0
		}
		val selectedIndex = items.indexOfFirst { it.filter == selected }.coerceAtLeast(0)
		val (visible, overflow) = remember(items, widths, maxPx, selectedIndex) {
			pickVisibleFilterTabs(items, widths, maxPx, ellipsisWidthPx, selectedIndex)
		}

		Row(modifier = Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
			for (item in visible) {
				LogFilterTab(
					text = item.label,
					badge = item.badge,
					selected = item.filter == selected,
					onClick = { onSelect(item.filter) },
				)
			}
			if (overflow.isNotEmpty()) {
				OverflowEllipsis(
					highlighted = overflow.any { it.filter == selected },
					items = overflow,
					selected = selected,
					onSelect = onSelect,
				)
			}
		}
	}
}

private fun pickVisibleFilterTabs(
	items: List<FilterTabItem>,
	widths: List<Int>,
	maxPx: Int,
	ellipsisWidthPx: Int,
	selectedIndex: Int,
): Pair<List<FilterTabItem>, List<FilterTabItem>> {
	if (items.isEmpty() || maxPx <= 0) return emptyList<FilterTabItem>() to items
	if (widths.sum() <= maxPx) return items to emptyList()

	val budget = (maxPx - ellipsisWidthPx).coerceAtLeast(widths.getOrElse(0) { 0 })
	val visibleIdx = mutableListOf<Int>()
	var used = 0
	for (i in items.indices) {
		val width = widths[i]
		if (visibleIdx.isEmpty() || used + width <= budget) {
			visibleIdx += i
			used += width
		} else {
			break
		}
	}
	if (selectedIndex !in visibleIdx && selectedIndex in items.indices) {
		while (visibleIdx.size > 1 && used + widths[selectedIndex] > budget) {
			val dropAt = visibleIdx.indexOfLast { it != selectedIndex }
			if (dropAt < 0) break
			used -= widths[visibleIdx.removeAt(dropAt)]
		}
		if (selectedIndex !in visibleIdx) {
			val insertAt = visibleIdx.indexOfFirst { it > selectedIndex }.let { if (it < 0) visibleIdx.size else it }
			visibleIdx.add(insertAt, selectedIndex)
		}
	}
	val visibleSet = visibleIdx.toSet()
	return items.filterIndexed { index, _ -> index in visibleSet } to
		items.filterIndexed { index, _ -> index !in visibleSet }
}

@Composable
private fun LogFilterTab(
	text: String,
	selected: Boolean,
	onClick: () -> Unit,
	badge: String? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()

	Box(
		modifier = Modifier
			.fillMaxHeight()
			.hoverable(interactionSource)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.drawBehind {
				if (selected) {
					val bar = 2.dp.toPx()
					drawRect(
						color = colors.accent,
						topLeft = Offset(0f, size.height - bar),
						size = Size(size.width, bar),
					)
				}
			}
			.padding(horizontal = 8.dp),
		contentAlignment = Alignment.Center,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = text,
				style = typography.caption.copy(
					fontSize = 11.sp,
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
				),
				color = when {
					selected -> colors.textPrimary
					hovered -> colors.textPrimary
					else -> colors.textMuted
				},
				maxLines = 1,
			)
			if (badge != null) {
				Text(
					text = badge,
					style = typography.monoSmall.copy(fontSize = 9.sp),
					color = if (selected) colors.accent else colors.textMuted,
				)
			}
		}
	}
}

@Composable
private fun OverflowEllipsis(
	highlighted: Boolean,
	items: List<FilterTabItem>,
	selected: LogFilter,
	onSelect: (LogFilter) -> Unit,
) {
	val colors = LocalToolColors.current
	var open by remember { mutableStateOf(false) }
	LaunchedEffect(items) {
		if (items.isEmpty()) open = false
	}

	Box(modifier = Modifier.fillMaxHeight()) {
		LogFilterTab(
			text = "⋯",
			selected = highlighted || open,
			onClick = { open = !open },
		)
		if (open) {
			Popup(
				alignment = Alignment.BottomStart,
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
					Column(modifier = Modifier.widthIn(min = 140.dp, max = 220.dp)) {
						for (item in items) {
							AppMenuItem(
								text = item.label,
								isChecked = item.filter == selected,
								onClick = {
									onSelect(item.filter)
									open = false
								},
							)
						}
					}
				}
			}
		}
	}
}

/** The lowest level the dock shows; each choice counts the lines it would show. */
@Composable
private fun LogLevelMenu(
	entries: List<AppLogEntry>,
	selected: LogLevel,
	onSelect: (LogLevel) -> Unit,
) {
	val colors = LocalToolColors.current
	var open by remember { mutableStateOf(false) }
	val counts = remember(entries) {
		LEVEL_THRESHOLDS.associateWith { threshold -> entries.count { it.level.severity >= threshold.severity } }
	}
	Box(modifier = Modifier.fillMaxHeight()) {
		LogFilterTab(
			text = "${tr("log.dock.level")}: ${selected.label()} ▾",
			selected = open,
			onClick = { open = !open },
		)
		if (open) {
			Popup(
				alignment = Alignment.BottomStart,
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
					Column(modifier = Modifier.widthIn(min = 140.dp, max = 220.dp)) {
						for (level in LEVEL_THRESHOLDS) {
							AppMenuItem(
								text = level.label(),
								shortcut = "${counts[level] ?: 0}",
								isChecked = level == selected,
								onClick = {
									onSelect(level)
									open = false
								},
							)
						}
					}
				}
			}
		}
	}
}

/**
 * Follow the newest line: log lines on the left, an arrow down to the floor beside them. While following, the arrow
 * reaches the floor and the newest line is drawn full; otherwise the arrow stops short and the floor is faint.
 */
@Composable
private fun IconLogAutoScroll(tint: Color, following: Boolean) = GridIcon(Modifier.size(13.dp), tint) {
	line(2.4f, 3.6f, 8.6f, 3.6f, ICON_FINE)
	line(2.4f, 7.4f, 8.6f, 7.4f, ICON_FINE)
	line(2.4f, 11.2f, 6.6f, 11.2f, ICON_FINE)
	if (following) fillBox(2f, 13.8f, 7f, 2.4f, 0.8f) else line(2.4f, 15f, 6.6f, 15f, ICON_FINE)
	val tip = if (following) 12.6f else 11f
	line(13f, 2.4f, 13f, tip)
	chevron(13f, tip, 0f, 1f, 3.2f)
	line(10.4f, 15.6f, 15.6f, 15.6f, tint = if (following) color else soft)
}

/** Clear the log: the shared bin. */
@Composable
private fun IconLogClear(tint: Color) = GridIcon(Modifier.size(12.dp), tint) { trashCan() }

/** Copy the log: the shared two sheets. */
@Composable
private fun IconLogCopy(tint: Color) = GridIcon(Modifier.size(12.dp), tint) { copySheets() }

/** Source-chip colors and label for a log row, from the theme's tag tokens the history tree shares. */
@Composable
private fun logSourceBadge(source: LogSource, colors: ToolColors): Triple<Color, Color, String> = when (source) {
	LogSource.SYSTEM -> Triple(colors.tagSystem, colors.tagSystemText, tr("log.dock.filter.system"))
	// Writes and reads both read "AI"; the chip's colour still tells an edit from a look.
	LogSource.AGENT -> Triple(colors.tagAgent, colors.tagAgentText, tr("log.dock.filter.agent"))
	LogSource.MCP_SERVER -> Triple(colors.tagMcp, colors.tagMcpText, tr("log.dock.filter.agent"))
	// Same blue family the history tree gives a "User" node.
	LogSource.EDITOR -> Triple(colors.tagUser, colors.tagUserText, tr("log.dock.filter.editor"))
}

/** The edge stripe that marks a line's level; INFO and DEBUG lines go without. */
private fun levelStripe(level: LogLevel, colors: ToolColors): Color = when (level) {
	LogLevel.ERROR -> colors.error
	LogLevel.WARNING -> colors.warning
	LogLevel.SUCCESS -> colors.success
	LogLevel.INFO, LogLevel.DEBUG -> Color.Transparent
}

private val LOG_ROW_START = 3.dp
/** The detail toggle's column, its gap included. */
private val LOG_TOGGLE_WIDTH = 18.dp
private val LOG_GAP = 6.dp
private val LOG_HEADER_HEIGHT = 18.dp

/** A fixed column's width on screen, its trailing gap included, so header and rows line up cell for cell. */
private fun LogColumnLayout.cellWidth(column: LogColumn) = width(column).dp + LOG_GAP

/** Where the message column starts, for the detail and image under it. */
private fun LogColumnLayout.messageInset(): Dp =
	visible.takeWhile { it != LogColumn.MESSAGE }.fold(LOG_TOGGLE_WIDTH) { inset, column -> inset + cellWidth(column) }

@Composable
private fun LogColumn.title(): String = when (this) {
	LogColumn.TIME -> tr("log.dock.column.time")
	LogColumn.SOURCE -> tr("log.dock.column.source")
	LogColumn.TAG -> tr("log.dock.column.tag")
	LogColumn.MESSAGE -> tr("log.dock.column.message")
}

/**
 * The column titles over the log. Dragging a title moves its column, dragging the edge after it sets its width, and
 * the right-click menu shows or hides the time, source and tag columns or restores the defaults. [onChange] follows
 * a drag as it goes; [onCommit] runs once it ends, to keep the layout.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogColumnHeader(
	layout: LogColumnLayout,
	onChange: (LogColumnLayout) -> Unit,
	onCommit: () -> Unit,
) {
	val colors = LocalToolColors.current
	val density = LocalDensity.current
	val current by rememberUpdatedState(layout)
	val change by rememberUpdatedState(onChange)
	val commit by rememberUpdatedState(onCommit)
	val cellWidths = remember { mutableStateMapOf<LogColumn, Int>() }
	var dragging by remember { mutableStateOf<LogColumn?>(null) }
	var dragOffset by remember { mutableStateOf(0f) }
	var menuAt by remember { mutableStateOf<IntOffset?>(null) }

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(LOG_HEADER_HEIGHT)
			.background(colors.panelBackground)
			.drawBehind {
				val hairline = 1.dp.toPx()
				drawRect(colors.divider, topLeft = Offset(0f, size.height - hairline), size = Size(size.width, hairline))
			}
			.pointerInput(Unit) {
				awaitPointerEventScope {
					while (true) {
						val event = awaitPointerEvent()
						if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
							val position = event.changes.first().position
							menuAt = IntOffset(position.x.toInt(), position.y.toInt())
						}
					}
				}
			},
	) {
		Row(
			modifier = Modifier.fillMaxSize().padding(start = LOG_ROW_START, end = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Spacer(Modifier.width(LOG_TOGGLE_WIDTH))
			val visible = layout.visible
			for (column in visible) {
				key(column) {
					val isMessage = column == LogColumn.MESSAGE
					val active = dragging == column
					Box(
						modifier = Modifier
							.then(if (isMessage) Modifier.weight(1f) else Modifier.width(layout.cellWidth(column)))
							.fillMaxHeight()
							.onGloballyPositioned { cellWidths[column] = it.size.width }
							.zIndex(if (active) 1f else 0f)
							.graphicsLayer { translationX = if (active) dragOffset else 0f },
						contentAlignment = Alignment.CenterStart,
					) {
						LogHeaderTitle(
							text = column.title(),
							active = active,
							modifier = Modifier
								.fillMaxHeight()
								.padding(end = LOG_GAP)
								.fillMaxWidth()
								.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)))
								.pointerInput(column) {
									// Several moves can land before the next composition hands back the layout.
									var working = current
									detectHorizontalDragGestures(
										onDragStart = { working = current; dragging = column; dragOffset = 0f },
										onDragEnd = { dragging = null; dragOffset = 0f; commit() },
										onDragCancel = { dragging = null; dragOffset = 0f; commit() },
									) { pointer, delta ->
										pointer.consume()
										dragOffset += delta
										val shown = working.visible
										val index = shown.indexOf(column)
										val next = shown.getOrNull(index + 1)
										val previous = shown.getOrNull(index - 1)
										val nextWidth = next?.let { cellWidths[it] } ?: 0
										val previousWidth = previous?.let { cellWidths[it] } ?: 0
										// Past half of a neighbour, trade places with it; the offset carries over.
										if (next != null && dragOffset > nextWidth / 2f) {
											working = working.moved(column, index + 1)
											change(working)
											dragOffset -= nextWidth
										} else if (previous != null && dragOffset < -previousWidth / 2f) {
											working = working.moved(column, index - 1)
											change(working)
											dragOffset += previousWidth
										}
									}
								},
						)
						if (!isMessage) {
							Box(
								modifier = Modifier
									.align(Alignment.CenterEnd)
									.width(LOG_GAP)
									.fillMaxHeight()
									.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
									.pointerInput(column) {
										var start = 0f
										var moved = 0f
										detectHorizontalDragGestures(
											onDragStart = { start = current.width(column); moved = 0f },
											onDragEnd = { commit() },
											onDragCancel = { commit() },
										) { pointer, delta ->
											pointer.consume()
											moved += delta
											change(current.resized(column, start + with(density) { moved.toDp() }.value))
										}
									},
								contentAlignment = Alignment.Center,
							) {
								Box(Modifier.width(1.dp).height(10.dp).background(colors.divider))
							}
						}
					}
				}
			}
		}

		menuAt?.let { position ->
			Popup(
				alignment = Alignment.TopStart,
				offset = position,
				onDismissRequest = { menuAt = null },
				properties = PopupProperties(focusable = true),
			) {
				Surface(
					color = colors.panelElevated,
					border = BorderStroke(1.dp, colors.border),
					shape = RoundedCornerShape(3.dp),
					elevation = 8.dp,
				) {
					Column(modifier = Modifier.widthIn(min = 140.dp, max = 220.dp)) {
						for (column in LogColumn.entries.filter { it != LogColumn.MESSAGE }) {
							AppMenuItem(
								text = column.title(),
								isChecked = column !in layout.hidden,
								onClick = { change(layout.toggled(column)); commit() },
							)
						}
						AppMenuItem(
							text = tr("log.dock.column.reset"),
							onClick = {
								change(LogColumnLayout())
								commit()
								menuAt = null
							},
						)
					}
				}
			}
		}
	}
}

@Composable
private fun LogHeaderTitle(text: String, active: Boolean, modifier: Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Box(
		modifier = modifier
			.hoverable(interaction)
			.clip(RoundedCornerShape(2.dp))
			.background(
				when {
					active -> colors.controlActive
					hovered -> colors.controlHover.copy(alpha = 0.6f)
					else -> Color.Transparent
				},
			)
			.padding(horizontal = 2.dp),
		contentAlignment = Alignment.CenterStart,
	) {
		Text(
			text = text,
			style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
			color = if (active || hovered) colors.textPrimary else colors.textMuted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/**
 * One line: a level stripe at the edge, then the detail toggle and the [columns] in their order and widths, so
 * messages line up under the header; errors and warnings carry a faint wash of their colour. The detail opens under
 * the message.
 */
@Composable
private fun LogEntryRow(
	entry: AppLogEntry,
	columns: LogColumnLayout,
	count: Int,
	expanded: Boolean,
	onToggle: () -> Unit,
	onImageClick: (ByteArray, String?) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()

	val timeText = remember(entry.timestamp) { TIME_FORMATTER.format(entry.timestamp) }
	val (sourceBg, sourceFg, sourceLabel) = logSourceBadge(entry.source, colors)
	val stripe = levelStripe(entry.level, colors)
	val textColor = when (entry.level) {
		LogLevel.ERROR -> colors.error
		LogLevel.WARNING -> colors.warning
		LogLevel.SUCCESS, LogLevel.INFO -> colors.textPrimary
		LogLevel.DEBUG -> colors.textMuted
	}
	val wash = when (entry.level) {
		LogLevel.ERROR -> colors.error.copy(alpha = 0.08f)
		LogLevel.WARNING -> colors.warning.copy(alpha = 0.06f)
		else -> Color.Transparent
	}
	val hasDetail = !entry.detail.isNullOrBlank()
	val inset = columns.messageInset()

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.hoverable(interaction)
			.background(if (hovered) colors.controlBackground.copy(alpha = 0.5f) else wash)
			.drawBehind {
				if (stripe != Color.Transparent) drawRect(stripe, size = Size(2.dp.toPx(), size.height))
				val hairline = 0.5.dp.toPx()
				drawRect(colors.divider.copy(alpha = 0.35f), topLeft = Offset(0f, size.height - hairline), size = Size(size.width, hairline))
			}
			.padding(start = LOG_ROW_START, end = 6.dp, top = 2.dp, bottom = 2.dp),
	) {
		Row(verticalAlignment = Alignment.Top) {
			Box(
				modifier = Modifier
					.width(LOG_TOGGLE_WIDTH)
					.padding(end = LOG_GAP)
					.height(16.dp)
					.then(
						if (hasDetail) Modifier
							.clickable(onClick = onToggle)
							.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
						else Modifier,
					),
				contentAlignment = Alignment.Center,
			) {
				if (hasDetail) {
					IconChevron(
						expanded = expanded,
						modifier = Modifier.size(9.dp),
						tint = if (hovered || expanded) colors.textPrimary else colors.textMuted,
					)
				}
			}
			for (column in columns.visible) {
				val cell = if (column == LogColumn.MESSAGE) Modifier.weight(1f) else Modifier.width(columns.cellWidth(column))
				when (column) {
					LogColumn.TIME -> Text(
						text = timeText,
						style = typography.monoSmall.copy(fontSize = 10.sp, lineHeight = 16.sp),
						color = colors.textMuted,
						modifier = cell.padding(end = LOG_GAP),
						maxLines = 1,
						overflow = TextOverflow.Clip,
					)
					LogColumn.SOURCE -> Box(
						modifier = cell.padding(end = LOG_GAP).height(16.dp),
						contentAlignment = Alignment.CenterStart,
					) {
						Box(
							modifier = Modifier
								.clip(RoundedCornerShape(2.dp))
								.background(sourceBg)
								.padding(horizontal = 4.dp),
						) {
							Text(
								text = sourceLabel,
								style = typography.monoSmall.copy(fontSize = 9.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold),
								color = sourceFg,
								maxLines = 1,
								overflow = TextOverflow.Clip,
							)
						}
					}
					LogColumn.TAG -> Text(
						text = entry.tag,
						style = typography.caption.copy(fontSize = 10.sp, lineHeight = 16.sp),
						color = colors.textMuted,
						modifier = cell.padding(end = LOG_GAP),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					LogColumn.MESSAGE -> Row(
						modifier = cell.padding(end = if (column == columns.visible.last()) 0.dp else LOG_GAP),
						horizontalArrangement = Arrangement.spacedBy(LOG_GAP),
					) {
						Text(
							text = entry.message,
							style = typography.mono.copy(fontSize = 11.sp, lineHeight = 16.sp),
							color = textColor,
							modifier = Modifier.weight(1f),
						)
						if (count > 1) RepeatBadge(count)
					}
				}
			}
		}

		if (hasDetail && expanded) {
			Text(
				text = entry.detail.orEmpty(),
				style = typography.monoSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
				color = colors.textMuted,
				modifier = Modifier
					.padding(start = inset, top = 2.dp, bottom = 2.dp)
					.clip(RoundedCornerShape(2.dp))
					.background(colors.codeBackground.copy(alpha = 0.6f))
					.padding(horizontal = 6.dp, vertical = 3.dp),
			)
		}

		if (entry.imageBytes != null) LogImageCard(entry, inset, onImageClick)
	}
}

/** How many identical lines a folded row stands for. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepeatBadge(count: Int) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	TooltipArea(tooltip = { ParameterTooltip(tr("log.dock.repeated", count)) }, delayMillis = 400) {
		Box(
			modifier = Modifier
				.height(16.dp)
				.clip(RoundedCornerShape(8.dp))
				.background(colors.controlBackground)
				.padding(horizontal = 5.dp),
			contentAlignment = Alignment.Center,
		) {
			Text(text = "×$count", style = typography.monoSmall.copy(fontSize = 9.sp), color = colors.textMuted)
		}
	}
}

/** A returned render under its line: a thumbnail on the checkerboard, its size, and a click to open it large. */
@Composable
private fun LogImageCard(entry: AppLogEntry, inset: Dp, onImageClick: (ByteArray, String?) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val imgBytes = entry.imageBytes ?: return
	val buffered = remember(imgBytes) {
		runCatching { ImageIO.read(ByteArrayInputStream(imgBytes)) }.getOrNull()
	} ?: return
	val bitmap = remember(buffered) { buffered.toImageBitmapFast() }
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val label = entry.imageLabel ?: entry.message

	Row(
		modifier = Modifier
			.padding(start = inset, top = 3.dp, bottom = 2.dp)
			.clip(RoundedCornerShape(4.dp))
			.background(colors.inputBackground)
			.border(BorderStroke(1.dp, if (hovered) colors.accent else colors.border), RoundedCornerShape(4.dp))
			.hoverable(interaction)
			.clickable { onImageClick(imgBytes, label) }
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.padding(4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Box(
			modifier = Modifier
				.size(width = 96.dp, height = 64.dp)
				.clip(RoundedCornerShape(3.dp)),
			contentAlignment = Alignment.Center,
		) {
			CheckerboardBackground(
				modifier = Modifier.fillMaxSize(),
				squareSizePx = 8f,
			)
			Image(
				bitmap = bitmap,
				contentDescription = label,
				modifier = Modifier.fillMaxSize().padding(2.dp),
			)
		}

		Column(modifier = Modifier.widthIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
			Text(
				text = label,
				style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = "${buffered.width} × ${buffered.height} px · ${(imgBytes.size / 1024).coerceAtLeast(1)} KB",
				style = typography.monoSmall.copy(fontSize = 9.5.sp),
				color = colors.textMuted,
			)
			Text(
				text = tr("log.dock.inspect"),
				style = typography.caption.copy(fontSize = 9.sp),
				color = if (hovered) colors.accent else colors.textMuted,
			)
		}
	}
}
