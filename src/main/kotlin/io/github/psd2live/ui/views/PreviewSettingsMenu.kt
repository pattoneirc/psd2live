package io.github.psd2live.ui.views

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.P2lrtPreviewSession
import io.github.psd2live.core.PreviewBackend
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.FloatingMenuRadio
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.texture.FloatingMenuSwitch

/**
 * The preview's settings, rising from its status pill: which runtime draws it, the p2lrt runtime's advanced
 * mode, and the frame rate cap (none by default: every display refresh).
 */
@Composable
internal fun PreviewSettingsMenu(
	expanded: Boolean,
	onDismiss: () -> Unit,
	backend: PreviewBackend,
	onBackend: (PreviewBackend) -> Unit,
	advanced: Boolean,
	frameRate: Int,
) {
	FloatingMenu(expanded, onDismiss, width = 248.dp, alignment = Alignment.BottomStart, rise = true) {
		FloatingMenuSection(tr("preview.menu.runtime"))
		FloatingMenuRadio(tr("preview.menu.cubism"), backend == PreviewBackend.CUBISM, { onBackend(PreviewBackend.CUBISM) },
			hint = tr("preview.menu.cubism.hint"))
		FloatingMenuRadio(tr("preview.menu.p2lrt"), backend == PreviewBackend.P2LRT, { onBackend(PreviewBackend.P2LRT) },
			enabled = P2lrtPreviewSession.runtimeAvailable,
			hint = tr(if (P2lrtPreviewSession.runtimeAvailable) "preview.menu.p2lrt.hint" else "preview.menu.p2lrt.missing"))
		FloatingMenuSwitch(tr("preview.menu.advanced"), advanced, { AppSettings.previewAdvanced = it },
			enabled = backend == PreviewBackend.P2LRT, hint = tr("preview.menu.advanced.hint"))
		FloatingMenuDivider()
		FloatingMenuSection(tr("preview.menu.frameRate"))
		for (rate in AppSettings.previewFrameRates) {
			FloatingMenuRadio(if (rate == 0) tr("preview.menu.frameRate.display") else tr("preview.menu.frameRate.fps", rate),
				frameRate == rate, { AppSettings.previewFrameRate = rate })
		}
	}
}
