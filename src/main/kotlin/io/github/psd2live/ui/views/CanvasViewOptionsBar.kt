package io.github.psd2live.ui.views

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import io.github.psd2live.ui.state.TabViewOptions
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget

/** What a row of a [CanvasOptionsRail] needs to animate with it: whether the rail is open, and its labels' fade and slide. */
internal class CanvasRailScope(val expanded: Boolean, val textAlpha: Float, val textOffset: androidx.compose.ui.unit.Dp)

/**
 * The bottom-right display rail of a canvas: a column of icon toggles on the trailing edge that, while hovered,
 * widens leftward and reveals each row's label - the mirror of the left tool palette. The edit canvas
 * ([CanvasViewOptionsBar]) and the atlas page build their rows with [RailToggle] and [RailDivider].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CanvasOptionsRail(modifier: Modifier = Modifier, content: @Composable CanvasRailScope.() -> Unit) {
	val toolbarInteractionSource = remember { MutableInteractionSource() }
	val isHoveredBySource by toolbarInteractionSource.collectIsHoveredAsState()
	var isHoveredByEvent by remember { mutableStateOf(false) }
	val isToolbarHovered = isHoveredBySource || isHoveredByEvent

	val animatedWidth by animateDpAsState(
		targetValue = if (isToolbarHovered) 168.dp else 34.dp,
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
	val textOffset by animateDpAsState(
		targetValue = if (isToolbarHovered) 0.dp else 6.dp,
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
	val scope = CanvasRailScope(animatedWidth > 42.dp, textAlpha, textOffset)
	Column(
		modifier = modifier
			.width(animatedWidth)
			.frostedGlass(
				shape = RoundedCornerShape(6.dp),
				isHovered = isToolbarHovered,
				elevation = elevation,
				alpha = 0.78f,
			)
			.hoverable(toolbarInteractionSource)
			.onPointerEvent(PointerEventType.Enter) { isHoveredByEvent = true }
			.onPointerEvent(PointerEventType.Exit) { isHoveredByEvent = false }
			.padding(3.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
		horizontalAlignment = Alignment.End,
	) { scope.content() }
}

/** A thin rule between groups of a [CanvasOptionsRail]. */
@Composable
internal fun CanvasRailScope.RailDivider() {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 4.dp, vertical = 2.dp)
			.height(1.dp)
			.background(LocalToolColors.current.border.copy(alpha = 0.35f)),
	)
}

/** One toggle of a [CanvasOptionsRail]: its icon on the trailing edge, its label while the rail is open. */
@Composable
internal fun CanvasRailScope.RailToggle(label: String, isChecked: Boolean, icon: @Composable (Color) -> Unit, onClick: () -> Unit) =
	ViewOptionRow(label, isChecked, expanded, textAlpha, textOffset, icon, onClick)

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

@Composable
private fun ViewOptionRow(
	label: String,
	isChecked: Boolean,
	isToolbarExpanded: Boolean,
	textAlpha: Float,
	textOffset: androidx.compose.ui.unit.Dp,
	icon: @Composable (Color) -> Unit,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val itemInteractionSource = remember { MutableInteractionSource() }
	val isItemHovered by itemInteractionSource.collectIsHoveredAsState()

	val bg = when {
		isChecked -> colors.accent.copy(alpha = 0.24f)
		isItemHovered -> colors.controlHover.copy(alpha = 0.7f)
		else -> Color.Transparent
	}
	val tint = when {
		isChecked -> colors.accent
		isItemHovered -> colors.textPrimary
		else -> colors.textMuted
	}

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(28.dp)
			.clip(RoundedCornerShape(3.dp))
			.background(bg)
			.semantics { contentDescription = label }
			.clickable(
				interactionSource = itemInteractionSource,
				indication = null,
				onClick = onClick,
			),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.End,
	) {
		if (isToolbarExpanded) {
			Text(
				text = label,
				color = when {
					isChecked -> colors.textPrimary
					isItemHovered -> colors.textPrimary
					else -> colors.textMuted
				},
				fontSize = 11.5.sp,
				fontWeight = if (isChecked) FontWeight.Medium else FontWeight.Normal,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier
					.weight(1f)
					.offset(x = textOffset)
					.alpha(textAlpha)
					.padding(start = 6.dp, end = 2.dp),
			)
		}
		Box(
			modifier = Modifier.size(28.dp),
			contentAlignment = Alignment.Center,
		) {
			icon(tint)
		}
	}
}

/** Atlas pixels: a coarse 2x2 grid; source pixels: a fine 4x4 grid. */
@Composable
private fun IconPixelSource(source: Boolean, tint: Color, modifier: Modifier) {
	androidx.compose.foundation.Canvas(modifier) {
		val cells = if (source) 4 else 2
		val inset = size.width * 0.12f
		val side = (size.width - inset * 2) / cells
		for (y in 0 until cells) for (x in 0 until cells) {
			if ((x + y) % 2 != 0) continue
			drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(inset + x * side, inset + y * side),
				size = androidx.compose.ui.geometry.Size(side, side))
		}
		drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
			size = androidx.compose.ui.geometry.Size(side * cells, side * cells), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
	}
}
