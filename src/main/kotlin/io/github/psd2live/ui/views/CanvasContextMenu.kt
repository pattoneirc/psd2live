package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.Arrangement
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
import io.github.psd2live.ui.views.tooloptions.ToolOptionMenuSection

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
        minWidth = 220.dp,
        maxWidth = 248.dp,
        frosted = true,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            ToolOptionMenuSection(editor, onDismiss = onDismissRequest, onAction = onAction)
        }
    }
}
