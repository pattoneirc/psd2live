package io.github.psd2live.ui.components

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

@Composable
fun RebuildMeshPromptDialog(
    layerName: String,
    onConfirmRebuild: () -> Unit,
    onKeepExisting: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    ModalDialogFrame(
        title = tr("editor.paint.rebuildTitle"),
        onDismiss = onDismiss,
        width = 420.dp,
        onConfirm = onConfirmRebuild,
        footer = {
            CompactButton(text = tr("editor.paint.keepMesh"), onClick = onKeepExisting)
            CompactButton(text = tr("editor.paint.rebuildMesh"), onClick = onConfirmRebuild, isPrimary = true)
        },
    ) {
        ModalMessage(tr("editor.paint.rebuildBody", layerName))
        Text(
            text = tr("editor.paint.rebuildHint"),
            style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
            color = colors.textMuted,
        )
    }
}
