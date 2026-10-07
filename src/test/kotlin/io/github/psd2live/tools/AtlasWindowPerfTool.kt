package io.github.psd2live.tools

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.render.CanvasRenderService
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.views.PSD2LiveApp
import io.github.psd2live.ui.views.texture.AtlasPageProbe
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test

/**
 * The texture workspace in the real editor window: what a tile move, a density change and pointer drags on the
 * atlas page cost, and whether a drag shows while it is in progress.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*AtlasWindowPerfTool'. Pointer input is dispatched to the window as AWT
 * events, so the real cursor stays where it is. Writes build/tools/atlas-window-perf/: report.txt (per phase the
 * frame pacing, when each busy flag fell, and the UI thread's hotspots) and screenshots mid drag.
 */
class AtlasWindowPerfTool {
	@Test fun profile() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val out = output("atlas-window-perf")
		val vm = PSD2LiveViewModel()
		val workspace = io.github.psd2live.ui.state.DesktopWorkspace(vm, out.resolve("workspace").toPath())
		vm.attachWorkspace(workspace)
		val frames = ConcurrentLinkedQueue<Long>()
		val window = AtomicReference<java.awt.Window?>()
		Thread {
			application(exitProcessOnExit = false) {
				Window(onCloseRequest = ::exitApplication, state = rememberWindowState(size = DpSize(1600.dp, 1000.dp)), undecorated = true) {
					window.set(this.window)
					LaunchedEffect(Unit) { while (true) withFrameNanos { frames += it } }
					PSD2LiveApp(viewModel = vm, window = this.window)
				}
			}
		}.apply { isDaemon = true; start() }
		fun waitFor(seconds: Int, what: String, condition: () -> Boolean) {
			val end = System.nanoTime() + seconds * 1_000_000_000L
			while (!condition()) { require(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(20) }
		}
		val report = StringBuilder()
		try {
			waitFor(60, "window") { window.get()?.isShowing == true }
			vm.openRecentFile(sample.path.toAbsolutePath().toString())
			waitFor(600, "model") { vm.state.value.previewModel != null && !vm.state.value.isBusy }
			SwingUtilities.invokeAndWait { vm.dismissStartScreen(); vm.openWorkspacePreset(WorkspacePreset.TEXTURE) }
			CanvasRenderService.ensureStarted()
			waitFor(30, "GPU renderer") { CanvasRenderService.status.value !is CanvasRenderService.Status.Starting }
			report.appendLine("GPU: ${CanvasRenderService.status.value}")
			Thread.sleep(2500)
			val win = window.get()!!
			/** The window's own last frame, as Skia drew it: never the screen, which may show other windows. */
			fun capture(name: String) {
				fun layer(c: java.awt.Component): org.jetbrains.skiko.SkiaLayer? = c as? org.jetbrains.skiko.SkiaLayer
					?: (c as? java.awt.Container)?.components?.firstNotNullOfOrNull(::layer)
				val bitmap = layer(win)?.screenshot() ?: return
				val image = org.jetbrains.skia.Image.makeFromBitmap(bitmap)
				image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes?.let { File(out, "$name.png").writeBytes(it) }
			}
			val scale = win.graphicsConfiguration.defaultTransform.scaleX.toFloat()
			val snapshot = requireNotNull(vm.textureSnapshot())
			val tile = snapshot.atlas.tiles.filter { it.page == 0 }.maxBy { it.width * it.height }
			SwingUtilities.invokeAndWait { vm.selectLayer(tile.layerId) }
			Thread.sleep(800)
			report.appendLine("tile ${tile.layerId} ${tile.width}x${tile.height} at (${tile.x}, ${tile.y}); ${snapshot.atlas.tiles.size} tiles")

			/** Window coordinates (AWT, logical pixels) of page pixel ([x], [y]), as the page shows now. */
			fun onWindow(x: Float, y: Float): java.awt.Point {
				val origin = AtlasPageProbe.origin; val camera = AtlasPageProbe.camera
				return java.awt.Point(((origin.x + camera[0] + x * camera[2]) / scale).toInt(), ((origin.y + camera[1] + y * camera[2]) / scale).toInt())
			}
			var buttons = 0
			fun mouse(id: Int, at: java.awt.Point, button: Int = MouseEvent.NOBUTTON) {
				SwingUtilities.invokeAndWait {
					val target = SwingUtilities.getDeepestComponentAt(win, at.x, at.y) ?: return@invokeAndWait
					val p = SwingUtilities.convertPoint(win, at, target)
					if (id == MouseEvent.MOUSE_PRESSED) buttons = InputEvent.BUTTON1_DOWN_MASK
					val modifiers = if (id == MouseEvent.MOUSE_RELEASED) InputEvent.BUTTON1_DOWN_MASK else buttons
					target.dispatchEvent(MouseEvent(target, id, System.currentTimeMillis(), modifiers, p.x, p.y, 1, false, button))
					if (id == MouseEvent.MOUSE_RELEASED) buttons = 0
				}
			}

			/** Flags of the application state, sampled until [seconds] pass, as (ms, flags) whenever they change. */
			fun timeline(seconds: Double, during: () -> Unit): List<Pair<Double, String>> {
				val start = System.nanoTime()
				val changes = ArrayList<Pair<Double, String>>()
				var last = ""
				val sampler = Thread {
					while (System.nanoTime() - start < seconds * 1e9) {
						val s = vm.state.value
						val flags = listOfNotNull("texture.busy".takeIf { s.textureWorkspace.busy }, "generating".takeIf { s.isGenerating },
							"analyzing".takeIf { s.isAnalyzing }, "canvasEditBusy".takeIf { s.canvasEditBusy },
							"workspaceEditBusy".takeIf { s.workspaceEditBusy }, "editorDraftBusy".takeIf { s.editorDraftBusy },
							"model@${System.identityHashCode(s.previewModel).toString(16)}", "rev ${s.textureWorkspace.revision}").joinToString(" ")
						if (flags != last) { changes += (System.nanoTime() - start) / 1e6 to flags; last = flags }
						Thread.sleep(1)
					}
				}.apply { start() }
				during()
				sampler.join()
				return changes
			}

			fun phase(name: String, seconds: Double, shotAt: Double? = null, action: (Double) -> Unit) {
				val recording = jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")).apply {
					enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(2)); start()
				}
				frames.clear()
				val pings = ArrayList<Long>()
				var shot = false
				val changes = timeline(seconds + 2.5) {
					val start = System.nanoTime()
					var pingAt = start
					while (true) {
						val t = (System.nanoTime() - start) / 1e9
						if (t > seconds) break
						action(t)
						// Encoding a capture holds the UI thread for a while: only once the stats are taken, and only mid gesture.
						if (shotAt != null && !shot && t >= shotAt) shot = true
						if (System.nanoTime() >= pingAt) {
							val sent = System.nanoTime()
							SwingUtilities.invokeLater { synchronized(pings) { pings += System.nanoTime() - sent } }
							pingAt = sent + 5_000_000
						}
						Thread.sleep(8)
					}
				}
				recording.stop()
				val jfr = File(out, "$name.jfr")
				recording.dump(jfr.toPath()); recording.close()
				report.appendLine("    atlas lifted draws ${AtlasPageProbe.liftedDraws.getAndSet(0)}, with the GPU frame lifted ${AtlasPageProbe.liftedGpuFrames.getAndSet(0)}; " +
					"GPU frames per view ${CanvasRenderService.framesDrawn.mapValues { it.value.getAndSet(0) }}")
				val stamps = frames.toList().sorted()
				val gaps = stamps.zipWithNext { a, b -> (b - a) / 1e6 }.sorted()
				val latency = synchronized(pings) { pings.map { it / 1e6 }.sorted() }
				fun List<Double>.pct(p: Double) = if (isEmpty()) 0.0 else this[((size - 1) * p).toInt()]
				report.appendLine("== $name: %.1f fps | frame gap p50 %.1f p95 %.1f max %.1f ms | UI latency p50 %.1f p95 %.1f max %.1f ms".format(
					stamps.size / (seconds + 2.5), gaps.pct(0.5), gaps.pct(0.95), gaps.lastOrNull() ?: 0.0, latency.pct(0.5), latency.pct(0.95), latency.lastOrNull() ?: 0.0))
				for ((ms, flags) in changes) report.appendLine("    %7.1f ms  %s".format(ms, flags))
				report.append(hotspots(jfr))
				SwingUtilities.invokeAndWait { capture("$name-after") }
			}

