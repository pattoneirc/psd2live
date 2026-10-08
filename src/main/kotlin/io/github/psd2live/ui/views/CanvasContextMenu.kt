package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.tooloptions.menuOptions
import io.github.psd2live.ui.views.tooloptions.ToolOptionMenu

/** Whether a right press on [editor]'s canvas has a menu to open: the tool in hand has options or actions for it. */
internal fun canvasContextMenuHasContent(editor: CanvasEditor): Boolean = menuOptions(editor).isNotEmpty()

/**
 * The canvas context menu, opened where the right press landed: the tool in hand's actions and its most used
 * values - the same options the tool options bar shows, from the same definitions (see [menuOptions]).
 */
@Composable
internal fun CanvasContextMenu(
    editor: CanvasEditor,
    expanded: Boolean,
    clickOffset: Offset,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit = {},
) {
    if (!expanded && !canvasContextMenuHasContent(editor)) return

    TreeContextMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        clickOffset = clickOffset,
        minWidth = 236.dp,
        maxWidth = 480.dp,
        frosted = true,
    ) {
        // Long groups open as a second level beside the menu, so the menu keeps to a screenful; the scroll is a
        // fallback for a very short canvas.
        Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            ToolOptionMenu(editor, onDismiss = onDismissRequest, onAction = onAction)
        }
    }
}
