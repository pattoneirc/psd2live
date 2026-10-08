package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.tutorial.expandShortcutMarkup
import io.github.psd2live.ui.BrushShape
import io.github.psd2live.ui.PaintShape
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.CanvasTarget
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.GLUE_SUB_TOOL_LABELS
import io.github.psd2live.ui.GLUE_WEIGHT_MODE_LABELS
import io.github.psd2live.ui.GlueSubTool
import io.github.psd2live.ui.SelectionStyle
import io.github.psd2live.ui.SkeletonEditSubTool
import io.github.psd2live.ui.SkeletonPoseSubTool
import io.github.psd2live.ui.WarpAddTo
import io.github.psd2live.ui.WarpSizeStrategy
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.HexField
import io.github.psd2live.ui.components.PAINT_SWATCHES
import io.github.psd2live.ui.components.PaintFgBgSwatch
import io.github.psd2live.ui.components.PaintSwatchRow
import io.github.psd2live.ui.components.PsColorField
import io.github.psd2live.ui.components.RecentPaintColors
import io.github.psd2live.ui.components.toHex
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlin.math.abs

@Composable
internal fun ToolDetailsView(
    editor: CanvasEditor,
    viewModel: PSD2LiveViewModel,
    state: PSD2LiveState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    if (state.previewModel == null || editor.state.previewModel == null) {
        Box(modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Text(tr("toolDetails.noModel"), style = typography.caption, color = colors.textMuted)
        }
        return
    }
    val target = editor.target()

    Column(modifier = modifier.fillMaxSize()) {
        // The tool, whether it can edit right now, and what it acts on.
        PanelToolbar(
            secondary = {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PanelToolbarText(tr("inspector.target"))
                    PanelToolbarText(
                        target?.geometry?.name ?: tr("editor.select"),
                        color = if (target != null) colors.textPrimary else colors.textMuted,
                    )
                }
            },
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(8.dp)
                    .background(colors.accent, RoundedCornerShape(2.dp))
            )
            PanelToolbarTitle(
                (if (state.activeWorkspace.canvases.size > 1) "${viewModel.canvasTitle(state.activeCanvas)} · " else "") +
                    tr("editor.tool.${editor.tool.name.lowercase()}"),
                modifier = Modifier.weight(1f),
            )
            PanelToolbarText(
                if (editor.busy) tr("editor.saving") else if (editor.editable) tr("editor.ready") else tr("editor.readonly"),
                color = if (editor.busy) colors.warning else if (editor.editable) colors.accent else colors.textDisabled,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The tool's everyday settings and actions are on the canvas, in the options bar and the context
            // menu; this panel keeps what needs more room: numeric transforms, create defaults, the path list,
            // the skeleton's binding and weights, and the paint palette.
            Text(tr("toolDetails.inOptionsBar"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted)
            if (target?.kind == "rotation") {
                Text(tr("editor.rotationGestureHint"), style = typography.caption, color = colors.textMuted)
                PreciseTransformColumn(editor)
            }

            when (editor.tool) {
                CanvasTool.TRANSFORM -> if (editor.hasTransformSelection && target?.kind != "rotation") PreciseTransformColumn(editor)

                CanvasTool.CREATE_WARP -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = tr("editor.tool.create_warp"),
                            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = colors.textPrimary,
                        )
                        Text(
                            text = expandShortcutMarkup(tr("editor.createWarpHint"), editor.state.keymap),
                            style = typography.caption.copy(fontSize = 10.5.sp),
                            color = colors.textMuted,
                        )

                        CompactSectionHeader(title = tr("editor.warpAddTo"))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                WarpAddTo.PARENT_OF_SELECTED to "editor.warpAddTo.parentOfSelected",
                                WarpAddTo.CHILD_OF_SELECTED_DEFORMER to "editor.warpAddTo.childOfDeformer",
                                WarpAddTo.SPECIFY_PARENT to "editor.warpAddTo.specifyParent",
                            ).forEach { (mode, key) ->
                                CompactToggleChip(
                                    text = tr(key),
                                    selected = editor.warpAddTo == mode,
                                    onToggle = { editor.warpAddTo = mode },
                                    height = 24.dp,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        if (editor.warpAddTo == WarpAddTo.SPECIFY_PARENT) {
                            val deformerOptions = listOf("" to tr("inspector.none")) +
                                editor.model.deformers.map { it.id.raw to it.name }
                            val selected = deformerOptions.firstOrNull { it.first == (editor.warpSpecifyParentId ?: "") }
                                ?: deformerOptions.first()
                            CompactDropdown(
                                items = deformerOptions,
                                selectedItem = selected,
                                onItemSelected = { editor.warpSpecifyParentId = it.first.takeIf { id -> id.isNotEmpty() } },
                                itemLabel = { it.second },
                                modifier = Modifier.fillMaxWidth(),
                                height = 24.dp,
                            )
                        }

                        CompactSectionHeader(title = tr("editor.warpPart"))
                        val partOptions = listOf("" to tr("editor.warpPart.inherit")) +
                            editor.model.parts.map { it.id.raw to it.name }
                        val partSelected = partOptions.firstOrNull { it.first == (editor.warpCreatePartId ?: "") }
                            ?: partOptions.first()
                        CompactDropdown(
                            items = partOptions,
                            selectedItem = partSelected,
                            onItemSelected = { editor.warpCreatePartId = it.first.takeIf { id -> id.isNotEmpty() } },
                            itemLabel = { it.second },
                            modifier = Modifier.fillMaxWidth(),
                            height = 24.dp,
                        )

                        CompactSectionHeader(title = tr("inspector.conversionDivision"))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(3 to 3, 5 to 5, 8 to 8).forEach { (r, c) ->
                                CompactToggleChip(
                                    text = "${r}×${c}",
                                    selected = editor.warpCreateGridRows == r && editor.warpCreateGridCols == c,
                                    onToggle = {
                                        editor.warpCreateGridRows = r
                                        editor.warpCreateGridCols = c
                                    },
                                    height = 24.dp,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CompactNumberSpinner(
                                value = editor.warpCreateGridCols.toDouble(),
                                onValueChange = { editor.warpCreateGridCols = it.toInt().coerceIn(1, 32) },
                                modifier = Modifier.weight(1f),
                                min = 1.0,
                                max = 32.0,
                                unit = "C",
                                height = 24.dp,
                            )
                            CompactNumberSpinner(
                                value = editor.warpCreateGridRows.toDouble(),
                                onValueChange = { editor.warpCreateGridRows = it.toInt().coerceIn(1, 32) },
                                modifier = Modifier.weight(1f),
                                min = 1.0,
                                max = 32.0,
                                unit = "R",
                                height = 24.dp,
                            )
                        }
                        CompactSectionHeader(title = tr("inspector.bezierDivision"))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(2 to 2, 3 to 3, 5 to 5).forEach { (r, c) ->
                                CompactToggleChip(
                                    text = "${r}×${c}",
                                    selected = editor.warpCreateBezierRows == r && editor.warpCreateBezierCols == c,
                                    onToggle = {
                                        editor.warpCreateBezierRows = r
                                        editor.warpCreateBezierCols = c
                                    },
                                    height = 24.dp,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CompactNumberSpinner(
                                value = editor.warpCreateBezierCols.toDouble(),
                                onValueChange = { editor.warpCreateBezierCols = it.toInt().coerceIn(1, 16) },
                                modifier = Modifier.weight(1f),
                                min = 1.0,
                                max = 16.0,
                                unit = "C",
                                height = 24.dp,
                            )
                            CompactNumberSpinner(
                                value = editor.warpCreateBezierRows.toDouble(),
                                onValueChange = { editor.warpCreateBezierRows = it.toInt().coerceIn(1, 16) },
                                modifier = Modifier.weight(1f),
                                min = 1.0,
                                max = 16.0,
                                unit = "R",
                                height = 24.dp,
                            )
                        }

                        CompactSectionHeader(title = tr("editor.warpSizeStrategy"))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                WarpSizeStrategy.SELECTION_BOUNDS to "editor.warpSize.selection",
                                WarpSizeStrategy.KEYFORM_ENVELOPE to "editor.warpSize.keyform",
                                WarpSizeStrategy.CENTER_ALIGN to "editor.warpSize.center",
                            ).forEach { (strategy, key) ->
                                CompactToggleChip(
                                    text = tr(key),
                                    selected = editor.warpSizeStrategy == strategy,
                                    onToggle = { editor.warpSizeStrategy = strategy },
                                    height = 24.dp,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        CompactToggleChip(
                            text = tr("editor.sequentialCreate"),
                            selected = editor.sequentialCreate,
                            onToggle = { editor.sequentialCreate = !editor.sequentialCreate },
                            height = 24.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                CanvasTool.CREATE_ROTATION -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = tr("editor.tool.create_rotation"),
                            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = colors.textPrimary,
                        )
                        Text(
                            text = expandShortcutMarkup(tr("editor.createRotationHint"), editor.state.keymap),
                            style = typography.caption.copy(fontSize = 10.5.sp),
                            color = colors.textMuted,
                        )
                        Text(
                            text = tr("editor.rotationMountHint"),
                            style = typography.caption.copy(fontSize = 10.5.sp),
                            color = colors.textMuted,
                        )
                        val (deformerIds, drawableIds) = editor.rotationScopeIds()
                        if (deformerIds.isNotEmpty() || drawableIds.isNotEmpty()) {
                            Text(
                                text = tr("editor.rotationScopeCount", deformerIds.size, drawableIds.size),
                                style = typography.caption.copy(fontSize = 10.5.sp),
                                color = colors.warning,
                            )
                        }
                        CompactSectionHeader(title = tr("editor.warpPart"))
                        val partOptions = listOf("" to tr("editor.warpPart.inherit")) +
                            editor.model.parts.map { it.id.raw to it.name }
                        val partSelected = partOptions.firstOrNull { it.first == (editor.warpCreatePartId ?: "") }
                            ?: partOptions.first()
                        CompactDropdown(
                            items = partOptions,
                            selectedItem = partSelected,
                            onItemSelected = { editor.warpCreatePartId = it.first.takeIf { id -> id.isNotEmpty() } },
                            itemLabel = { it.second },
                            modifier = Modifier.fillMaxWidth(),
                            height = 24.dp,
                        )
                        CompactToggleChip(
                            text = tr("editor.sequentialCreate"),
                            selected = editor.sequentialCreate,
                            onToggle = { editor.sequentialCreate = !editor.sequentialCreate },
                            height = 24.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                CanvasTool.GLUE -> {
                    val pair = editor.glueMeshPair()
                    val pairs = if (pair != null) editor.gluePreviewPoints().size else 0
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pair != null) {
                            Text(
                                text = tr("editor.glueMeshA") + "  " + editor.meshLabel(pair.first),
                                style = typography.caption.copy(fontSize = 10.5.sp),
                                color = io.github.psd2live.ui.GlueColorA,
                            )
                            Text(
                                text = tr("editor.glueMeshB") + "  " + editor.meshLabel(pair.second),
                                style = typography.caption.copy(fontSize = 10.5.sp),
                                color = io.github.psd2live.ui.GlueColorB,
                            )
                            Text(
                                text = if (pairs > 0) tr("editor.gluePairs", pairs) else tr("editor.glueNoPairs"),
                                style = typography.caption.copy(fontSize = 10.5.sp),
                                color = if (pairs > 0) colors.textMuted else colors.warning,
                            )
                        }
                        Text(
                            text = tr("editor.glueBrushHint"),
                            style = typography.caption.copy(fontSize = 10.5.sp),
                            color = colors.textMuted,
                        )
                    }
                }

                CanvasTool.CREATE_DEFORM_PATH -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = tr("editor.pathDeform"),
                            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = colors.textPrimary,
                        )
                        Text(
                            text = tr("editor.pathCreateHint"),
                            style = typography.caption.copy(fontSize = 10.5.sp),
                            color = colors.textMuted,
                        )
                        Text(
                            text = tr("editor.pathLevelHint", editor.pathLevel),
                            style = typography.caption.copy(fontSize = 10.5.sp),
                            color = colors.textMuted,
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactButton(
                                text = tr("editor.newPath"),
                                onClick = { editor.cancel(); editor.drawingPath = true },
                                enabled = target?.kind == "mesh" && editor.editable,
                                modifier = Modifier.weight(1f),
                                height = 25.dp,
                            )
                            if (editor.drawingPath) {
                                CompactButton(
                                    text = tr("editor.finishPath"),
                                    onClick = { editor.finishPath() },
                                    enabled = editor.draft.size >= 2,
                                    modifier = Modifier.weight(1f),
                                    height = 25.dp,
                                )
                            }
                        }

                        CompactSectionHeader(title = tr("editor.pathEditLevel"))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(2, 3).forEach { level ->
                                CompactToggleChip(
                                    text = "L$level",
                                    selected = editor.pathLevel == level,
                                    onToggle = { editor.pathLevel = level },
                                    height = 24.dp,
                                )
                            }
                        }

                        val active = editor.selectedPath()
                        if (active != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.panelElevated, RoundedCornerShape(4.dp))
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (!active.closed) {
                                        CompactButton(
                                            text = tr("editor.extend"),
                                            onClick = { editor.extendPath() },
                                            enabled = editor.editable,
                                            modifier = Modifier.weight(1f),
                                            height = 24.dp,
                                        )
                                    }
                                    CompactButton(
                                        text = tr("editor.delete"),
                                        onClick = { editor.deletePathPoint() },
                                        enabled = editor.editable,
                                        modifier = Modifier.weight(1f),
                                        height = 24.dp,
                                    )
                                    CompactButton(
                                        text = tr(if (active.closed) "editor.openPath" else "editor.closePath"),
                                        onClick = { editor.changePath { it.copy(closed = !it.closed) } },
                                        enabled = editor.editable && active.points.size >= 3,
                                        modifier = Modifier.weight(1f),
                                        height = 24.dp,
                                    )
                                }

                                if (editor.pathPoint in active.points.indices) {
                                    CompactButton(
                                        text = tr("editor.corner"),
                                        onClick = {
                                            editor.changePath { p ->
                                                p.copy(points = p.points.mapIndexed { i, pt ->
                                                    if (i == editor.pathPoint) pt.copy(corner = !pt.corner) else pt
                                                })
                                            }
                                        },
                                        enabled = editor.editable,
                                        modifier = Modifier.fillMaxWidth(),
                                        height = 24.dp,
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(tr("editor.width"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                                    CompactNumberSpinner(
                                        value = active.width.toDouble(),
                                        onValueChange = { w -> editor.changePath { it.copy(width = w.toFloat().coerceAtLeast(0f)) } },
                                        modifier = Modifier.weight(1f),
                                        min = 0.0,
                                        max = 100000.0,
                                        decimals = 2,
                                        step = 1.0,
                                        unit = "px",
                                        enabled = editor.editable,
                                        height = 24.dp,
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(tr("editor.hardness"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                                    CompactNumberSpinner(
                                        value = active.hardness.toDouble(),
                                        onValueChange = { h -> editor.changePath { it.copy(hardness = h.toFloat().coerceIn(0f, 100f)) } },
                                        modifier = Modifier.weight(1f),
                                        min = 0.0,
                                        max = 100.0,
                                        decimals = 2,
                                        unit = "%",
                                        enabled = editor.editable,
                                        height = 24.dp,
                                    )
                                }
                            }
                        }

                    }
                }
                CanvasTool.WEIGHT_PAINT, CanvasTool.WEIGHT_GRADIENT -> {
                    val brush = editor.tool == CanvasTool.WEIGHT_PAINT
                    Text(tr(if (brush) "editor.weightHint" else "editor.weightGradientHint"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted)
                }
                CanvasTool.SUBDIVIDE -> {
                    Text(
                        text = tr("editor.subdivideHint"),
                        style = typography.caption.copy(fontSize = 10.5.sp),
                        color = colors.textMuted,
                    )
                }
                CanvasTool.KNIFE -> {
                    Text(expandShortcutMarkup(tr("editor.knifeGestureHint"), editor.state.keymap), style = typography.caption, color = colors.textMuted)
                }
                CanvasTool.SKELETON_POSE -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (editor.bakedSkeleton != null) tr("skeleton.pose.hint") else tr("skeleton.pose.none"),
                            style = typography.caption.copy(fontSize = 10.5.sp),
                            color = colors.textMuted,
                        )
                        SkeletonSavedPoseControls(editor)
                        SkeletonIkControls(editor)
                    }
                }
                CanvasTool.SKELETON_EDIT -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = tr(editor.skeletonEditSubTool.labelKey),
                            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = colors.textPrimary,
                        )
                        if (editor.skeletonEditSubTool == SkeletonEditSubTool.BIND) SkeletonBindingControls(editor)
                        else if (editor.skeletonEditSubTool == SkeletonEditSubTool.WEIGHTS) SkeletonWeightControls(editor)
                        else SkeletonTransformControls(editor)
                        SkeletonIkControls(editor)
                    }
                }
                CanvasTool.PAINT_BRUSH, CanvasTool.PAINT_PENCIL, CanvasTool.PAINT_ERASER,
                CanvasTool.PAINT_BUCKET, CanvasTool.PAINT_EYEDROPPER,
                CanvasTool.PAINT_SHAPE -> {
                    PaintToolDetailsColumn(editor, target)
                }
                else -> Unit
            }

            Spacer(Modifier.weight(1f))
            Divider(color = colors.divider, thickness = 0.8.dp)

            // 5. History Undo / Redo Actions
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = tr("editor.history"),
                    style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactButton(
                        text = tr("editor.undo"),
                        onClick = { viewModel.undoHistory() },
                        enabled = editor.editable,
                        modifier = Modifier.weight(1f),
                        height = 26.dp,
                    )
                    CompactButton(
                        text = tr("editor.redo"),
                        onClick = { viewModel.redoHistory() },
                        enabled = editor.editable,
                        modifier = Modifier.weight(1f),
                        height = 26.dp,
                    )
                }
            }
        }
    }
}

