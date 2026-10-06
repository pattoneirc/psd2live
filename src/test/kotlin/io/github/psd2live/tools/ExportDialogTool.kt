package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.WindowState
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.ui.components.AppTitleBar
import io.github.psd2live.ui.components.OtherFormatExportDialog
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import java.io.File
import kotlin.test.Test

/** The File menu and every export-as dialog: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ExportDialogTool'. */
class ExportDialogTool {
	@OptIn(ExperimentalComposeUiApi::class)
	@Test fun renderMenuAndDialogs() {
		requireTools()
		val previousLanguage = I18n.currentLanguage
		val preview = PSD2LivePipeline().buildPreview(File("examples/tml/psd-input/tml.psd").toPath())
		val directory = output("export-dialog")
		fun write(name: String, scene: ImageComposeScene) {
			try {
				val rendered = scene.render()
				try { File(directory, "$name.png").writeBytes(requireNotNull(rendered.encodeToData()).bytes) } finally { rendered.close() }
			} finally { scene.close() }
		}
		try {
			PSD2LiveViewModel().use { vm ->
				vm.setStateForTest(vm.state.value.copy(previewModel = preview, outputPath = "D:/output"))
				for (language in listOf(AppLanguage.CHINESE, AppLanguage.ENGLISH)) {
					I18n.setLanguage(language, persist = false)
					val tag = language.name.lowercase()
					// Submenus open on hover.
					for ((name, hover) in listOf("menu-import" to Offset(60f, 135f), "menu-export-as" to Offset(60f, 196f))) {
						val scene = ImageComposeScene(760, 640, density = Density(1f)) {
							CompactToolTheme(colors = ToolColors.Dark) {
								Box(Modifier.fillMaxSize().background(ToolColors.Dark.panelBackground)) {
									AppTitleBar(
										windowState = WindowState(), isBusy = false, hasInput = true, canOpenOutput = true,
										currentLanguage = language, onOpenPsd = {}, onOpenProject = {}, onSaveProject = {},
										onSaveProjectAs = {}, projectTitle = "tml", onReanalyze = {}, onReexportPsd = {}, onOpenOutput = {},
										onShowExport = {}, onClose = {}, onSetLanguage = {}, onShowAgentConnection = {},
										onShowTextureUpscale = {}, onShowHistory = {}, onShowAbout = {},
										tutorialMenuForce = "file",
									)
								}
							}
						}
						scene.render().close()
						scene.sendPointerEvent(PointerEventType.Move, hover)
						scene.render(16_000_000).close()
						write("$tag-$name", scene)
					}
					for (target in vm.otherExportTargets().filter { it.id !in io.github.psd2live.core.ExportService.experimental }) {
						vm.setStateForTest(vm.state.value.copy(otherExportTarget = target.id))
						write("$tag-${target.id}", ImageComposeScene(560, 520, density = Density(1f)) {
							CompactToolTheme(colors = ToolColors.Dark) {
								OtherFormatExportDialog(vm.state.value, vm, onChooseOutput = {})
							}
						})
					}
				}
			}
		} finally { I18n.setLanguage(previousLanguage, persist = false) }
	}
}
