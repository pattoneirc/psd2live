package io.github.psd2live.ui.views.tooloptions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.ICON_FINE
import io.github.psd2live.ui.components.IconCheck
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconDeformPath
import io.github.psd2live.ui.components.IconDrawOrder
import io.github.psd2live.ui.components.IconRotationDeformer
import io.github.psd2live.ui.components.IconSkeleton
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.components.IconUndo
import io.github.psd2live.ui.components.IconWarpDeformer
import io.github.psd2live.ui.components.PaintFgBgSwatch
import io.github.psd2live.ui.components.copySheets
import io.github.psd2live.ui.components.subdivide
import io.github.psd2live.ui.components.toHex
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.tooloptions.ActionOption
import io.github.psd2live.ui.tooloptions.ChoiceOption
import io.github.psd2live.ui.tooloptions.NoteOption
import io.github.psd2live.ui.tooloptions.OptionIcon
import io.github.psd2live.ui.tooloptions.SectionOption
import io.github.psd2live.ui.tooloptions.SliderOption
import io.github.psd2live.ui.tooloptions.ToggleOption
import io.github.psd2live.ui.tooloptions.ToolOption
import io.github.psd2live.ui.tooloptions.barOptions
import io.github.psd2live.ui.tooloptions.menuOptions
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import io.github.psd2live.ui.views.texture.AccentButton
import io.github.psd2live.ui.views.texture.BarChip
import io.github.psd2live.ui.views.texture.BarDivider
import io.github.psd2live.ui.views.texture.BarValueChip
import io.github.psd2live.ui.views.texture.FloatingBar
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuRadio
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.texture.FloatingMenuSlider
import io.github.psd2live.ui.views.texture.FloatingMenuSwitch

/** Whether [editor] has a tool options bar to show: options of its own, or paint mode's colours. */
internal fun toolOptionsBarShown(editor: CanvasEditor): Boolean =
    editor.hierarchyMode == EditHierarchyMode.PAINT || barOptions(editor).isNotEmpty()

/**
 * The tool options bar: under the mode bar, the settings and actions of the tool in hand, as [barOptions] lists
 * them - Photoshop's options bar in the canvas's frosted style. Paint mode leads with its colours.
 * [onHeight] reports the bar's height so the tool palette can make room under it.
 */
@Composable
internal fun BoxScope.ToolOptionsBar(editor: CanvasEditor, focus: () -> Unit, onHeight: (Int) -> Unit = {}) {
    val options = barOptions(editor)
    val painting = editor.hierarchyMode == EditHierarchyMode.PAINT
    if (!painting && options.isEmpty()) {
        LaunchedEffect(Unit) { onHeight(0) }
        return
    }
    val colors = LocalToolColors.current
    FloatingBar(
        Modifier
            .align(Alignment.TopStart)
            .padding(start = 8.dp, top = 42.dp, end = 56.dp)
            .onSizeChanged { onHeight(it.height) }
            .tutorialTarget(TutorialTargetId.TOOL_OPTIONS_BAR),
    ) {
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (painting) {
                // Photoshop's foreground/background pair; X swaps them on the canvas too.
                PaintFgBgSwatch(
                    foreground = editor.paintColor,
                    background = editor.paintSecondaryColor,
                    onForegroundChanged = { editor.paintColor = it },
                    onBackgroundChanged = { editor.paintSecondaryColor = it },
                    onSwap = { editor.swapPaintColors(); focus() },
                    squareSize = 15.dp,
                    modifier = Modifier.padding(horizontal = 3.dp),
                )
                Text(editor.paintColor.toHex(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.textMuted,
                    modifier = Modifier.padding(end = 2.dp))
                if (options.isNotEmpty()) BarDivider()
            }
            options.forEach { option -> BarOption(editor, option, focus) }
        }
    }
}

