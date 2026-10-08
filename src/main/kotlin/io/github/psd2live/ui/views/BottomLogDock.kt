package io.github.psd2live.ui.views

import io.github.psd2live.ui.utils.toImageBitmapFast
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

	LaunchedEffect(rows.size, rows.lastOrNull()?.count, autoScroll) {
		if (autoScroll && rows.isNotEmpty()) {
			listState.scrollToItem(rows.size - 1)
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
			search = if (expanded) PanelSearch(searchQuery, { searchQuery = it }, tr("log.dock.search")) else null,
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
					IconLogAutoScroll(tint = if (autoScroll) colors.accent else colors.textMuted)
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
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.then(if (fillDock) Modifier.weight(1f) else Modifier.height(state.logPanelHeight.dp))
					.background(colors.inputBackground),
			) {
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

/** Follow the newest line: an arrow down to the floor. */
@Composable
private fun IconLogAutoScroll(tint: Color) = GridIcon(Modifier.size(12.dp), tint) {
	line(9f, 2.4f, 9f, 12.4f)
	chevron(9f, 12.4f, 0f, 1f, 4.4f)
	line(3f, 15.6f, 15f, 15.6f)
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
private val LOG_TOGGLE_WIDTH = 18.dp
private val LOG_TIME_WIDTH = 50.dp
private val LOG_SOURCE_WIDTH = 40.dp
private val LOG_TAG_WIDTH = 64.dp
private val LOG_GAP = 6.dp
/** Where the message column starts, for the detail and image under it. */
private val LOG_MESSAGE_INSET = LOG_TOGGLE_WIDTH + LOG_TIME_WIDTH + LOG_SOURCE_WIDTH + LOG_TAG_WIDTH + LOG_GAP * 3

/**
 * One line: a level stripe at the edge, then a fixed column each for the detail toggle, time, source and tag, so
 * messages line up; errors and warnings carry a faint wash of their colour. The detail opens under the message.
 */
@Composable
private fun LogEntryRow(
	entry: AppLogEntry,
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
		Row(
			verticalAlignment = Alignment.Top,
			horizontalArrangement = Arrangement.spacedBy(LOG_GAP),
		) {
			Box(
				modifier = Modifier
					.width(LOG_TOGGLE_WIDTH - LOG_GAP)
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
			Text(
				text = timeText,
				style = typography.monoSmall.copy(fontSize = 10.sp, lineHeight = 16.sp),
				color = colors.textMuted,
				modifier = Modifier.width(LOG_TIME_WIDTH),
				maxLines = 1,
			)
			Box(
				modifier = Modifier.width(LOG_SOURCE_WIDTH).height(16.dp),
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
					)
				}
			}
			Text(
				text = entry.tag,
				style = typography.caption.copy(fontSize = 10.sp, lineHeight = 16.sp),
				color = colors.textMuted,
				modifier = Modifier.width(LOG_TAG_WIDTH),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = entry.message,
				style = typography.mono.copy(fontSize = 11.sp, lineHeight = 16.sp),
				color = textColor,
				modifier = Modifier.weight(1f),
			)
			if (count > 1) RepeatBadge(count)
		}

		if (hasDetail && expanded) {
			Text(
				text = entry.detail.orEmpty(),
				style = typography.monoSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
				color = colors.textMuted,
				modifier = Modifier
					.padding(start = LOG_MESSAGE_INSET, top = 2.dp, bottom = 2.dp)
					.clip(RoundedCornerShape(2.dp))
					.background(colors.codeBackground.copy(alpha = 0.6f))
					.padding(horizontal = 6.dp, vertical = 3.dp),
			)
		}

		if (entry.imageBytes != null) LogImageCard(entry, onImageClick)
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
private fun LogImageCard(entry: AppLogEntry, onImageClick: (ByteArray, String?) -> Unit) {
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
			.padding(start = LOG_MESSAGE_INSET, top = 3.dp, bottom = 2.dp)
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
