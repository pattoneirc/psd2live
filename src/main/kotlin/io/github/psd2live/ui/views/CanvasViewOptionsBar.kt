package io.github.psd2live.ui.views

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.IconDeformPath
import io.github.psd2live.ui.components.IconPathHardness
import io.github.psd2live.ui.components.IconPathWidth
import io.github.psd2live.ui.components.IconSkeleton
import io.github.psd2live.ui.components.IconMeshWireframe
import io.github.psd2live.ui.components.IconRotationDeformer
import io.github.psd2live.ui.components.IconSelectedOnly
import io.github.psd2live.ui.components.IconTextureView
import io.github.psd2live.ui.components.IconWarpDeformer
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.ICON_FINE
import io.github.psd2live.ui.state.TabViewOptions
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.ui.views.texture.BarTooltip
import io.github.psd2live.ui.views.texture.canvasChrome
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget

/**
 * What a row of a [CanvasOptionsRail] needs to animate with it: whether the rail is open, its labels' fade and
 * slide, and which edge it hangs from ([leading] for the tool palettes on the left).
 */
internal class CanvasRailScope(
	val expanded: Boolean,
	val textAlpha: Float,
	val textOffset: androidx.compose.ui.unit.Dp,
	val leading: Boolean = false,
)

/**
 * A canvas rail: a column of icon rows on one edge that, while hovered, widens and reveals each row's label.
 * The display rail sits bottom-right ([CanvasViewOptionsBar], the atlas page) and the tool palettes of the edit
 * and preview canvases on the left ([leading]); all build their rows with [RailItem], [RailToggle] and
 * [RailDivider]. [pinned] holds it open, for a menu dropped from one of its rows; [scrollable] lets a long
 * palette scroll on a short canvas.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CanvasOptionsRail(
	modifier: Modifier = Modifier,
	leading: Boolean = false,
	expandedWidth: androidx.compose.ui.unit.Dp = 168.dp,
	pinned: Boolean = false,
	scrollable: Boolean = false,
	content: @Composable CanvasRailScope.() -> Unit,
) {
	val toolbarInteractionSource = remember { MutableInteractionSource() }
	val isHoveredBySource by toolbarInteractionSource.collectIsHoveredAsState()
	var isHoveredByEvent by remember { mutableStateOf(false) }
	val isToolbarHovered = isHoveredBySource || isHoveredByEvent || pinned

	val animatedWidth by animateDpAsState(
		targetValue = if (isToolbarHovered) expandedWidth else 34.dp,
		animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
	)
	val textAlpha by animateFloatAsState(
		targetValue = if (isToolbarHovered) 1f else 0f,
		animationSpec = tween(
			durationMillis = if (isToolbarHovered) 150 else 80,
			delayMillis = if (isToolbarHovered) 40 else 0,
			easing = FastOutSlowInEasing,
		),
	)
	// Labels slide in from the rail's own edge.
	val textOffset by animateDpAsState(
		targetValue = if (isToolbarHovered) 0.dp else if (leading) (-6).dp else 6.dp,
		animationSpec = tween(
			durationMillis = if (isToolbarHovered) 180 else 80,
			delayMillis = if (isToolbarHovered) 30 else 0,
			easing = FastOutSlowInEasing,
		),
	)
	val elevation by animateDpAsState(
		targetValue = if (isToolbarHovered) 8.dp else 2.dp,
		animationSpec = tween(durationMillis = 200),
	)
	val scope = CanvasRailScope(animatedWidth > 42.dp, textAlpha, textOffset, leading)
	Column(
		modifier = modifier
			.width(animatedWidth)
			.canvasChrome()
			.frostedGlass(
				shape = RoundedCornerShape(6.dp),
				isHovered = isToolbarHovered,
				elevation = elevation,
				alpha = 0.78f,
			)
			.hoverable(toolbarInteractionSource)
			.onPointerEvent(PointerEventType.Enter) { isHoveredByEvent = true }
			.onPointerEvent(PointerEventType.Exit) { isHoveredByEvent = false }
			.let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
			.padding(3.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
		horizontalAlignment = if (leading) Alignment.Start else Alignment.End,
	) { scope.content() }
}

/** A thin rule between groups of a [CanvasOptionsRail]; [strong] for the rule above a tool's own variants. */
@Composable
internal fun CanvasRailScope.RailDivider(strong: Boolean = false) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 4.dp, vertical = 2.dp)
			.height(1.dp)
			.background(LocalToolColors.current.border.copy(alpha = if (strong) 0.45f else 0.35f)),
	)
}

