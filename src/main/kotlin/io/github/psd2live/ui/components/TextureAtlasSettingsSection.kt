package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/**
 * The export dialog's view of the texture atlas: what the export will write - pages, page size, fit and upscale -
 * read from the committed atlas. The atlas, its budget and the textures are edited in the Texture workspace only,
 * so the export has no second set of fields that could disagree with it.
 */
@Composable
internal fun TextureAtlasSettingsSection(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val snapshot = io.github.psd2live.ui.views.texture.rememberTextureSnapshot(state, viewModel)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(3.dp))
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Column(Modifier.weight(1f)) {
			Text(tr("settings.group.texture"), style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary)
			val summary = snapshot?.atlas?.let { atlas ->
				tr("export.atlasSummary", atlas.pages.size, atlas.budget.pageSize, "%.0f".format(atlas.fit * 100f)) +
					if (state.textureUpscale.scale > 1) "  ·  " + tr("export.atlasUpscale", state.textureUpscale.scale) else ""
			} ?: tr("texture.atlas.empty")
			Text(summary, style = typography.caption.copy(fontSize = 10.5.sp),
				color = if ((snapshot?.atlas?.fit ?: 1f) < 1f) colors.warning else colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
		}
		CompactButton(
			text = tr("export.atlasEdit"),
			onClick = { viewModel.closeExportDialog(); viewModel.openWorkspacePreset(WorkspacePreset.TEXTURE) },
			height = 22.dp,
		)
	}
}
