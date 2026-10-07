package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.ExportSuccess
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/** How many notes the dialog lists before summing up the rest; the log keeps them all. */
private const val SHOWN_NOTES = 8

/**
 * Reports a finished export, whichever it was (Cubism, PSD or Export as): what was written, where, and the
 * export's warnings or losses. Close and don't show again turns the prompt off; Settings › Prompts turns it on.
 */
@Composable
internal fun ExportSuccessDialog(
	success: ExportSuccess,
	onOpenFolder: () -> Unit,
	onDismiss: () -> Unit,
	onDontShowAgain: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	ModalDialogFrame(
		title = tr("dialog.exportSuccess.title"),
		onDismiss = onDismiss,
		width = 460.dp,
		maxHeight = 560.dp,
		tone = ModalTone.SUCCESS,
		onConfirm = onOpenFolder,
		footerStart = {
			CompactButton(text = tr("prompt.closeAndDontShow"), onClick = onDontShowAgain)
		},
		footer = {
			CompactButton(text = tr("dialog.close"), onClick = onDismiss)
			CompactButton(
				text = tr("dialog.openFolder"),
				onClick = onOpenFolder,
				isPrimary = true,
				leadingIcon = { IconFolder(Modifier.size(13.dp), tint = colors.accentText) },
			)
		},
	) {
		ModalMessage(success.message)
		Row(
			Modifier.fillMaxWidth()
				.background(colors.inputBackground, RoundedCornerShape(4.dp))
				.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp))
				.padding(horizontal = 8.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			IconFolder(Modifier.size(14.dp), tint = colors.textMuted)
			Text(
				success.folder,
				style = typography.monoSmall.copy(fontSize = 10.5.sp),
				color = colors.textPrimary,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (success.notes.isNotEmpty()) Column(
			Modifier.fillMaxWidth()
				.background(colors.panelElevated, RoundedCornerShape(4.dp))
				.border(BorderStroke(1.dp, colors.warning.copy(alpha = 0.6f)), RoundedCornerShape(4.dp))
				.padding(8.dp),
			verticalArrangement = Arrangement.spacedBy(3.dp),
		) {
			Text(
				success.notesTitle ?: tr("log.warnings"),
				style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
				color = colors.warning,
			)
			for (note in success.notes.take(SHOWN_NOTES)) Text(
				"· $note",
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.textPrimary,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			if (success.notes.size > SHOWN_NOTES) Text(
				tr("dialog.exportSuccess.moreNotes", success.notes.size - SHOWN_NOTES),
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.textMuted,
			)
		}
	}
}
