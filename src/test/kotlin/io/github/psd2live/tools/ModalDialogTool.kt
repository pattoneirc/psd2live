package io.github.psd2live.tools

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.TextureUpscaleConfig
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.project.WorkspaceProjectSnapshot
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.ui.components.DepthSplitDialog
import io.github.psd2live.ui.components.DrawOrderInputDialog
import io.github.psd2live.ui.components.HelpDialog
import io.github.psd2live.ui.components.RebuildMeshPromptDialog
import io.github.psd2live.ui.components.SettingsDialog
import io.github.psd2live.ui.components.TextureUpscaleDialog
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.ToolColors
import org.umamo.format.art.*
import java.io.File
import kotlin.test.Test

/**
 * The app's modal dialogs on the shared frame (ModalDialogFrame), in Chinese: PSD2LIVE_TOOLS=1 ./gradlew test --tests
 * '*ModalDialogTool'. [renderDialogs] writes the settings, help, texture upscale, draw order and rebuild mesh dialogs
 * in the dark theme to build/tools/modal-dialog/<dialog>.png; [depthSplit] the depth split dialog in the dark and light
 * themes and with large text to depth-split-{dark,light,large-text}.png.
 */
class ModalDialogTool {
	@Test fun renderDialogs() {
		requireTools()
		val directory = output("modal-dialog")
		keepingLanguage(AppLanguage.CHINESE) {
			renderPng(File(directory, "settings.png"), 1100, 760) {
				SettingsDialog(uiScale = 1f, fontScale = 1f, onUiScaleChange = {}, onFontScaleChange = {}, onResetDefaults = {}, onDismiss = {})
			}
			renderPng(File(directory, "help.png"), 900, 760) { HelpDialog(onDismiss = {}) }
			renderPng(File(directory, "upscale.png"), 700, 760) {
				TextureUpscaleDialog(TextureUpscaleConfig(), isBusy = false, onDownloadStateChange = {}, onDismiss = {}, onApply = {})
			}
			renderPng(File(directory, "draw-order.png"), 520, 420) {
				DrawOrderInputDialog("layer", "前发", 500f, 480f, isOverridden = true, onConfirm = {}, onReset = {}, onDismiss = {})
			}
			renderPng(File(directory, "rebuild-mesh.png"), 560, 360) {
				RebuildMeshPromptDialog("前发", onConfirmRebuild = {}, onKeepExisting = {}, onDismiss = {})
			}
		}
	}

	/** The depth split offer for a collar over a neck. */
	@Test fun depthSplit() {
		requireTools()
		val directory = output("modal-dialog")
		keepingLanguage(AppLanguage.CHINESE) {
			val bounds = LayerBounds(20, 20, 40, 40)
			fun layer(id: String, name: String, order: Int): WorkspaceSourceLayer = WorkspaceSourceLayer(
				LayerId(id), name, "", SourceLayerKind.Raster, true, order, bounds, 1f, false,
				LayerBlend.Normal, ChannelMask.ALL, LayerRaster(40, 40, ByteArray(40 * 40 * 4) { -1 }), null, null, false)
			val preview = PSD2LivePipeline().buildPreview(WorkspaceSourceArt(100, 100,
				listOf(layer("collar", "领子", 0), layer("neck", "脖子", 1)), emptyList()), PipelineConfig(atlasSize = 256))
			val ids = preview.rig.layerIdByDrawableId.entries.associate { it.value to it.key }
			val expected = WorkspaceProjectSnapshot("preview", "preview-revision", "preview-head", true,
				"preview", 100, 100, false, "ready", null, emptyList(), emptyList(), state = "preview-state")
			val offer = PSD2LiveViewModel.DepthSplitOffer(preview, ids.getValue("collar"), "preview", "preview", ids.getValue("neck"), expected)
			for ((name, colors, fontScale) in listOf(Triple("dark", ToolColors.Dark, 1f),
				Triple("light", ToolColors.Light, 1f), Triple("large-text", ToolColors.Dark, 1.35f))) {
				renderPng(File(directory, "depth-split-$name.png"), 720, 560, colors = colors, fontScale = fontScale) {
					DepthSplitDialog(offer, onConfirm = {}, onDismiss = {})
				}
			}
		}
	}
}