@Composable
private fun BarOption(editor: CanvasEditor, option: ToolOption, focus: () -> Unit) {
    val colors = LocalToolColors.current
    when (option) {
        is SectionOption -> BarDivider()
        is SliderOption -> BarValueChip(
            label = tr(option.labelKey),
            display = option.display(option.get(editor)),
            value = option.get(editor),
            onValueChange = { option.apply(editor, it) },
            valueRange = option.range(editor),
            scrub = { start, dx -> option.scrubbed(start, dx, editor.brushSizeLimit) },
            logarithmic = option.logarithmic,
            onCommit = focus,
        )
        is ChoiceOption<*> -> BarChoice(editor, option, focus)
        is ToggleOption -> BarChip(tr(option.labelKey), selected = option.get(editor), onClick = {
            option.set(editor, !option.get(editor)); focus()
        })
        is ActionOption -> {
            val label = tr(option.labelKey, *option.labelArgs(editor).toTypedArray())
            val run = { option.run(editor); focus() }
            if (option.primary) AccentButton(label, onClick = run, enabled = option.enabled(editor),
                icon = option.icon?.let { icon -> { tint -> OptionIconView(icon, tint) } })
            else BarChip(label, selected = false, onClick = run, enabled = option.enabled(editor),
                icon = option.icon?.let { icon -> { tint -> OptionIconView(icon, tint) } })
        }
        is NoteOption -> option.text(editor)?.let { text ->
            Text(text, fontSize = 10.5.sp, color = if (option.warning(editor)) colors.warning else colors.textMuted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun <T> BarChoice(editor: CanvasEditor, option: ChoiceOption<T>, focus: () -> Unit) {
    val current = option.get(editor)
    val choices = option.choices(editor)
    if (option.inline) {
        choices.forEach { choice ->
            BarChip(option.label(editor, choice), selected = choice == current, tooltip = tr(option.labelKey),
                onClick = { option.set(editor, choice); focus() })
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        BarChip("${tr(option.labelKey)}  ${option.label(editor, current)}", selected = false, onClick = { open = !open },
            chevron = true, open = open)
        FloatingMenu(open, { open = false }) {
            choices.forEach { choice ->
                FloatingMenuRadio(option.label(editor, choice), selected = choice == current,
                    onSelect = { option.set(editor, choice); open = false; focus() })
            }
        }
    }
}

/**
 * The context menu's share of the tool's options ([menuOptions]): the values as sliders and switches, the choices
 * as radio groups and the actions as rows. [onAction] returns focus to the canvas; an action closes the menu
 * through [onDismiss] unless it is one taken several times in a row.
 */
@Composable
internal fun ColumnScope.ToolOptionMenuSection(editor: CanvasEditor, onDismiss: () -> Unit, onAction: () -> Unit = {}) {
    val colors = LocalToolColors.current
    menuOptions(editor).forEach { option ->
        when (option) {
            is SectionOption -> FloatingMenuSection(tr(option.labelKey))
            is SliderOption -> FloatingMenuSlider(
                label = tr(option.labelKey),
                value = option.get(editor),
                onValueChange = { option.apply(editor, it) },
                valueRange = option.range(editor),
                display = option.display(option.get(editor)),
                logarithmic = option.logarithmic,
            )
            is ChoiceOption<*> -> MenuChoice(editor, option)
            is ToggleOption -> FloatingMenuSwitch(tr(option.labelKey), option.get(editor), { option.set(editor, it) })
            is ActionOption -> FloatingMenuRow(
                label = tr(option.labelKey, *option.labelArgs(editor).toTypedArray()),
                onClick = {
                    option.run(editor)
                    onAction()
                    if (!option.keepsMenu) onDismiss()
                },
                enabled = option.enabled(editor),
                icon = option.icon?.let { icon ->
                    { tint -> OptionIconView(icon, if (option.danger && option.enabled(editor)) colors.error else tint) }
                },
            )
            is NoteOption -> option.text(editor)?.let { text ->
                Text(text, fontSize = 10.5.sp, color = if (option.warning(editor)) colors.warning else colors.textMuted, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun <T> ColumnScope.MenuChoice(editor: CanvasEditor, option: ChoiceOption<T>) {
    val current = option.get(editor)
    FloatingMenuSection(tr(option.labelKey))
    option.choices(editor).forEach { choice ->
        FloatingMenuRadio(option.label(editor, choice), selected = choice == current, onSelect = { option.set(editor, choice) })
    }
}

/** The drawing of [icon], in [tint]. */
@Composable
internal fun OptionIconView(icon: OptionIcon, tint: Color) {
    val modifier = Modifier.size(12.dp)
    when (icon) {
        OptionIcon.SELECT_ALL -> IconMenuSelectAll(tint, Modifier.size(13.dp))
        OptionIcon.INVERT -> IconMenuInvert(tint, Modifier.size(13.dp))
        OptionIcon.LINKED -> IconMenuLinked(tint, Modifier.size(13.dp))
        OptionIcon.DESELECT -> IconClose(modifier = Modifier.size(10.dp), tint = tint)
        OptionIcon.SPLIT -> IconMenuSplit(tint, modifier)
        OptionIcon.SUBDIVIDE -> GridIcon(modifier, tint) { subdivide() }
        OptionIcon.CONNECT -> IconMenuConnect(tint, modifier)
        OptionIcon.MERGE -> IconMenuMerge(tint, modifier)
        OptionIcon.DELETE -> IconTrash(modifier = modifier, tint = tint)
        OptionIcon.DUPLICATE -> GridIcon(modifier, tint) { copySheets() }
        OptionIcon.WARP -> IconWarpDeformer(tint, modifier)
        OptionIcon.ROTATION -> IconRotationDeformer(modifier = modifier, tint = tint)
        OptionIcon.PATH -> IconDeformPath(modifier = modifier, tint = tint)
        OptionIcon.SKELETON -> IconSkeleton(modifier = modifier, tint = tint)
        OptionIcon.SWING -> IconSwing(tint, modifier)
        OptionIcon.DEPTH_SPLIT -> IconDrawOrder(modifier = modifier, tint = tint)
        OptionIcon.CONFIRM -> IconCheck(modifier = Modifier.size(11.dp), tint = tint)
        OptionIcon.CANCEL -> IconClose(modifier = Modifier.size(9.dp), tint = tint)
        OptionIcon.UNDO -> IconUndo(Modifier.size(12.dp), tint)
        OptionIcon.CLEAR -> IconTrash(modifier = Modifier.size(11.dp), tint = tint)
    }
}

/** Select all: everything inside the frame checked. */
@Composable
private fun IconMenuSelectAll(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    panel(2.4f, 2.4f, 13.2f, 13.2f, 2f)
    outline(path { m(5.6f, 9.2f); l(8f, 11.6f); l(12.6f, 6.4f) }, 1.6f)
}

/** Invert selection: a disc half filled. */
@Composable
private fun IconMenuInvert(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    fill(Path().apply {
        moveTo(p(9f, 2.4f).x, p(9f, 2.4f).y)
        lineTo(p(9f, 15.6f).x, p(9f, 15.6f).y)
        arcTo(androidx.compose.ui.geometry.Rect(p(2.4f, 2.4f), p(15.6f, 15.6f)), 90f, 180f, forceMoveTo = false)
        close()
    })
    ring(9f, 9f, 6.6f)
}

/** Select linked: two vertices joined by an edge. */
@Composable
private fun IconMenuLinked(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    line(7.6f, 9f, 10.4f, 9f)
    shape(circlePath(4.8f, 9f, 2.8f))
    shape(circlePath(13.2f, 9f, 2.8f))
}

/** Split: a vertex inserted in the middle of an edge. */
@Composable
private fun IconMenuSplit(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    line(4.8f, 11.4f, 13.2f, 6.6f)
    ring(3.4f, 12.2f, 1.6f, ICON_FINE)
    ring(14.6f, 5.8f, 1.6f, ICON_FINE)
    dot(9f, 9f, 1.9f)
}

/** Connect: an edge drawn between two vertices. */
@Composable
private fun IconMenuConnect(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    line(4f, 13f, 14f, 5f)
    dot(4f, 13f, 1.9f)
    dot(14f, 5f, 1.9f)
}

/** Merge: two vertices drawn into one. */
@Composable
private fun IconMenuMerge(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    ring(3.6f, 4f, 1.5f, ICON_FINE)
    ring(14.4f, 4f, 1.5f, ICON_FINE)
    line(4.8f, 5.4f, 7.6f, 8.8f)
    chevron(7.6f, 8.8f, 1f, 1.2f, 2.6f)
    line(13.2f, 5.4f, 10.4f, 8.8f)
    chevron(10.4f, 8.8f, -1f, 1.2f, 2.6f)
    dot(9f, 12.6f, 2.2f)
}

/** Swing: a pendulum with the arc it sways along. */
@Composable
private fun IconSwing(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    dot(9f, 2.6f, 1.2f)
    line(9f, 2.6f, 12.2f, 11.6f)
    dot(12.6f, 12.6f, 2.2f)
    arcArrow(9f, 2.6f, 12.8f, 55f, 70f)
}
