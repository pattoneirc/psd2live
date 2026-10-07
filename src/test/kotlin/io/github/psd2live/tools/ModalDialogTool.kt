package io.github.psd2live.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.psd2live.core.TextureUpscaleConfig
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.ui.components.DrawOrderInputDialog
import io.github.psd2live.ui.components.HelpDialog
import io.github.psd2live.ui.components.RebuildMeshPromptDialog
import io.github.psd2live.ui.components.SettingsDialog
import io.github.psd2live.ui.components.TextureUpscaleDialog
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import java.io.File
import kotlin.test.Test

/** The app's modal dialogs on the shared frame: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ModalDialogTool'. */
class ModalDialogTool {
	@Test fun renderDialogs() {
		requireTools()
		val previousLanguage = I18n.currentLanguage
		val directory = output("modal-dialog")
		fun write(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
			val scene = ImageComposeScene(width, height, density = Density(1f)) {
				CompactToolTheme(colors = ToolColors.Dark) { content() }
			}
			try {
				scene.render().close()
				val rendered = scene.render(16_000_000)
				try { File(directory, "$name.png").writeBytes(requireNotNull(rendered.encodeToData()).bytes) } finally { rendered.close() }
			} finally { scene.close() }
		}
		try {
			I18n.setLanguage(AppLanguage.CHINESE, persist = false)
			write("settings", 1100, 760) { SettingsDialog(uiScale = 1f, fontScale = 1f, onUiScaleChange = {}, onFontScaleChange = {}, onResetDefaults = {}, onDismiss = {}) }
			write("help", 900, 760) { HelpDialog(onDismiss = {}) }
			write("upscale", 700, 760) { TextureUpscaleDialog(TextureUpscaleConfig(), isBusy = false, onDownloadStateChange = {}, onDismiss = {}, onApply = {}) }
			write("draw-order", 520, 420) {
				DrawOrderInputDialog("layer", "前发", 500f, 480f, isOverridden = true, onConfirm = {}, onReset = {}, onDismiss = {})
			}
			write("rebuild-mesh", 560, 360) { RebuildMeshPromptDialog("前发", onConfirmRebuild = {}, onKeepExisting = {}, onDismiss = {}) }
		} finally { I18n.setLanguage(previousLanguage, persist = false) }
	}
}