/**
 * The numeric transform, shared by TRANSFORM, MESH and WARP so all three edit a multi-point selection
 * through the same controls and — because preciseTransform centres on the same pivot the box does —
 * about the same point.
 *
 * The pending values live here rather than on the editor, so leaving the tool and coming back starts
 * from the identity again.
 */
@Composable
private fun PreciseTransformColumn(editor: CanvasEditor) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = tr("editor.preciseTransform"),
            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
        )

        if (!editor.hasTransformSelection) {
            Text(
                text = tr("editor.select"),
                style = typography.caption.copy(fontSize = 10.5.sp),
                color = colors.textMuted,
            )
        }

        var posX by remember { mutableStateOf(0.0) }
        var posY by remember { mutableStateOf(0.0) }
        var scaleVal by remember { mutableStateOf(100.0) }
        var rotateVal by remember { mutableStateOf(0.0) }

        // Move row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactNumberSpinner(posX, { posX = it }, Modifier.weight(1f), min = -10000.0, max = 10000.0, decimals = 1, unit = "X", height = 24.dp)
            CompactNumberSpinner(posY, { posY = it }, Modifier.weight(1f), min = -10000.0, max = 10000.0, decimals = 1, unit = "Y", height = 24.dp)
            CompactButton(
                text = tr("editor.apply"),
                onClick = { editor.preciseTransform(first = posX.toFloat(), second = posY.toFloat()) },
                enabled = editor.editable && editor.hasTransformSelection && (posX != 0.0 || posY != 0.0),
                height = 24.dp,
            )
        }

        // Scale and rotate need a selection with extent — two points or more. A lone point spans nothing, so
        // its only operation is the move above; the rows would be controls that cannot do what they say.
        if (editor.selectionHasExtent) {
            // Scale row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactNumberSpinner(scaleVal, { scaleVal = it }, Modifier.weight(1f), min = 0.1, max = 10000.0, decimals = 1, unit = "%", height = 24.dp)
                CompactButton(
                    text = tr("editor.apply"),
                    onClick = { editor.preciseTransform(first = scaleVal.toFloat(), scaleMode = true) },
                    enabled = editor.editable && editor.hasTransformSelection && scaleVal != 100.0,
                    height = 24.dp,
                )
            }

            // Rotate row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactNumberSpinner(rotateVal, { rotateVal = it }, Modifier.weight(1f), min = -360.0, max = 360.0, decimals = 1, unit = "°", height = 24.dp)
                CompactButton(
                    text = tr("editor.apply"),
                    onClick = { editor.preciseTransform(first = rotateVal.toFloat(), rotateMode = true) },
                    enabled = editor.editable && editor.hasTransformSelection && rotateVal != 0.0,
                    height = 24.dp,
                )
            }
        }
    }
}