/** One toggle of a [CanvasOptionsRail]: its icon on the rail's edge, its label while the rail is open. */
@Composable
internal fun CanvasRailScope.RailToggle(label: String, isChecked: Boolean, icon: @Composable (Color) -> Unit, onClick: () -> Unit) =
	RailItem(label, isChecked, icon = icon, onClick = onClick)

/**
 * One row of a [CanvasOptionsRail]: a tool, a tool's variant or a display toggle. [keyLabel] is the chord that
 * reaches it, boxed as a key cap when [keyCap] (a tool) or plain (a variant). [tooltip] says why a row is disabled.
 */
@Composable
internal fun CanvasRailScope.RailItem(
	label: String,
	selected: Boolean,
	enabled: Boolean = true,
	keyLabel: String = "",
	keyCap: Boolean = false,
	tooltip: String? = null,
	icon: @Composable (Color) -> Unit,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val itemInteractionSource = remember { MutableInteractionSource() }
	val isItemHovered by itemInteractionSource.collectIsHoveredAsState()

	val bg = when {
		selected -> colors.accent.copy(alpha = 0.24f)
		isItemHovered && enabled -> colors.controlHover.copy(alpha = 0.7f)
		else -> Color.Transparent
	}
	val tint = when {
		!enabled -> colors.textDisabled
		selected -> colors.accent
		isItemHovered -> colors.textPrimary
		else -> colors.textMuted
	}
	BarTooltip(tooltip) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(28.dp)
				.clip(RoundedCornerShape(3.dp))
				.background(bg)
				.semantics { contentDescription = if (keyLabel.isEmpty()) label else "$label  $keyLabel" }
				.hoverable(itemInteractionSource)
				.clickable(
					interactionSource = itemInteractionSource,
					indication = null,
					enabled = enabled,
					onClick = onClick,
				),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = if (leading) Arrangement.Start else Arrangement.End,
		) {
			if (leading) Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon(tint) }
			if (expanded) {
				Row(
					modifier = Modifier
						.weight(1f)
						.offset(x = textOffset)
						.alpha(textAlpha)
						.padding(start = if (leading) 2.dp else 6.dp, end = if (leading) 4.dp else 2.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = label,
						color = when {
							!enabled -> colors.textDisabled
							selected || isItemHovered -> colors.textPrimary
							else -> colors.textMuted
						},
						fontSize = 11.5.sp,
						fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f),
					)
					if (keyLabel.isNotEmpty()) RailKeyLabel(keyLabel, keyCap, selected)
				}
			}
			if (!leading) Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon(tint) }
		}
	}
}

/** The chord of a rail row: a key cap for a tool, plain text for a tool's variant. */
@Composable
private fun RailKeyLabel(keyLabel: String, keyCap: Boolean, selected: Boolean) {
	val colors = LocalToolColors.current
	if (keyCap) {
		Box(
			modifier = Modifier
				.background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.panelElevated, RoundedCornerShape(3.dp))
				.border(0.5.dp, if (selected) colors.accent.copy(alpha = 0.4f) else colors.border.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
				.padding(horizontal = 4.dp, vertical = 1.dp),
			contentAlignment = Alignment.Center,
		) {
			Text(keyLabel, color = if (selected) colors.accent else colors.textDisabled, fontSize = 9.5.sp,
				fontFamily = FontFamily.Monospace, maxLines = 1)
		}
	} else {
		Text(keyLabel, color = colors.textMuted.copy(alpha = 0.75f), fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
			maxLines = 1, modifier = Modifier.padding(end = 4.dp))
	}
}

/**
 * Bottom-right mirror of the left tool palette: the most-used per-tab display toggles, so the
 * View menu does not have to be opened mid-edit.
 *
 * Path guides and selection-focus toggles are Edit-only overlays, so [showPathGuides] /
 * [showSelectionFocus] hide those rows on Preview tabs.
 */
