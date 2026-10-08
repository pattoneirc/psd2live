package io.github.psd2live.tools

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.core.PreviewBackend
import io.github.psd2live.render.SkiaGpu
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.SidebarSide
import io.github.psd2live.ui.views.LocalAwtWindow
import io.github.psd2live.ui.views.PSD2LiveApp
import io.github.psd2live.ui.views.sidebars
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test

/**
 * The preview's frame rate in the real editor window while it plays. For each runtime, over PSD2LIVE_SECONDS
 * (default 5) of playback: the intervals between delivered frames, the window's frame period and the share of it
 * Compose takes (recomposition, layout, recording the draw), what else keeps the event thread busy, and how often
 * the document state changes. The rest of a frame is Skia drawing the recorded window on the GPU, swapping and
 * waiting for the display.
 *
 * - PSD2LIVE_WORKSPACE: a workspace preset (PREVIEW, ANIMATION, PHYSICS, EDIT, ...) or ID; default the opening one
 * - PSD2LIVE_BACKENDS: CUBISM,P2LRT (default both)
 * - PSD2LIVE_VSYNC=0: frames as fast as they render, so the period is the cost rather than a multiple of the refresh
 * - PSD2LIVE_ADAPTIVE=1: vsync stops waiting while frames miss the refresh (AdaptiveVsync)
 * - PSD2LIVE_GPU=1: Skia's flush of each frame on the CPU, and the frame's GPU time (timer queries)
 * - PSD2LIVE_REPLAY=1: the last frame replayed offscreen, the CPU and GPU time of drawing it, with and without MSAA
 * - PSD2LIVE_MSAA: samples of the window's multisampled target (MultisampledWindow, default 4; 0 draws without it)
 * - PSD2LIVE_PANELS=0: the side panels hidden, for the cost of the canvas alone
 * - PSD2LIVE_TRACE=1: compositions per second of each composable, the hottest first
 * - PSD2LIVE_JFR=1: a flight recording (`{backend}.jfr`) and the event thread's hottest methods
 * - PSD2LIVE_SVG=1: the window's last frame as SVG (`{backend}.svg`), every draw the GPU replays, to count them
 * - PSD2LIVE_INVALIDATIONS=1: where the draw and layout requests that make the window compositor replay come from
 * - PSD2LIVE_CAPTURE=1: after playback, pauses and writes the window as it draws (`{backend}-paused.png`), read from
 *   the GPU; PSD2LIVE_COMPOSITOR=0 (`psd2live.compositor=false`) draws without the window compositor, to compare
 *
 * Measured in the window's own frames and draws, never the screen.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*PreviewFpsTool'
 */
@OptIn(InternalComposeTracingApi::class)
class PreviewFpsTool {
	@Test fun fps() = try { run() } catch (t: Throwable) { t.printStackTrace(); throw t }