			// A tile move committed straight through the view model: what one commit costs the application.
			phase("commit-move", 0.05) { t ->
				if (t == 0.0 || t < 0.01) SwingUtilities.invokeAndWait {
					val s = requireNotNull(vm.textureSnapshot())
					val at = s.tilesByLayer.getValue(tile.layerId)
					vm.draggedTextureTile(s, tile.layerId, at.x + 0f, at.y + 600f)?.let { vm.moveTextureTile(s, it) }; vm.applyTextureSession()
				}
			}
			Thread.sleep(1000)
			phase("commit-density", 0.05) { t ->
				if (t < 0.01) SwingUtilities.invokeAndWait {
					val s = requireNotNull(vm.textureSnapshot())
					vm.setTextureDensity(s, listOf(tile.layerId), 0.5f); vm.applyTextureSession()
				}
			}
			Thread.sleep(1500)

			// A real drag of the tile through the window: does it show while the pointer moves?
			val moving = requireNotNull(vm.textureSnapshot()).tilesByLayer.getValue(tile.layerId)
			val centre = onWindow(moving.x + moving.width / 2f, moving.y + moving.height / 2f)
			report.appendLine("drag from window point $centre (scale $scale, origin ${AtlasPageProbe.origin}, camera ${AtlasPageProbe.camera.toList()})")
			phase("pointer-tile-drag", 2.0, shotAt = 1.0) { t ->
				if (t < 0.01 && buttons == 0) { mouse(MouseEvent.MOUSE_MOVED, centre); mouse(MouseEvent.MOUSE_PRESSED, centre, MouseEvent.BUTTON1) }
				else mouse(MouseEvent.MOUSE_DRAGGED, java.awt.Point(centre.x + (t * 120).toInt(), centre.y + (t * 40).toInt()))
				if (t > 1.95 && buttons != 0) mouse(MouseEvent.MOUSE_RELEASED, java.awt.Point(centre.x + 234, centre.y + 78), MouseEvent.BUTTON1)
			}
			if (buttons != 0) mouse(MouseEvent.MOUSE_RELEASED, centre, MouseEvent.BUTTON1)
			Thread.sleep(1500)

