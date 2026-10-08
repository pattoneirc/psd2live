package io.github.psd2live.tools

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.core.PreviewBackend
import io.github.psd2live.render.CanvasGpu
import io.github.psd2live.render.SkiaGpu
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.views.LocalAwtWindow
import io.github.psd2live.ui.views.PSD2LiveApp
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test

/**
 * The canvases in the real editor window with Skia on OpenGL: the edit canvas's GPU frames, then for each preview
 * runtime whether its frames are drawn in the window's Skia context, how many arrive in two seconds of playback,
 * and each view's texture read from the GPU (build/tools/preview-window/{edit,cubism,p2lrt}.png, report.txt).
 * A watchdog dumps every thread into the report and halts the JVM if the window hangs for two minutes.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*PreviewWindowTool'
 */
class PreviewWindowTool {
	@Test fun preview() = try { run() } catch (t: Throwable) { t.printStackTrace(); throw t }

	private fun run() {
		requireTools()
		SkiaGpu.requestOpenGl()
		val sample = Sample.fromEnvironment()
		val out = output("preview-window")
		val report = StringBuilder()
		Thread {
			Thread.sleep(120_000)
			val dump = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
				"${thread.name} (${thread.state})\n" + stack.take(40).joinToString("\n") { "    at $it" }
			}
			File(out, "report.txt").writeText("$report\n--- watchdog: stuck, threads:\n$dump")
			Runtime.getRuntime().halt(3)
		}.apply { isDaemon = true; start() }
		val vm = PSD2LiveViewModel()
		vm.attachWorkspace(io.github.psd2live.ui.state.DesktopWorkspace(vm, out.resolve("workspace").toPath()))
		val window = AtomicReference<java.awt.Window?>()
		Thread {
			application(exitProcessOnExit = false) {
				Window(onCloseRequest = ::exitApplication, state = rememberWindowState(size = DpSize(1400.dp, 900.dp)), undecorated = true) {
					window.set(this.window)
					CompositionLocalProvider(LocalAwtWindow provides this.window) { PSD2LiveApp(viewModel = vm, window = this.window) }
				}
			}
		}.apply { isDaemon = true; start() }
		fun waitFor(seconds: Int, what: String, condition: () -> Boolean) {
			val end = System.nanoTime() + seconds * 1_000_000_000L
			while (!condition()) { require(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(20) }
		}
		/** [viewId]'s texture as the GPU holds it, read in a task of the main window; never the screen. */
		fun capture(name: String, viewId: String) {
			val done = java.util.concurrent.CompletableFuture<Triple<Int, Int, ByteArray>?>()
			val gpu = SkiaGpu.primary ?: return
			gpu.post { done.complete(it.pixels(viewId)) }
			val (w, h, rgba) = runCatching { done.get(10, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() ?: return
			val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
			for (i in 0 until w * h) {
				val a = rgba[i * 4 + 3].toInt() and 0xff
				fun c(k: Int) = if (a == 0) 0 else minOf(255, ((rgba[i * 4 + k].toInt() and 0xff) * 255 + a / 2) / a)
				image.setRGB(i % w, i / w, (a shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2))
			}
			javax.imageio.ImageIO.write(image, "png", File(out, "$name.png"))
		}
		try {
			waitFor(60, "window") { window.get()?.isShowing == true }
			waitFor(30, "GPU") { SkiaGpu.status.value !is SkiaGpu.Status.Starting }
			report.appendLine("GPU: ${SkiaGpu.status.value}")
			vm.openRecentFile(sample.path.toAbsolutePath().toString())
			waitFor(600, "model") { vm.state.value.previewModel != null && !vm.state.value.isBusy }
			SwingUtilities.invokeAndWait { vm.dismissStartScreen() }
			Thread.sleep(1500)
			report.appendLine("edit canvas GPU frames: ${CanvasGpu.framesDrawn.values.sumOf { it.get() }}")
			capture("edit", vm.canvasRenderKey(vm.state.value.activeCanvas.id, CanvasMode.EDIT))
			SwingUtilities.invokeAndWait {
				vm.setCanvasMode(vm.state.value.activeCanvas.id, CanvasMode.PREVIEW)
				vm.setAnimationEnabled(true)
			}
			for (backend in PreviewBackend.entries) {
				SwingUtilities.invokeAndWait { vm.selectPreviewBackend(backend) }
				val key = vm.canvasRenderKey(vm.state.value.activeCanvas.id, CanvasMode.PREVIEW)
				val flow = vm.sdkFrameFor(key)
				runCatching { waitFor(60, "$backend frames") { flow.value?.backend == backend } }
					.onFailure { report.appendLine("$backend: ${it.message}; status ${vm.state.value.sdkStatus}") }
				Thread.sleep(1000)
				val seen = HashSet<Any>()
				val start = System.nanoTime()
				while (System.nanoTime() - start < 2_000_000_000L) { flow.value?.let { seen += it }; Thread.sleep(1) }
				val texture = SkiaGpu.primary?.resources?.frame(key)
				report.appendLine("$backend: active ${vm.previewBackend.value}, ${seen.size} frames in 2 s, " +
					"texture ${texture?.let { "${it.width}x${it.height}" }}, status ${vm.state.value.sdkStatus}")
				capture(backend.name.lowercase(), key)
			}
			report.appendLine("log:")
			vm.state.value.logEntries.filter { it.tag == "Render" }.forEach { report.appendLine("  ${it.message}") }
		} finally {
			File(out, "report.txt").writeText(report.toString())
			println(report)
			SwingUtilities.invokeLater { window.get()?.dispose() }
		}
	}
}