	private fun run() {
		requireTools()
		SkiaGpu.requestOpenGl()
		if (setting("PSD2LIVE_VSYNC", "1") == "0") System.setProperty("skiko.vsync.enabled", "false")
		if (setting("PSD2LIVE_ADAPTIVE", "0") == "1") System.setProperty("psd2live.adaptiveVsync", "true")
		System.getenv("PSD2LIVE_MSAA")?.let { System.setProperty("psd2live.msaa", it) }
		if (setting("PSD2LIVE_COMPOSITOR", "1") == "0") System.setProperty("psd2live.compositor", "false")
		val sample = Sample.fromEnvironment()
		val seconds = setting("PSD2LIVE_SECONDS", "5").toInt()
		val out = output("preview-fps")
		val report = StringBuilder()
		Thread {
			Thread.sleep(300_000)
			File(out, "report.txt").writeText("$report\n--- watchdog: stuck")
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
		try {
			waitFor(60, "window") { window.get()?.isShowing == true }
			waitFor(30, "GPU") { SkiaGpu.status.value !is SkiaGpu.Status.Starting }
			val display = window.get()!!.graphicsConfiguration.device.displayMode
			report.appendLine("GPU: ${SkiaGpu.status.value}; display ${display.width}x${display.height} @ ${display.refreshRate} Hz")
			vm.openRecentFile(sample.path.toAbsolutePath().toString())
			waitFor(600, "model") { vm.state.value.previewModel != null && !vm.state.value.isBusy }
			SwingUtilities.invokeAndWait { vm.dismissStartScreen() }
			System.getenv("PSD2LIVE_WORKSPACE")?.takeIf { it.isNotBlank() }?.let { name ->
				SwingUtilities.invokeAndWait {
					vm.setActiveWorkspace(vm.state.value.workspaces.firstOrNull { it.id == name || it.preset.name.equals(name, true) }?.id ?: name)
				}
				Thread.sleep(1000)
			}
			if (setting("PSD2LIVE_PANELS", "1") == "0") SwingUtilities.invokeAndWait {
				for (side in SidebarSide.entries) {
					val workspace = vm.state.value.activeWorkspace
					if (workspace.sidebars()[side].orEmpty().any { it !in workspace.hiddenModules }) vm.toggleSidebar(side)
				}
			}
			report.appendLine("workspace ${vm.state.value.activeWorkspace.id}, hidden ${vm.state.value.activeWorkspace.hiddenModules}")
			val timing = RenderTiming()
			SwingUtilities.invokeAndWait { timing.install(window.get()!!) }
			val events = EventTiming().also { java.awt.Toolkit.getDefaultToolkit().systemEventQueue.push(it) }
			SwingUtilities.invokeAndWait {
				vm.setCanvasMode(vm.state.value.activeCanvas.id, CanvasMode.PREVIEW)
				vm.setAnimationEnabled(true)
			}
			val backends = setting("PSD2LIVE_BACKENDS", PreviewBackend.entries.joinToString(",") { it.name })
				.split(',').map { PreviewBackend.valueOf(it.trim()) }
			for (backend in backends) {
				SwingUtilities.invokeAndWait { vm.selectPreviewBackend(backend) }
				val key = vm.canvasRenderKey(vm.state.value.activeCanvas.id, CanvasMode.PREVIEW)
				val flow = vm.sdkFrameFor(key)
				runCatching { waitFor(60, "$backend frames") { flow.value?.backend == backend } }
					.onFailure { report.appendLine("$backend: ${it.message}; status ${vm.state.value.sdkStatus}"); continue }
				Thread.sleep(1500)
				val recording = if (setting("PSD2LIVE_JFR", "0") == "1") jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")).apply {
					enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1))
					enable("jdk.NativeMethodSample").withPeriod(java.time.Duration.ofMillis(1))
					start()
				} else null
				val compositions = ConcurrentHashMap<String, Int>()
				if (setting("PSD2LIVE_TRACE", "0") == "1") Composer.setTracer(object : CompositionTracer {
					override fun isTraceInProgress() = true
					override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) { compositions.merge(info.substringBefore(" ("), 1, Int::plus) }
					override fun traceEventEnd() {}
				})
				timing.reset()
				events.reset()
				val composedBefore = SkiaGpu.primary?.compositorFrames ?: (0L to 0L)
				val invalidations = ConcurrentHashMap<String, Int>()
				if (setting("PSD2LIVE_INVALIDATIONS", "0") == "1") io.github.psd2live.render.WindowCompositor.onInvalidate = {
					val site = Thread.currentThread().stackTrace.drop(3)
						.filter { !it.className.startsWith("java.") && !it.className.startsWith("kotlin.") && !it.className.contains("WindowCompositor") }
						.map { "${it.className.substringAfterLast('.')}.${it.methodName}" }
						.fold(ArrayList<String>()) { acc, name -> if (acc.lastOrNull() != name) acc += name; acc }
						.filter { !it.startsWith("NodeCoordinator.") && !it.startsWith("RootNodeOwner") && !it.startsWith("SnapshotInvalidationTracker") && !it.startsWith("GraphicsLayerOwnerLayer") }
						.take(16).joinToString(" < ")
					invalidations.merge(site, 1, Int::plus)
				}
				// Spinning rather than sleeping: a frame every 4 ms needs finer stamps than Thread.sleep(1) gives on Windows.
				val stamps = ArrayList<Long>()
				var lastFrame: Any? = null
				var lastState: Any? = null
				var states = 0
				val start = System.nanoTime()
				while (System.nanoTime() - start < seconds * 1_000_000_000L) {
					val frame = flow.value
					if (frame != null && frame !== lastFrame) { lastFrame = frame; stamps += System.nanoTime() }
					val state = vm.state.value
					if (state !== lastState) { lastState = state; states++ }
					Thread.onSpinWait()
				}
				Composer.setTracer(null)
				val jfrFile = File(out, "${backend.name.lowercase()}.jfr")
				recording?.apply { stop(); dump(jfrFile.toPath()); close() }
				val intervals = stamps.zipWithNext { a, b -> (b - a) / 1e6 }.sorted()
				fun pct(p: Double) = intervals.getOrNull(((intervals.size - 1) * p).toInt()) ?: 0.0
				val fps = if (stamps.size > 1) (stamps.size - 1) / ((stamps.last() - stamps.first()) / 1e9) else 0.0
				report.appendLine("$backend: %.1f fps over %d frames; interval ms p50 %.2f p90 %.2f p99 %.2f max %.2f"
					.format(fps, stamps.size, pct(0.5), pct(0.9), pct(0.99), intervals.lastOrNull() ?: 0.0))
				report.appendLine("  " + timing.summary())
				if (setting("PSD2LIVE_REPLAY", "0") == "1") report.append(timing.replay())
				report.appendLine("  document state changes %.1f/s".format(states / seconds.toDouble()))
				val composed = SkiaGpu.primary?.compositorFrames ?: (0L to 0L)
				report.appendLine("  window compositor: %d frames, Compose picture replayed in %d".format(
					composed.first - composedBefore.first, composed.second - composedBefore.second))
				io.github.psd2live.render.WindowCompositor.onInvalidate = null
				invalidations.entries.sortedByDescending { it.value }.take(12)
					.forEach { report.appendLine("    %6.1f/s  %s".format(it.value / seconds.toDouble(), it.key)) }
				report.append(events.summary(seconds))
				if (compositions.isNotEmpty()) {
					report.appendLine("  compositions per second:")
					compositions.entries.sortedByDescending { it.value }.take(40)
						.forEach { report.appendLine("    %8.1f  %s".format(it.value / seconds.toDouble(), it.key)) }
				}
				if (recording != null) report.append(hotMethods(jfrFile))
				if (setting("PSD2LIVE_CAPTURE", "0") == "1") {
					SwingUtilities.invokeAndWait { vm.setAnimationEnabled(false) }
					Thread.sleep(2500)
					report.appendLine("  " + capture(window.get()!!, File(out, "${backend.name.lowercase()}-paused.png")))
					SwingUtilities.invokeAndWait { vm.setAnimationEnabled(true) }
					Thread.sleep(1000)
				}
				if (setting("PSD2LIVE_SVG", "0") == "1") SwingUtilities.invokeAndWait {
					report.appendLine("  " + dumpSvg(window.get()!!, File(out, "${backend.name.lowercase()}.svg")))
				}
			}
		} finally {
			File(out, "report.txt").writeText(report.toString())
			println(report)
			SwingUtilities.invokeLater { window.get()?.dispose() }
		}
	}

	private fun layerOf(window: java.awt.Window): SkiaLayer? {
		fun find(c: java.awt.Component): SkiaLayer? =
			c as? SkiaLayer ?: (c as? java.awt.Container)?.components?.firstNotNullOfOrNull(::find)
		return find(window)
	}

	/** Times the window's frames: the period between them, and Compose's part of each (recompose, layout, record). */
	private inner class RenderTiming {
		private val render = ConcurrentLinkedQueue<Long>()
		private val periods = ConcurrentLinkedQueue<Long>()
		private val flushes = ConcurrentLinkedQueue<Long>()
		private val gpu = ConcurrentLinkedQueue<Long>()
		private val gpuTiming = setting("PSD2LIVE_GPU", "0") == "1"
		private val queries = IntArray(4)
		private var query = 0
		private var free = 0
		@Volatile private var lastStart = 0L

		fun install(window: java.awt.Window) {
			val layer = layerOf(window) ?: return
			this.layer = layer
			val inner = layer.renderDelegate ?: return
			layer.renderDelegate = object : SkikoRenderDelegate {
				override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
					val start = System.nanoTime()
					if (lastStart != 0L) periods += start - lastStart
					lastStart = start
					val timed = gpuTiming && runCatching { org.lwjgl.opengl.GL.getCapabilities() }.isSuccess
					if (timed) {
						if (queries[0] == 0) org.lwjgl.opengl.GL15.glGenQueries(queries)
						// The query issued a few frames ago has its result by now.
						val old = queries[query]
						if (org.lwjgl.opengl.GL15.glGetQueryObjecti(old, org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE) != 0 && free >= queries.size)
							gpu += org.lwjgl.opengl.GL33.glGetQueryObjecti64(old, org.lwjgl.opengl.GL15.GL_QUERY_RESULT)
						org.lwjgl.opengl.GL15.glBeginQuery(0x88BF /* GL_TIME_ELAPSED */, old)
					}
					replayRequest?.let { request -> replayRequest = null; request.complete(runCatching { benchmark() }.getOrElse { "  replay failed: $it\n" }) }
					inner.onRender(canvas, width, height, nanoTime)
					val drawn = System.nanoTime()
					render += drawn - start
					if (timed) {
						SkiaGpu.primary?.directContext()?.flush()
						flushes += System.nanoTime() - drawn
						org.lwjgl.opengl.GL15.glEndQuery(0x88BF)
						query = (query + 1) % queries.size
						free++
					}
				}
			}
		}

		@Volatile private var replayRequest: java.util.concurrent.CompletableFuture<String>? = null
		private var layer: SkiaLayer? = null

		/** Replays the window's last frame offscreen on its GPU, for where the frame's time goes. */
		fun replay(): String {
			val request = java.util.concurrent.CompletableFuture<String>()
			replayRequest = request
			SwingUtilities.invokeLater { layer?.needRedraw() }
			return request.get(60, java.util.concurrent.TimeUnit.SECONDS)
		}

		/**
		 * The last recorded frame drawn into an offscreen target of the window's size, many times: the CPU time
		 * Skia takes to turn the picture into GPU work (draw), to send it (flush), and the GPU time left after
		 * (finish waits for it). Variants: multisampled targets, which let Skia draw paths with the GPU's own
		 * rasterizer instead of masks made on the CPU.
		 */
		private fun benchmark(): String {
			val layer = layer ?: return "  no layer\n"
			val holder = SkiaLayer::class.java.getDeclaredField("picture").apply { isAccessible = true }.get(layer) ?: return "  no frame\n"
			val picture = holder.javaClass.getMethod("getInstance").invoke(holder) as org.jetbrains.skia.Picture
			val context = SkiaGpu.primary?.directContext() ?: return "  no context\n"
			val bounds = picture.cullRect
			val w = bounds.width.toInt(); val h = bounds.height.toInt()
			val text = StringBuilder("  replay of the last frame (${w}x$h) offscreen, per frame:\n")
			for (samples in listOf(0, 4, 8)) {
				val surface = org.jetbrains.skia.Surface.makeRenderTarget(context, false, org.jetbrains.skia.ImageInfo.makeN32Premul(w, h), samples,
					org.jetbrains.skia.SurfaceOrigin.BOTTOM_LEFT, org.jetbrains.skia.SurfaceProps(), false)
				if (surface == null) { text.appendLine("    msaa $samples: no surface"); continue }
				val canvas = surface.canvas
				fun draw() { canvas.clear(0); canvas.drawPicture(picture) }
				repeat(20) { draw(); context.flush(surface); context.submit(false) }
				org.lwjgl.opengl.GL11.glFinish()
				val n = 60
				var record = 0L; var flush = 0L; var finish = 0L
				repeat(n) {
					val t0 = System.nanoTime()
					draw()
					val t1 = System.nanoTime()
					context.flush(surface); context.submit(false)
					val t2 = System.nanoTime()
					org.lwjgl.opengl.GL11.glFinish()
					val t3 = System.nanoTime()
					record += t1 - t0; flush += t2 - t1; finish += t3 - t2
				}
				text.appendLine("    msaa %d: draw %.2f ms, flush %.2f ms (CPU); GPU left after %.2f ms".format(samples, record / 1e6 / n, flush / 1e6 / n, finish / 1e6 / n))
				surface.close()
			}
			context.resetGLAll()
			return text.toString()
		}

		fun reset() { render.clear(); periods.clear(); flushes.clear(); gpu.clear(); lastStart = 0L }

		fun summary(): String {
			fun stats(name: String, values: Collection<Long>): String {
				val sorted = values.map { it / 1e6 }.sorted()
				if (sorted.isEmpty()) return "$name -"
				fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
				return "$name mean %.2f p50 %.2f p90 %.2f p99 %.2f".format(sorted.average(), pct(0.5), pct(0.9), pct(0.99))
			}
			return stats("window period ms", periods) + "; " + stats("compose ms", render) +
				(if (gpuTiming) "\n  " + stats("flush cpu ms", flushes) + "; " + stats("gpu ms", gpu) else "")
		}
	}

	/** The time the event thread spends per kind of event: its frames, and what else keeps it from the next one. */
	private class EventTiming : java.awt.EventQueue() {
		private val totals = ConcurrentHashMap<String, LongArray>()

		override fun dispatchEvent(event: java.awt.AWTEvent) {
			val start = System.nanoTime()
			try { super.dispatchEvent(event) } finally {
				val key = if (event is java.awt.event.InvocationEvent) event.toString().substringAfter("runnable=", "?")
					.substringBefore(',').substringBefore('@').substringBefore("/0x") else event.javaClass.simpleName
				val slot = totals.getOrPut(key) { LongArray(2) }
				synchronized(slot) { slot[0] += System.nanoTime() - start; slot[1]++ }
			}
		}

		fun reset() = totals.clear()

		fun summary(seconds: Int): String {
			val text = StringBuilder("  event thread busy %.0f%%\n".format(100.0 * totals.values.sumOf { it[0] } / (seconds * 1e9)))
			totals.entries.sortedByDescending { it.value[0] }.take(8).forEach { (key, v) ->
				text.appendLine("    %7.1f ms in %6d events, %6.3f ms each: %s".format(v[0] / 1e6, v[1], v[0] / 1e6 / v[1], key))
			}
			return text.toString()
		}
	}

	/**
	 * The window's last frame as it draws: Skiko's recording of it (with the compositor's cached picture and preview
	 * textures in it) replayed into a texture in the window's context and read back. Never the screen.
	 */
	private fun capture(window: java.awt.Window, file: File): String {
		val gpu = SkiaGpu.primary ?: return "no GPU"
		val layer = layerOf(window) ?: return "no layer"
		val done = java.util.concurrent.CompletableFuture<String>()
		gpu.post { resources ->
			done.complete(runCatching {
				val holder = SkiaLayer::class.java.getDeclaredField("picture").apply { isAccessible = true }.get(layer) ?: error("no frame")
				val picture = holder.javaClass.getMethod("getInstance").invoke(holder) as org.jetbrains.skia.Picture
				val w = picture.cullRect.width.toInt()
				val h = picture.cullRect.height.toInt()
				// A task runs in a turn, which changed GL state behind Skia's back.
				resources.skia.resetGLAll()
				val surface = org.jetbrains.skia.Surface.makeRenderTarget(resources.skia, false, org.jetbrains.skia.ImageInfo.makeN32Premul(w, h))!!
				surface.canvas.clear(0xFF000000.toInt())
				surface.canvas.drawPicture(picture)
				val bitmap = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(w, h) }
				check(surface.readPixels(bitmap, 0, 0)) { "readPixels failed" }
				val png = org.jetbrains.skia.Image.makeFromBitmap(bitmap).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG) ?: error("no PNG")
				file.writeBytes(png.bytes)
				surface.close()
				"captured ${w}x$h to ${file.name}"
			}.getOrElse { "capture failed: $it" })
		}
		return runCatching { done.get(10, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { "capture timed out" }
	}

	/** The window's last recorded frame played into Skia's SVG canvas; returns its element counts. */
	private fun dumpSvg(window: java.awt.Window, file: File): String {
		val layer = layerOf(window) ?: return "no layer"
		val holder = SkiaLayer::class.java.getDeclaredField("picture").apply { isAccessible = true }.get(layer) ?: return "no frame"
		val picture = holder.javaClass.getMethod("getInstance").invoke(holder) as org.jetbrains.skia.Picture
		val stream = org.jetbrains.skia.DynamicMemoryWStream()
		val canvas = org.jetbrains.skia.svg.SVGCanvas.make(picture.cullRect, stream, false, false)
		picture.playback(canvas)
		canvas.close()
		val bytes = ByteArray(stream.bytesWritten())
		stream.read(bytes, 0, bytes.size)
		file.writeBytes(bytes)
		val counts = Regex("<(path|text|rect|ellipse|image|clipPath)\\b").findAll(String(bytes)).groupingBy { it.groupValues[1] }.eachCount()
		return "draws in the last frame: $counts"
	}

	/** The share of samples on the event thread per method at the top of the stack, and anywhere on it. */
	private fun hotMethods(file: File): String {
		val self = HashMap<String, Int>()
		val total = HashMap<String, Int>()
		var samples = 0
		// Other threads: Java samples per thread, and their hottest methods (native samples include waits).
		val threads = HashMap<String, Int>()
		val otherSelf = HashMap<String, Int>()
		val otherTotal = HashMap<String, Int>()
		fun name(f: jdk.jfr.consumer.RecordedFrame) = "${f.method.type.name}.${f.method.name}"
		jdk.jfr.consumer.RecordingFile(file.toPath()).use { recording ->
			while (recording.hasMoreEvents()) {
				val event = recording.readEvent()
				if (event.eventType.name != "jdk.ExecutionSample" && event.eventType.name != "jdk.NativeMethodSample") continue
				val thread = event.getThread("sampledThread")?.javaName.orEmpty()
				if (!thread.startsWith("AWT-EventQueue")) {
					val frames = event.stackTrace?.frames ?: continue
					val kind = if (event.eventType.name == "jdk.ExecutionSample") "java" else "native"
					threads.merge("${thread.replace(Regex("\\d+"), "#")} [$kind]", 1, Int::plus)
					frames.firstOrNull()?.let { otherSelf.merge("[$kind] ${name(it)}", 1, Int::plus) }
					frames.map(::name).filter { it.startsWith("io.github.psd2live") }.toSet().forEach { otherTotal.merge(it, 1, Int::plus) }
					continue
				}
				val frames = event.stackTrace?.frames ?: continue
				samples++
				frames.firstOrNull()?.let { self.merge(name(it), 1, Int::plus) }
				frames.map(::name).toSet().forEach { total.merge(it, 1, Int::plus) }
			}
		}
		if (samples == 0) return "  no event thread samples\n"
		val text = StringBuilder("  event thread samples $samples, self:\n")
		self.entries.sortedByDescending { it.value }.take(20).forEach { text.appendLine("    %5.1f%%  %s".format(100.0 * it.value / samples, it.key)) }
		text.appendLine("  on the stack (psd2live, compose, skiko):")
		total.entries.filter { it.key.startsWith("io.github.psd2live") || it.key.startsWith("androidx.compose") || it.key.startsWith("org.jetbrains.skiko") }
			.sortedByDescending { it.value }.take(50).forEach { text.appendLine("    %5.1f%%  %s".format(100.0 * it.value / samples, it.key)) }
		text.appendLine("  other threads' samples (event thread: $samples):")
		threads.entries.sortedByDescending { it.value }.take(15).forEach { text.appendLine("    %6d  %s".format(it.value, it.key)) }
		text.appendLine("  other threads, self:")
		otherSelf.entries.sortedByDescending { it.value }.take(25).forEach { text.appendLine("    %6d  %s".format(it.value, it.key)) }
		text.appendLine("  other threads, psd2live on the stack:")
		otherTotal.entries.sortedByDescending { it.value }.take(25).forEach { text.appendLine("    %6d  %s".format(it.value, it.key)) }
		return text.toString()
	}
}
