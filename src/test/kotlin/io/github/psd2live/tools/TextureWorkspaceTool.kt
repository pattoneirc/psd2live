package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.WorkspaceTextureEdit
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.RigCanvasSupport
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.ui.SkiaRigPainter
import io.github.psd2live.ui.SourcePixelImages
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.texture.AtlasPageView
import io.github.psd2live.ui.views.texture.TextureInspectorPanel
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test

/**
 * The texture workspace's atlas page and layer panel, and the edit canvas in atlas and source pixels:
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*TextureWorkspaceTool'. Writes build/tools/texture-workspace/.
 */
class TextureWorkspaceTool {
	@TempDir lateinit var temporary: Path

	@OptIn(ExperimentalComposeUiApi::class)
	@Test fun renderWorkspace() = runBlocking<Unit> {
		requireTools()
		val directory = output("texture-workspace")
		// Page images are made off the UI thread: give them a moment, then draw the settled frame.
		fun write(name: String, width: Int, height: Int, content: @androidx.compose.runtime.Composable () -> Unit) =
			renderPng(File(directory, "$name.png"), width, height, frames = 6, settleMillis = 250, content = content)
		keepingLanguage {
			PSD2LiveViewModel().use { vm ->
				DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
					vm.attachWorkspace(workspace)
					workspace.importPsd(File("examples/tml/psd-input/tml.psd").absolutePath, true)
					// A spread of densities and a lock, then one arrangement by the meshes' footprints.
					val first = requireNotNull(vm.textureSnapshot())
					val bySize = first.atlas.tiles.sortedBy { it.width * it.height }
					val small = bySize.take(3).map { it.layerId }
					val large = bySize.takeLast(2).map { it.layerId }
					var state = first.state
					state = vm.commitTextureEditNow(state, WorkspaceTextureEdit.SetPixelDensity(small, 3f)).mutation.state!!
					state = vm.commitTextureEditNow(state, WorkspaceTextureEdit.SetPixelDensity(large, 0.5f, lock = true)).mutation.state!!
					vm.commitTextureEditNow(state, WorkspaceTextureEdit.Pack(byMesh = true))
					val selected = bySize[bySize.size / 2].layerId
					vm.selectLayer(selected)

					for (language in listOf(AppLanguage.CHINESE, AppLanguage.ENGLISH)) {
						I18n.setLanguage(language, persist = false)
						val tag = language.name.lowercase()
						write("$tag-workspace", 1100, 760) {
							Row(Modifier.fillMaxSize().background(ToolColors.Dark.windowBackground)) {
								Box(Modifier.width(620.dp).fillMaxHeight()) { AtlasPageView(vm.state.value, vm) }
								Box(Modifier.width(480.dp).fillMaxHeight()) { TextureInspectorPanel(vm.state.value, vm) }
							}
						}
					}
					I18n.setLanguage(AppLanguage.ENGLISH, persist = false)
					vm.setTextureHeatmap(true)
					write("en-atlas-heatmap", 620, 760) { AtlasPageView(vm.state.value, vm) }
					vm.setTextureHeatmap(false)
					// The inspector's density slider mid-drag shows the tile at the size it would get.
					vm.previewTextureDensity(mapOf(selected to 2f))
					write("en-atlas-density-preview", 1100, 760) {
						Row(Modifier.fillMaxSize().background(ToolColors.Dark.windowBackground)) {
							Box(Modifier.width(620.dp).fillMaxHeight()) { AtlasPageView(vm.state.value, vm) }
							Box(Modifier.width(480.dp).fillMaxHeight()) { TextureInspectorPanel(vm.state.value, vm) }
						}
					}
					vm.previewTextureDensity(emptyMap())
					vm.selectLayer(large.first()); vm.selectLayer(small.last(), additive = true)
					write("en-inspector-multi", 360, 640) { TextureInspectorPanel(vm.state.value, vm) }
					vm.selectLayer(null)
					write("en-inspector-none", 360, 400) { TextureInspectorPanel(vm.state.value, vm) }

					// The edit canvas's texture pass in atlas and source pixels, zoomed on a half-density layer.
					val model = requireNotNull(vm.state.value.previewModel)
					val geometry = RigCanvasSupport.evaluate(model)
					val layer = requireNotNull(vm.textureSnapshot()?.layer(large.first()))
					val rect = layer.canvasRect
					val side = 520
					val scale = side / maxOf(rect.width, rect.height).toDouble()
					val viewport = CanvasViewport(scale, -rect.left * scale, -rect.top * scale, model.analysis.source.widthPx.toFloat(),
						model.analysis.source.heightPx.toFloat())
					SkiaRigPainter(model.atlas).use { painter ->
						SourcePixelImages(model).use { sources ->
							for ((name, source) in listOf("canvas-atlas-pixels" to null, "canvas-source-pixels" to sources)) {
								Surface.makeRasterN32Premul(side, side).use { surface ->
									surface.canvas.clear(Color.makeRGB(48, 50, 54))
									painter.paint(surface.canvas, model, geometry, viewport, sources = source)
									surface.makeImageSnapshot().use { image ->
										File(directory, "$name.png").writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
									}
								}
							}
						}
					}
				}
			}
		}
	}
}
