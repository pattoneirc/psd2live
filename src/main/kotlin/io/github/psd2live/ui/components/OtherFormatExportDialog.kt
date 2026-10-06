package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.format.compile.ExportTarget
import io.github.psd2live.format.compile.TargetSetting
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * File > Export as, by kind of output. Every neutral target appears in exactly one group or among the
 * [experimentalExportTargets] (ExportTargetSettingsTest); the source PSD re-export sits with the layered PSDs but
 * has its own dialog.
 */
internal val exportMenuGroups: List<Pair<String, List<String>>> = listOf(
	"export.group.runtime" to listOf("vtube-studio", "p2lrt", "web"),
	"export.group.media" to listOf("png-sequence", "sprite-sheet", "gif", "apng", "webp", "mp4", "webm", "mov"),
	"export.group.psd" to listOf("psd-pose"),
)

/** Experimental targets stay out of the menu; see [io.github.psd2live.core.ExportService.experimental]. */
internal val experimentalExportTargets: Set<String> get() = io.github.psd2live.core.ExportService.experimental


/** The dialog of the neutral target chosen under File > Export as. */
@Composable
fun OtherFormatExportDialog(state: PSD2LiveState, viewModel: PSD2LiveViewModel, onChooseOutput: () -> Unit) {
	val targetId = state.otherExportTarget ?: return
	val target = remember(targetId, state.previewModel) { viewModel.otherExportTargets().firstOrNull { it.id == targetId } } ?: return
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isBusy = state.isAnalyzing || state.isGenerating

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(colors.scrim)
			.scrimDismiss(enabled = !isBusy) { viewModel.closeOtherExport() },
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(480.dp)
				.heightIn(max = 640.dp)
				.background(colors.panelBackground, RoundedCornerShape(8.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(8.dp))
				.clickable(enabled = false) {}
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(
					text = tr("export.other.dialogTitle", tr("export.target.${target.id}")),
					style = typography.title.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
					color = colors.textPrimary,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				CompactIconButton(onClick = { viewModel.closeOtherExport() }, enabled = !isBusy, size = 20.dp) {
					IconClose(modifier = Modifier.size(10.dp), tint = colors.textMuted)
				}
			}
			Column(
				modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				OtherFormatSection(state, viewModel, target)
				ExportOutputRow(state, viewModel, onChooseOutput, enabled = !isBusy)
				Text(tr("export.other.folderHint", target.id), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
			}
			CompactButton(
				text = if (isBusy) tr("export.other.running") else tr("export.other.export"),
				onClick = { viewModel.exportOtherFormat(target.id, viewModel.otherExportSettings(target)) },
				enabled = !isBusy && state.previewModel != null && state.outputPath.isNotBlank(),
				isPrimary = true,
				height = 28.dp,
				modifier = Modifier.fillMaxWidth(),
			)
			OtherFormatResult(viewModel, target)
		}
	}
}

/**
 * [target]'s declared settings as rows. Only values the user changed are sent; the rest keep the target's or
 * the project's defaults. Edits are kept per target while the app runs.
 */
@Composable
internal fun OtherFormatSection(state: PSD2LiveState, viewModel: PSD2LiveViewModel, target: ExportTarget) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isBusy = state.isAnalyzing || state.isGenerating
	val defaults = remember(target, state.previewModel) { viewModel.exportTargetDefaults(target) }
	val clips = remember(state.previewModel) { viewModel.exportClipChoices() }
	Column(
		modifier = Modifier.fillMaxWidth()
			.background(colors.panelElevated, RoundedCornerShape(3.dp))
			.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(3.dp))
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		if (target.settings.isEmpty()) Text(tr("export.other.noSettings"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
		for (setting in target.settings) {
			TargetSettingRow(setting, viewModel.otherExportSetting(target, setting.key) ?: defaults[setting.key], clips, enabled = !isBusy) { value ->
				viewModel.setOtherExportSetting(target, setting.key, value)
			}
		}
	}
}

@Composable
private fun OtherFormatResult(viewModel: PSD2LiveViewModel, target: ExportTarget) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val result by viewModel.otherExportResult.collectAsState()
	val report = result?.takeIf { it["target"]?.jsonPrimitive?.content == target.id } ?: return
	val losses = report["losses"]?.jsonArray.orEmpty().map { it.jsonObject }
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(tr("export.other.done", report["files"]?.jsonArray?.size ?: 0, report["directory"]?.jsonPrimitive?.content ?: ""),
			style = typography.caption.copy(fontSize = 10.sp), color = colors.textPrimary)
		if (losses.isEmpty()) Text(tr("export.other.noLosses"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
		else {
			Text(tr("export.other.losses", losses.size), style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
				color = colors.warning)
			for (loss in losses.distinctBy { it["note"]?.jsonPrimitive?.content }.take(8)) Text(
				"· ${tr("export.loss.${loss["handling"]?.jsonPrimitive?.content}")}: ${loss["note"]?.jsonPrimitive?.content}",
				style = typography.caption.copy(fontSize = 10.sp), color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/** One declared target setting; [value] is the current string value, null when the target derives it. [onChange] null restores the default. */
@Composable
private fun TargetSettingRow(
	setting: TargetSetting, value: String?, clips: List<Pair<String, String>>, enabled: Boolean, onChange: (String?) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	ExportLabeledRow(label = tr("export.setting.${setting.key}")) {
		when (setting) {
			is TargetSetting.ClipChoice -> {
				val items = listOf<Pair<String, String>?>(null) + clips
				CompactDropdown(items = items, selectedItem = clips.firstOrNull { it.first == value }, onItemSelected = { onChange(it?.first) },
					itemLabel = { it?.second ?: tr(if (setting.rest) "export.other.restPose" else "export.other.firstClip") },
					modifier = Modifier.weight(1f), enabled = enabled, height = 22.dp)
			}
			is TargetSetting.Flag -> CompactCheckbox(
				checked = value?.toBooleanStrictOrNull() ?: setting.default, onCheckedChange = { onChange(it.toString()) }, enabled = enabled,
			)
			is TargetSetting.Number -> {
				val number = value?.toDoubleOrNull()
				if (setting.default == null) {
					CompactCheckbox(checked = number != null, onCheckedChange = { custom -> onChange(if (custom) format(setting, setting.min) else null) },
						label = tr("export.other.custom"), enabled = enabled)
					Spacer(Modifier.width(8.dp))
				}
				val shown = number ?: setting.default
				if (shown != null) CompactNumberSpinner(
					value = shown, onValueChange = { onChange(format(setting, it)) }, min = setting.min, max = setting.max, step = setting.step,
					decimals = setting.decimals, enabled = enabled, modifier = Modifier.width(88.dp), height = 22.dp,
				) else Text(tr("export.other.auto"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
			}
			is TargetSetting.Text -> CompactTextField(
				value = value.orEmpty(), onValueChange = { onChange(it.ifBlank { null }) }, placeholder = setting.hint, enabled = enabled,
				modifier = Modifier.weight(1f), height = 22.dp,
			)
		}
	}
}

private fun format(setting: TargetSetting.Number, value: Double): String =
	if (setting.decimals == 0) value.toLong().toString() else "%.${setting.decimals}f".format(java.util.Locale.ROOT, value)
