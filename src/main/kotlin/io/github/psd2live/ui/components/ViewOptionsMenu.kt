package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.TabViewOptions

// ============================================================================
// View option icons
// ============================================================================

/** Texture: a picture, its hills and sun. */
@Composable
fun IconTextureView(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	panel(2.2f, 2.2f, 13.6f, 13.6f, 1.6f)
	outline(path { m(3.6f, 12.6f); l(7.6f, 7.9f); l(10.4f, 10.8f); l(12.6f, 8.6f); l(14.4f, 12.2f) })
	dot(6.3f, 5.8f, 1.4f)
}

/** Mesh wireframe: the shared mesh glyph. */
@Composable
fun IconMeshWireframe(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) { meshPatch() }

/** Warp deformer: the shared lattice. */
@Composable
fun IconWarpDeformer(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) { warpLattice() }

/** Selection bounds: a dashed box with its corner handles. */
@Composable
fun IconSelectionBounds(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	box(3.6f, 3.6f, 10.8f, 10.8f, 0f, ICON_FINE, dash = floatArrayOf(1.6f, 2.2f))
	for (x in floatArrayOf(3.6f, 14.4f)) for (y in floatArrayOf(3.6f, 14.4f)) fillBox(x - 1.6f, y - 1.6f, 3.2f, 3.2f, 0.5f)
}

/** Contextual warp: the deformer above the selection, linked to it. */
@Composable
fun IconContextualWarp(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	box(2.5f, 2.5f, 6.5f, 5.8f, 1.2f, ICON_FINE, dash = floatArrayOf(1.6f, 1.8f))
	outline(path { m(5.75f, 8.3f); l(5.75f, 12.6f); l(9f, 12.6f) })
	panel(9f, 9.7f, 6.5f, 5.8f, 1.2f)
	dot(12.25f, 12.6f, 1.2f)
}

/** Selected only: framing brackets round the one element shown. */
@Composable
fun IconSelectedOnly(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	brackets(2.5f, 2.5f, 15.5f, 15.5f, arm = 4f)
	fillBox(6.5f, 6.5f, 5f, 5f, 1.2f)
}

/** Dim unselected: the selected layer in front, the others faint behind it. */
@Composable
fun IconDimUnselected(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	behind(rectPath(1.4f, 6.1f, 10.5f, 10.5f, 2.2f)) {
		box(7.2f, 2.5f, 8.3f, 8.3f, 1.5f, ICON_FINE, color.copy(alpha = color.alpha * 0.45f), floatArrayOf(1.6f, 2f))
	}
	panel(2.5f, 7.2f, 8.3f, 8.3f, 1.5f)
	dot(6.65f, 11.35f, 1.4f)
}

/** Names: a serif T. */
@Composable
fun IconWarpShowNames(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	outline(path { m(3.6f, 5.8f); l(3.6f, 4f); l(14.4f, 4f); l(14.4f, 5.8f) })
	line(9f, 4f, 9f, 14f)
	line(6.3f, 14f, 11.7f, 14f)
}

/** Point indices: a number sign. */
@Composable
fun IconWarpShowIndices(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	line(2.9f, 6.8f, 15.1f, 6.8f)
	line(2.9f, 11.5f, 15.1f, 11.5f)
	line(7.2f, 3.2f, 6.1f, 15.1f)
	line(11.9f, 3.2f, 10.8f, 15.1f)
}

/** The deform path icon's end anchors, as the canvas draws a path's points: each with its own ring. */
private val PATH_ICON_ENDS = listOf(3.6f to 13.7f, 14.4f to 4.3f)

/** The deform path glyph's curve and end anchors, drawn over each end's ring. */
private fun IconPen.pathIconBase() {
	outline(path { m(3.6f, 13.7f); c(3.6f, 7.3f, 14.4f, 10.7f, 14.4f, 4.3f) })
	PATH_ICON_ENDS.forEach { (x, y) -> fillBox(x - 1.4f, y - 1.4f, 2.8f, 2.8f, 0.5f) }
}

/** Path width: each point's reach, drawn as the dashed radius ring the canvas shows around it. */
@Composable
fun IconPathWidth(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	PATH_ICON_ENDS.forEach { (x, y) -> ring(x, y, 3.2f, ICON_FINE, dash = floatArrayOf(1.2f, 1.3f)) }
	pathIconBase()
}

/** Path hardness: each point's solid core, drawn as the filled hardness disc the canvas shows around it. */
@Composable
fun IconPathHardness(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	PATH_ICON_ENDS.forEach { (x, y) ->
		dot(x, y, 3f, soft)
		ring(x, y, 3f, ICON_FINE)
	}
	pathIconBase()
}

// ============================================================================
// View Options Menu Items
// ============================================================================

/**
 * The single source of truth for the per-tab canvas and annotation toggles, rendered by the tab
 * strip's "view options" dropdown.
 *
 * Items are categorized into three distinct DCC sections:
 * 1. Entities / Canvas & Model (纹理、网格线框、弯曲变形器、旋转变形器、变形路径 + 子选项 路径宽度/路径硬度)
 * 2. Selection & Focus (选中边框、关联变形器、仅显示选中项、淡化未选中)
 * 3. Annotations & Guides (名称、点编号)
 *
 * Deform path entries are `showPathGuides`-gated because path guides are an Edit-tab overlay:
 * offering the toggle on a Preview tab would advertise a switch that cannot change anything.
 * Selection & Focus is likewise Edit-only: Preview is view-only and ignores selection chrome.
 */
