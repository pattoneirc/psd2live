package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.Composable
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
import io.github.psd2live.ui.components.ExportDialog
import io.github.psd2live.ui.components.ExportPsdDialog
import io.github.psd2live.ui.components.ExportSuccessDialog
import io.github.psd2live.ui.state.ExportSuccess
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import java.io.File
import kotlin.test.Test

/** The File menu, every export dialog and the export-success dialog: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ExportDialogTool'. */
class ExportDialogTool {
	@OptIn(ExperimentalComposeUiApi::class)
	@Test fun renderMenuAndDialogs() {
		requireTools()
		val preview = PSD2LivePipeline().buildPreview(File("examples/tml/psd-input/tml.psd").toPath())
		val directory = output("export-dialog")
		fun write(name: String, width: Int, height: Int, colors: ToolColors = ToolColors.Dark, content: @Composable () -> Unit) =
			renderPng(File(directory, "$name.png"), width, height, colors = colors, frames = 0, content = content)
		keepingLanguage {
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
						writePng(scene, File(directory, "$tag-$name.png"), frames = 0, startNanos = 32_000_000)
					}
					for (target in vm.otherExportTargets().filter { it.id !in io.github.psd2live.core.ExportService.experimental }) {
						vm.setStateForTest(vm.state.value.copy(otherExportTarget = target.id))
						write("$tag-${target.id}", 560, 520) { OtherFormatExportDialog(vm.state.value, vm, onChooseOutput = {}) }
					}
					vm.setStateForTest(vm.state.value.copy(otherExportTarget = null, showExportDialog = true))
					write("$tag-cubism", 640, 760) { ExportDialog(vm.state.value, vm, onChooseOutput = {}, onDismiss = {}) }
					vm.setStateForTest(vm.state.value.copy(showExportDialog = false, analysis = preview.analysis, showExportPsdDialog = true))
					write("$tag-psd", 600, 480) { ExportPsdDialog(vm.state.value, vm) }
					vm.setStateForTest(vm.state.value.copy(showExportPsdDialog = false))
					val success = ExportSuccess(tr("export.other.success", tr("export.target.gif"), 3), "D:/output/tml-gif",
						tr("export.other.losses", 2), listOf("drop: physics are not simulated", "approximate: blend modes are flattened"))
					for ((name, colors) in listOf("success" to ToolColors.Dark, "success-light" to ToolColors.Light)) {
						write("$tag-$name", 560, 360, colors) { ExportSuccessDialog(success, onOpenFolder = {}, onDismiss = {}, onDontShowAgain = {}) }
					}
				}
			}
		}
	}
}
