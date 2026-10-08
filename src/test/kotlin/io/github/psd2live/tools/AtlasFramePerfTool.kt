package io.github.psd2live.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.texture.AtlasPageView
import io.github.psd2live.ui.views.texture.TextureInspectorPanel
import java.io.File
import java.nio.file.Path
import kotlin.test.Test

/**
 * Frame cost of the texture workspace's atlas page and layer panel under pointer input, headless, with the software
 * painter: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*AtlasFramePerfTool'. Each phase sends pointer events to an
 * [ImageComposeScene] and times every render (recomposition, layout and drawing on the UI thread). The GPU renderer
 * draws in a window's Skia OpenGL context, which a scene has not: AtlasWindowPerfTool and PreviewWindowTool measure it
 * in a real window. Writes build/tools/atlas-frame-perf/: the report, a frame mid corner drag (corner-drag.png), the
 * open edit session (session.png), the settled page (frame.png) and a close zoom (zoomed.png).
 */
class AtlasFramePerfTool {
	@OptIn(ExperimentalComposeUiApi::class)
	@Test fun profile() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val out = output("atlas-frame-perf")
		out.listFiles()?.filter { it.extension == "png" }?.forEach { it.delete() }
		val report = StringBuilder()
		val savedSoftware = AppSettings.softwareCanvas
		AppSettings.softwareCanvas = true
		try { profile(sample, out, report) } finally { AppSettings.softwareCanvas = savedSoftware }
		File(out, "report.txt").writeText(report.toString())
		println(report)
	}

	@OptIn(ExperimentalComposeUiApi::class)
	private fun profile(sample: Sample, out: File, report: StringBuilder) {
		PSD2LiveViewModel().use { vm ->
			DesktopWorkspace(vm, Path.of(out.path, "workspace")).use { workspace ->
				vm.attachWorkspace(workspace)
				kotlinx.coroutines.runBlocking { workspace.importPsd(sample.path.toAbsolutePath().toString(), true) }
				val snapshot = requireNotNull(vm.textureSnapshot())
				val tile = snapshot.atlas.tiles.filter { it.page == 0 }.maxBy { it.width * it.height }
				vm.selectLayer(tile.layerId)
				val width = 1100; val height = 800; val pageWidth = 700
				val scene = ImageComposeScene(width, height, density = Density(1f)) {
					CompactToolTheme(colors = ToolColors.Dark) {
						val state by vm.state.collectAsState()
						Row(Modifier.fillMaxSize()) {
							Box(Modifier.width(pageWidth.dp).fillMaxHeight()) { AtlasPageView(state, vm) }
							Box(Modifier.width((width - pageWidth).dp).fillMaxHeight()) { TextureInspectorPanel(state, vm) }
						}
					}
				}
				var clock = 0L
				fun frame(): Double {
					clock += 16_000_000L
					val t = System.nanoTime()
					scene.render(clock).close()
					return (System.nanoTime() - t) / 1e6
				}
				/** A settled frame: images made off the UI thread have had time to land. */
				fun shot(name: String) {
					repeat(3) { frame(); Thread.sleep(60) }
					scene.render(clock).use { File(out, "$name.png").writeBytes(requireNotNull(it.encodeToData()).bytes) }
				}
				repeat(20) { frame(); Thread.sleep(50) }
				// The page as it fits the view: margin 24, centred.
				val page = snapshot.atlas.pages[0]
				val fit = minOf((pageWidth - 48f) / page.width, (height - 48f) / page.height)
				val left = (pageWidth - page.width * fit) / 2f; val top = (height - page.height * fit) / 2f
				fun view(x: Float, y: Float) = Offset(left + x * fit, top + y * fit)
				fun phase(name: String, steps: Int, input: (Int) -> Unit) {
					val times = (0 until steps).map { input(it); frame() }.sorted()
					report.appendLine("%-22s frames %3d  median %6.2f ms  p90 %6.2f ms  max %6.2f ms".format(name, times.size,
						times[times.size / 2], times[times.size * 9 / 10], times.last()))
				}
				val centre = view(tile.x + tile.width / 2f, tile.y + tile.height / 2f)
				phase("idle", 120) { }
				phase("hover", 200) { scene.sendPointerEvent(PointerEventType.Move, view(it * 30f % page.width, (it * 53f) % page.height)) }
				val primary = PointerButtons(isPrimaryPressed = true)
				val corner = view((tile.x + tile.width).toFloat(), (tile.y + tile.height).toFloat())
				phase("corner drag", 60) {
					when (it) {
						0 -> { scene.sendPointerEvent(PointerEventType.Move, corner); scene.sendPointerEvent(PointerEventType.Press, corner, buttons = primary, button = PointerButton.Primary) }
						59 -> scene.sendPointerEvent(PointerEventType.Move, corner, buttons = primary)
						else -> scene.sendPointerEvent(PointerEventType.Move, corner + Offset(it * 1.5f, it * 1.5f), buttons = primary)
					}
				}
				// Mid-gesture: the grown tiles show at their new size.
				scene.sendPointerEvent(PointerEventType.Move, corner + Offset(60f, 60f), buttons = primary)
				shot("corner-drag")
				scene.sendPointerEvent(PointerEventType.Release, corner, button = PointerButton.Primary)
				// The released drag sits in the edit session; a turn joins it, then the session bar shows both.
				requireNotNull(vm.textureSnapshot()).let { s -> vm.draggedTextureTile(s, tile.layerId, 1500f, 1500f)?.let { vm.moveTextureTile(s, it) } }
				vm.rotateTextureTile(requireNotNull(vm.textureSnapshot()), tile.layerId, 20f)
				shot("session")
				phase("wheel zoom", 30) { scene.sendPointerEvent(PointerEventType.Scroll, centre, scrollDelta = Offset(0f, if (it < 15) -1f else 1f)) }
				phase("tile drag", 60) {
					when (it) {
						0 -> { scene.sendPointerEvent(PointerEventType.Move, centre); scene.sendPointerEvent(PointerEventType.Press, centre, buttons = primary, button = PointerButton.Primary) }
						59 -> scene.sendPointerEvent(PointerEventType.Release, centre + Offset(0f, 0f), button = PointerButton.Primary)
						else -> scene.sendPointerEvent(PointerEventType.Move, centre + Offset((it % 20) * 3f, (it % 20) * 2f), buttons = primary)
					}
				}
				vm.setTextureShowMeshes(false)
				phase("hover, no meshes", 200) { scene.sendPointerEvent(PointerEventType.Move, view(it * 30f % page.width, (it * 53f) % page.height)) }
				vm.setTextureShowMeshes(true)
				shot("frame")
				// Zoomed in on the selected tile: texels one by one.
				repeat(12) { scene.sendPointerEvent(PointerEventType.Scroll, centre, scrollDelta = Offset(0f, -1f)) }
				shot("zoomed")
				scene.close()
			}
		}
	}
}