@Composable
fun ViewOptionsMenuItems(
	options: TabViewOptions,
	onOptionsChange: (TabViewOptions) -> Unit,
	onDismiss: () -> Unit,
	showHeaders: Boolean = true,
	showPathGuides: Boolean = true,
	showSelectionFocus: Boolean = true,
	onHover: (() -> Unit)? = null,
	onReset: (() -> Unit)? = null,
) {
	fun apply(updated: TabViewOptions) {
		onOptionsChange(updated.normalized())
		onDismiss()
	}

	// 1. 画布要素 (Entities / Canvas & Model)
	if (showHeaders) AppMenuHeader(tr("menu.view.category.canvas"))

	AppMenuItem(
		text = tr("canvas.visibility.texture"),
		icon = { IconTextureView(tint = it) },
		isChecked = options.showTexture,
		onHover = onHover,
		onClick = { apply(options.copy(showTexture = !options.showTexture)) },
	)
	AppMenuItem(
		text = tr("canvas.visibility.mesh"),
		icon = { IconMeshWireframe(tint = it) },
		isChecked = options.showMesh,
		onHover = onHover,
		onClick = { apply(options.copy(showMesh = !options.showMesh)) },
	)
	AppMenuItem(
		text = tr("canvas.visibility.warp"),
		icon = { IconWarpDeformer(tint = it) },
		isChecked = options.showWarp,
		onHover = onHover,
		onClick = { apply(options.copy(showWarp = !options.showWarp)) },
	)
	AppMenuItem(
		text = tr("canvas.visibility.rotation"),
		icon = { IconRotationDeformer(tint = it, modifier = Modifier.size(14.dp)) },
		isChecked = options.showRotation,
		onHover = onHover,
		onClick = { apply(options.copy(showRotation = !options.showRotation)) },
	)
	if (showPathGuides) {
		AppMenuItem(
			text = tr("canvas.visibility.paths"),
			icon = { IconDeformPath(tint = it, modifier = Modifier.size(14.dp)) },
			isChecked = options.showDeformPaths,
			onHover = onHover,
			onClick = { apply(options.copy(showDeformPaths = !options.showDeformPaths)) },
		)
		// 变形路径的子选项: width and hardness are annotations *of a path*, so they hang off the
		// entry above rather than sitting in the overlays group as independent toggles. They are
		// disabled -- not hidden -- while the parent is off, so the pair never jumps out of the
		// menu and their remembered state stays discoverable.
		AppMenuItem(
			text = tr("canvas.information.pathWidth"),
			icon = { IconPathWidth(tint = it) },
			isChecked = options.pathShowWidth,
			enabled = options.showDeformPaths,
			isChild = true,
			onHover = onHover,
			onClick = { apply(options.copy(pathShowWidth = !options.pathShowWidth)) },
		)
		AppMenuItem(
			text = tr("canvas.information.pathHardness"),
			icon = { IconPathHardness(tint = it) },
			isChecked = options.pathShowHardness,
			enabled = options.showDeformPaths,
			isChild = true,
			isLastChild = true,
			onHover = onHover,
			onClick = { apply(options.copy(pathShowHardness = !options.pathShowHardness)) },
		)
		AppMenuItem(
			text = tr("canvas.visibility.skeleton"),
			icon = { IconSkeleton(tint = it, modifier = Modifier.size(14.dp)) },
			isChecked = options.showSkeleton,
			onHover = onHover,
			onClick = { apply(options.copy(showSkeleton = !options.showSkeleton)) },
		)
	}

	if (showSelectionFocus) {
		AppMenuSeparator()

		// 2. 选区与聚焦 (Selection & Focus)
		if (showHeaders) AppMenuHeader(tr("menu.view.category.selection"))

		AppMenuItem(
			text = tr("canvas.information.selectionBounds"),
			icon = { IconSelectionBounds(tint = it) },
			isChecked = options.showSelectionBounds,
			onHover = onHover,
			onClick = { apply(options.copy(showSelectionBounds = !options.showSelectionBounds)) },
		)
		AppMenuItem(
			text = tr("canvas.information.contextualWarp"),
			icon = { IconContextualWarp(tint = it) },
			isChecked = options.contextualWarp,
			onHover = onHover,
			onClick = { apply(options.copy(contextualWarp = !options.contextualWarp)) },
		)
		AppMenuItem(
			text = tr("canvas.information.selectedOnly"),
			icon = { IconSelectedOnly(tint = it) },
			isChecked = options.filterSelectedOnly,
			onHover = onHover,
			onClick = { apply(options.copy(filterSelectedOnly = !options.filterSelectedOnly)) },
		)
		AppMenuItem(
			text = tr("canvas.visibility.dimUnselected"),
			icon = { IconDimUnselected(tint = it) },
			isChecked = options.dimUnselected,
			onHover = onHover,
			onClick = { apply(options.copy(dimUnselected = !options.dimUnselected)) },
		)
	}

	AppMenuSeparator()

	// 3. 辅助标注 (Annotations & Guides)
	if (showHeaders) AppMenuHeader(tr("menu.view.category.overlays"))

	AppMenuItem(
		text = tr("canvas.information.names"),
		icon = { IconWarpShowNames(tint = it) },
		isChecked = options.warpShowNames,
		onHover = onHover,
		onClick = { apply(options.copy(warpShowNames = !options.warpShowNames)) },
	)
	AppMenuItem(
		text = tr("canvas.information.indices"),
		icon = { IconWarpShowIndices(tint = it) },
		isChecked = options.warpShowIndices,
		onHover = onHover,
		onClick = { apply(options.copy(warpShowIndices = !options.warpShowIndices)) },
	)

	// 4. 底部动作 (Footer Actions)
	if (onReset != null) {
		AppMenuSeparator()
		AppMenuItem(
			text = tr("tab.resetView"),
			icon = { IconReset(tint = it, modifier = Modifier.size(14.dp)) },
			indentCheckSpace = true,
			onHover = onHover,
			onClick = onReset,
		)
	}
}