@Composable
private fun PaintToolDetailsColumn(editor: CanvasEditor, target: CanvasTarget?) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val paintT = editor.paintTarget()
    val layerId = editor.targetLayerId(paintT)
    val layerName = layerId?.let { lid ->
        editor.state.previewModel?.rig?.puppet?.drawables
            ?.firstOrNull { editor.state.previewModel?.rig?.layerIdByDrawableId?.get(it.id.raw) == lid }
            ?.name ?: lid
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Target layer status
        Text(
            text = tr("editor.paintTargetLayer"),
            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
        )
        if (paintT != null && layerId != null) {
            Text(
                text = layerName ?: layerId,
                style = typography.caption.copy(fontSize = 11.sp),
                color = colors.accent,
                maxLines = 1,
            )
        } else {
            Text(
                text = tr("editor.paintSelectLayerHint"),
                style = typography.caption.copy(fontSize = 10.5.sp),
                color = colors.error,
            )
        }

        Divider(color = colors.divider, thickness = 0.5.dp)

        // Palette: Photoshop's foreground/background pair, its colour field for the foreground, the swatches and the
        // colours used last.
        Text(
            text = tr("editor.paintPalette"),
            style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PaintFgBgSwatch(
                foreground = editor.paintColor,
                background = editor.paintSecondaryColor,
                onForegroundChanged = { editor.paintColor = it },
                onBackgroundChanged = { editor.paintSecondaryColor = it },
                onSwap = { editor.swapPaintColors() },
                onReset = { editor.resetPaintColors() },
                squareSize = 26.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "${tr("editor.paint.foreground")}  ${editor.paintColor.toHex()}",
                    color = colors.textPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Text(
                    text = "${tr("editor.paint.background")}  ${editor.paintSecondaryColor.toHex()}",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
        PsColorField(
            color = editor.paintColor,
            onColorChange = { editor.paintColor = it },
            modifier = Modifier.fillMaxWidth(),
            height = 120.dp,
            onRelease = RecentPaintColors::push,
        )
        HexField(editor.paintColor, { editor.paintColor = it })
        Text(
            text = tr("editor.paint.swatches"),
            style = typography.caption.copy(fontSize = 10.sp),
            color = colors.textMuted,
        )
        val pick = { color: Color -> editor.paintColor = color; RecentPaintColors.push(color) }
        PaintSwatchRow(PAINT_SWATCHES.take(8), editor.paintColor, pick)
        PaintSwatchRow(PAINT_SWATCHES.drop(8), editor.paintColor, pick)
        val recent = RecentPaintColors.colors.take(8)
        if (recent.isNotEmpty()) {
            Text(
                text = tr("editor.paint.recentColors"),
                style = typography.caption.copy(fontSize = 10.sp),
                color = colors.textMuted,
            )
            PaintSwatchRow(recent, editor.paintColor, pick)
        }

        Divider(color = colors.divider, thickness = 0.5.dp)

        // Actions
        val hasSession = editor.paintSession != null
        val uncommittedCount = editor.paintSession?.strokeCount ?: 0
        if (io.github.psd2live.core.DepthSplit.isFrontLayer(editor.state.previewModel, editor.paintSession?.layerId)) {
            Text(tr("editor.depthSplit.paintHint"), style = typography.caption, color = colors.textMuted)
        }
        if (paintT != null && layerId != null) {
            Spacer(Modifier.height(4.dp))

            // Discard and apply side by side, as the session bar at the top of the canvas has them.
            val pending = hasSession && (editor.paintSession?.isDirty == true || uncommittedCount > 0)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactButton(
                    text = tr("texture.session.discard"),
                    onClick = { editor.discardPaintSession() },
                    enabled = pending,
                    leadingIcon = { IconSessionDiscard(if (pending) colors.textPrimary else colors.textDisabled) },
                    modifier = Modifier.weight(1f),
                    height = 28.dp,
                )
                CompactButton(
                    text = if (uncommittedCount > 0) tr("editor.paint.applyCount", uncommittedCount) else tr("editor.paint.apply"),
                    onClick = { editor.promptCommitPaintSession() },
                    enabled = pending,
                    isPrimary = true,
                    leadingIcon = { IconSessionApply(if (pending) colors.accentText else colors.textDisabled) },
                    modifier = Modifier.weight(1.4f),
                    height = 28.dp,
                )
            }
        }
    }
}