@Composable
internal fun CanvasViewOptionsBar(
	options: TabViewOptions,
	onOptionsChange: (TabViewOptions) -> Unit,
	showPathGuides: Boolean = true,
	showSelectionFocus: Boolean = true,
	/** Edit canvases can sample layer rasters (source pixels) instead of the atlas. */
	showSourcePixels: Boolean = false,
	modifier: Modifier = Modifier,
) {
	fun apply(updated: TabViewOptions) {
		onOptionsChange(updated.normalized())
	}
	CanvasOptionsRail(modifier.tutorialTarget(TutorialTargetId.VIEW_OPTIONS_BAR)) {
		RailToggle(
			label = tr("canvas.visibility.texture"),
			isChecked = options.showTexture,
			icon = { IconTextureView(tint = it, modifier = Modifier.size(14.dp)) },
			onClick = { apply(options.copy(showTexture = !options.showTexture)) },
		)
		if (showSourcePixels) {
			RailToggle(
				label = tr("canvas.visibility.sourcePixels"),
				isChecked = options.sourcePixels,
				icon = { IconPixelSource(source = options.sourcePixels, tint = it, modifier = Modifier.size(14.dp)) },
				onClick = { apply(options.copy(sourcePixels = !options.sourcePixels)) },
			)
		}
		RailToggle(
			label = tr("canvas.visibility.mesh"),
			isChecked = options.showMesh,
			icon = { IconMeshWireframe(tint = it, modifier = Modifier.size(14.dp)) },
			onClick = { apply(options.copy(showMesh = !options.showMesh)) },
		)
		RailToggle(
			label = tr("canvas.visibility.warp"),
			isChecked = options.showWarp,
			icon = { IconWarpDeformer(tint = it, modifier = Modifier.size(14.dp)) },
			onClick = { apply(options.copy(showWarp = !options.showWarp)) },
		)
		RailToggle(
			label = tr("canvas.visibility.rotation"),
			isChecked = options.showRotation,
			icon = { IconRotationDeformer(tint = it, modifier = Modifier.size(14.dp)) },
			onClick = { apply(options.copy(showRotation = !options.showRotation)) },
		)
		if (showPathGuides) {
			RailToggle(
				label = tr("canvas.visibility.paths"),
				isChecked = options.showDeformPaths,
				icon = { IconDeformPath(tint = it, modifier = Modifier.size(14.dp)) },
				onClick = { apply(options.copy(showDeformPaths = !options.showDeformPaths)) },
			)
			if (options.showDeformPaths) {
				RailToggle(
					label = tr("canvas.information.pathWidth"),
					isChecked = options.pathShowWidth,
					icon = { IconPathWidth(tint = it) },
					onClick = { apply(options.copy(pathShowWidth = !options.pathShowWidth)) },
				)
				RailToggle(
					label = tr("canvas.information.pathHardness"),
					isChecked = options.pathShowHardness,
					icon = { IconPathHardness(tint = it) },
					onClick = { apply(options.copy(pathShowHardness = !options.pathShowHardness)) },
				)
			}
			RailToggle(
				label = tr("canvas.visibility.skeleton"),
				isChecked = options.showSkeleton,
				icon = { IconSkeleton(tint = it, modifier = Modifier.size(14.dp)) },
				onClick = { apply(options.copy(showSkeleton = !options.showSkeleton)) },
			)
		}

		if (showSelectionFocus) {
			RailDivider()

			RailToggle(
				label = tr("canvas.information.selectedOnly"),
				isChecked = options.filterSelectedOnly,
				icon = { IconSelectedOnly(tint = it, modifier = Modifier.size(14.dp)) },
				onClick = { apply(options.copy(filterSelectedOnly = !options.filterSelectedOnly)) },
			)
		}
	}
}

/** Atlas pixels: a coarse 2x2 grid; source pixels: a fine 4x4 grid. */
@Composable
private fun IconPixelSource(source: Boolean, tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
	val cells = if (source) 4 else 2
	val side = 13.2f / cells
	for (y in 0 until cells) for (x in 0 until cells) {
		if ((x + y) % 2 == 0) fillBox(2.4f + x * side, 2.4f + y * side, side, side, 0f)
	}
	box(2.4f, 2.4f, 13.2f, 13.2f, 0.6f, ICON_FINE)
}