			// A corner drag: the density preview while it grows.
			val grown = requireNotNull(vm.textureSnapshot()).tilesByLayer.getValue(tile.layerId)
			val corner = onWindow((grown.x + grown.width).toFloat(), (grown.y + grown.height).toFloat())
			phase("pointer-corner-drag", 2.0, shotAt = 1.0) { t ->
				if (t < 0.01 && buttons == 0) { mouse(MouseEvent.MOUSE_MOVED, corner); mouse(MouseEvent.MOUSE_PRESSED, corner, MouseEvent.BUTTON1) }
				else mouse(MouseEvent.MOUSE_DRAGGED, java.awt.Point(corner.x + (t * 40).toInt(), corner.y + (t * 40).toInt()))
				if (t > 1.95 && buttons != 0) mouse(MouseEvent.MOUSE_RELEASED, java.awt.Point(corner.x + 78, corner.y + 78), MouseEvent.BUTTON1)
			}
			if (buttons != 0) mouse(MouseEvent.MOUSE_RELEASED, corner, MouseEvent.BUTTON1)
			Thread.sleep(1000)
			// Six drags back to back, a quarter second each, none waiting for the one before to commit.
			val errors = ArrayList<String>()
			var lastTarget: java.awt.Point? = null
			phase("rapid-drags", 1.6) { t ->
				val round = (t / 0.25).toInt()
				if (round >= 6) return@phase
				val inRound = t - round * 0.25
				val shown = requireNotNull(vm.textureSnapshot()).tilesByLayer.getValue(tile.layerId)
					.let { committed -> vm.state.value.textureWorkspace.shown[tile.layerId]?.let { committed.copy(x = it.x, y = it.y, width = it.width, height = it.height) } ?: committed }
				if (buttons == 0 && inRound < 0.15) {
					val start = onWindow(shown.x + shown.width / 2f, shown.y + shown.height / 2f)
					lastTarget = java.awt.Point(start.x + if (round % 2 == 0) 30 else -30, start.y + 12)
					mouse(MouseEvent.MOUSE_MOVED, start); mouse(MouseEvent.MOUSE_PRESSED, start, MouseEvent.BUTTON1)
					mouse(MouseEvent.MOUSE_DRAGGED, java.awt.Point(start.x + if (round % 2 == 0) 15 else -15, start.y + 6))
				} else if (buttons != 0 && inRound >= 0.15) {
					mouse(MouseEvent.MOUSE_DRAGGED, lastTarget!!)
					mouse(MouseEvent.MOUSE_RELEASED, lastTarget!!, MouseEvent.BUTTON1)
					vm.state.value.textureWorkspace.error?.let { errors += it }
				}
			}
			if (buttons != 0) mouse(MouseEvent.MOUSE_RELEASED, lastTarget!!, MouseEvent.BUTTON1)
			kotlinx.coroutines.runBlocking { vm.awaitTextureEdits() }
			report.appendLine("rapid drags: errors $errors; pending left ${vm.state.value.textureWorkspace.shown.keys}; " +
				"history head ${vm.state.value.historySnapshot?.headNodeId}")
		} finally {
			File(out, "report.txt").writeText(report.toString())
			println(report)
			runCatching { SwingUtilities.invokeAndWait { window.get()?.dispose() } }
			workspace.close()
			vm.close()
		}
	}

	/** Where the UI thread spent the phase: busiest project frames (inclusive) and methods (self). */
	private fun hotspots(file: File): String {
		val self = HashMap<String, Int>()
		val inclusive = HashMap<String, Int>()
		var total = 0
		jdk.jfr.consumer.RecordingFile(file.toPath()).use { events ->
			while (events.hasMoreEvents()) {
				val event = events.readEvent()
				if (event.eventType.name != "jdk.ExecutionSample") continue
				if (event.getThread("sampledThread")?.javaName?.startsWith("AWT-EventQueue") != true) continue
				total++
				val stack = event.stackTrace?.frames ?: continue
				stack.firstOrNull()?.let { f -> val k = "${f.method.type.name}.${f.method.name}"; self[k] = (self[k] ?: 0) + 1 }
				val seen = HashSet<String>()
				for (f in stack) {
					val type = f.method.type.name
					if (!type.startsWith("io.github.psd2live") && !type.startsWith("org.umamo")) continue
					val k = "${type.substringAfterLast('.')}.${f.method.name}"
					if (seen.add(k)) inclusive[k] = (inclusive[k] ?: 0) + 1
				}
			}
		}
		val text = StringBuilder("    UI thread samples $total (2 ms each)\n")
		inclusive.entries.sortedByDescending { it.value }.take(14).forEach { text.append("      ${it.value * 100 / maxOf(1, total)}% ${it.key}\n") }
		text.append("    self:\n")
		self.entries.sortedByDescending { it.value }.take(8).forEach { text.append("      ${it.value * 100 / maxOf(1, total)}% ${it.key}\n") }
		return text.toString()
	}
}
