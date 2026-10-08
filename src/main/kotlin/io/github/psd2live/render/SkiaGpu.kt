package io.github.psd2live.render

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import java.awt.Container
import java.awt.Window
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The application's one GPU renderer lives in Skia's own OpenGL context: every canvas (editing, preview, texture
 * atlas) draws on the thread that renders the window, while that window's context is current, into textures
 * Skia owns ([GpuTarget]), and Compose draws those textures in the same frame. There is no second context, no
 * sharing between contexts, no pixel read back and nothing to synchronize.
 *
 * Skiko keeps the context private; [of] finds a window's `SkiaLayer` and reads its OpenGL redrawer's context
 * handle and the `DirectContext` that draws it. A window drawn otherwise (Direct3D, Metal, software) has no
 * [WindowGpu], and its canvases paint in software.
 */
object SkiaGpu {
	sealed interface Status {
		data object Starting : Status
		data class Ready(val description: String) : Status
		data class Unavailable(val reason: String) : Status
	}

	private val _status = MutableStateFlow<Status>(Status.Starting)
	/** Whether the main window renders with OpenGL, so the GPU renderer can draw in it. */
	val status: StateFlow<Status> = _status.asStateFlow()

	private val isWindows = System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)
	private val isLinux = System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)
	private val windows = WeakHashMap<Window, WindowGpu?>()

	private val _primary = MutableStateFlow<WindowGpu?>(null)
	/** The main window's GPU; runtimes whose state lives in one context (Cubism) draw there. */
	val primaryFlow: StateFlow<WindowGpu?> = _primary.asStateFlow()
	val primary: WindowGpu? get() = _primary.value

	/**
	 * Asks Skiko for OpenGL on Windows, where it would pick Direct3D. An explicit `skiko.renderApi` or
	 * `SKIKO_RENDER_API` is kept. Must run before the first window is created.
	 */
	fun requestOpenGl() {
		if (!isWindows) return
		if (System.getProperty("skiko.renderApi") != null || System.getenv("SKIKO_RENDER_API") != null) return
		System.setProperty("skiko.renderApi", "OPENGL")
	}

	internal fun refreshStatus(gpu: WindowGpu) {
		if (_status.value is Status.Ready) _status.value = Status.Ready(gpu.description)
	}

	private val primaryWaiters = ConcurrentLinkedQueue<(WindowGpu) -> Unit>()

	/** Runs [action] with the main window's GPU once it is captured (at once if it is). */
	fun whenPrimary(action: (WindowGpu) -> Unit) {
		primary?.let { action(it); return }
		primaryWaiters += action
		primary?.let { gpu -> while (true) { val next = primaryWaiters.poll() ?: break; next(gpu) } }
	}

	/** Makes [window] the main window once it draws; retries while Skiko is still creating its redrawer. */
	fun capturePrimary(window: Window) {
		fun attempt(remaining: Int) {
			val gpu = of(window)
			if (gpu != null) {
				_primary.value = gpu
				_status.value = Status.Ready(gpu.description)
				while (true) { val next = primaryWaiters.poll() ?: break; next(gpu) }
				return
			}
			if (remaining == 0) {
				_status.value = Status.Unavailable(failure(window))
				return
			}
			javax.swing.Timer(50) { attempt(remaining - 1) }.apply { isRepeats = false }.start()
		}
		javax.swing.SwingUtilities.invokeLater { attempt(40) }
	}

	/** [window]'s GPU, or null when it does not render with OpenGL (yet). */
	fun of(window: Window?): WindowGpu? {
		window ?: return null
		synchronized(windows) {
			windows[window]?.let { if (it.valid()) return it }
			val made = runCatching { resolve(window) }.getOrNull()
			if (made != null) windows[window] = made
			return made
		}
	}

	private fun failure(window: Window): String = runCatching {
		val layer = findLayer(window) ?: return "The window has no Skia layer"
		val redrawer = redrawerGetter?.invoke(layer) ?: return "The window has no redrawer yet"
		"The window draws with ${redrawer.javaClass.simpleName}"
	}.getOrElse { it.message ?: it.javaClass.simpleName }

	// --- reflection on Skiko ---

	private val redrawerGetter: Method? by lazy {
		runCatching { SkiaLayer::class.java.getMethod("getRedrawer\$skiko") }.getOrNull()
	}
	private val contextField: Field? by lazy {
		runCatching {
			Class.forName("org.jetbrains.skiko.context.ContextHandler").getDeclaredField("context").apply { isAccessible = true }
		}.getOrNull()
	}

	private fun resolve(window: Window): WindowGpu? {
		val layer = findLayer(window) ?: return null
		val redrawer = redrawerGetter?.invoke(layer) ?: return null
		val type = redrawer.javaClass
		if (type.simpleName != "WindowsOpenGLRedrawer" && type.simpleName != "LinuxOpenGLRedrawer") return null
		val context = type.getDeclaredField("context").apply { isAccessible = true }.getLong(redrawer)
		if (context == 0L) return null
		val handler = type.getDeclaredField("contextHandler").apply { isAccessible = true }.get(redrawer) ?: return null
		val field = contextField ?: return null
		return WindowGpu(layer, redrawer, context, handler, field, if (isLinux) Platform.GLX else Platform.WGL)
	}

	private fun findLayer(root: java.awt.Component): SkiaLayer? {
		if (root is SkiaLayer) return root
		if (root is Container) for (child in root.components) findLayer(child)?.let { return it }
		return null
	}

	internal enum class Platform { WGL, GLX }

	/** Whether the redrawer [layer] uses is still [redrawer]: Skiko replaces it when it falls back to another API. */
	internal fun sameRedrawer(layer: SkiaLayer, redrawer: Any): Boolean = runCatching { redrawerGetter?.invoke(layer) === redrawer }.getOrDefault(false)
}

/**
 * One window's Skia OpenGL context, as the GPU renderer uses it.
 *
 * GL work runs only on the window's render thread while its context is current: inside a canvas's draw
 * ([turn]) or, for work no canvas is drawing (loads, releases, a runtime's own bookkeeping), as [post]ed tasks
 * that run when the window next renders, before Compose draws, or when a canvas of the window [drain]s them.
 */
class WindowGpu internal constructor(
	private val layer: SkiaLayer,
	private val redrawer: Any,
	/** The `HGLRC` or `GLXContext` Skia draws with. */
	internal val glContext: Long,
	private val handler: Any,
	private val contextField: Field,
	private val platform: SkiaGpu.Platform,
) {
	private val tasks = ConcurrentLinkedQueue<(GpuResources) -> Unit>()
	private var installed: SkikoRenderDelegate? = null
	private var capabilities: org.lwjgl.opengl.GLCapabilities? = null
	/** What this context holds for the renderer; touched only during a turn. */
	internal val resources = GpuResources(this)
	@Volatile var description: String = "OpenGL"
		private set
	/** Whether Skia's context is a core profile, where renderers written for compatibility GL (Cubism's) cannot draw. */
	@Volatile var coreProfile: Boolean = false
		private set

	internal fun valid(): Boolean = SkiaGpu.sameRedrawer(layer, redrawer)

	/** The `DirectContext` that draws the window; null before its first frame. */
	internal fun directContext(): DirectContext? = runCatching { contextField.get(handler) as? DirectContext }.getOrNull()

	internal fun isCurrentContext(): Boolean = isCurrent()
	internal val pixelGeometry: org.jetbrains.skia.PixelGeometry? get() = runCatching { layer.pixelGeometry }.getOrNull()

	/** Whether this context is current on the calling thread, which is what lets GL calls reach it. */
	private fun isCurrent(): Boolean = when (platform) {
		SkiaGpu.Platform.WGL -> org.lwjgl.opengl.WGL.wglGetCurrentContext(null) == glContext
		SkiaGpu.Platform.GLX -> org.lwjgl.opengl.GLX.glXGetCurrentContext() == glContext
	}

	/**
	 * Runs [block] with the context current and hands Skia back a context whose state it must assume changed;
	 * null when the calling thread is not rendering this window (the block does not run).
	 */
	fun <T> turn(block: (GpuResources) -> T): T? {
		if (!isCurrent()) return null
		if (javax.swing.SwingUtilities.isEventDispatchThread()) ensureInstalled()
		val skia = directContext() ?: return null
		if (capabilities == null) {
			capabilities = GL.createCapabilities()
			val profile = GL11.glGetInteger(0x9126) // GL_CONTEXT_PROFILE_MASK: 1 core, 2 compatibility, 0 legacy
			coreProfile = profile and 1 != 0
			description = "${GL11.glGetString(GL11.GL_RENDERER)} | ${GL11.glGetString(GL11.GL_VERSION)}${if (coreProfile) " core" else ""}"
			if (this === SkiaGpu.primary) SkiaGpu.refreshStatus(this)
		} else {
			GL.setCapabilities(capabilities)
		}
		// Errors Skia or a driver left behind are not this turn's.
		repeat(8) { if (GL11.glGetError() == GL11.GL_NO_ERROR) return@repeat }
		try {
			resources.restoreDefaults()
			runTasks()
			return block(resources)
		} finally {
			resources.restoreDefaults()
			skia.resetGLAll()
		}
	}

	/** Runs the posted tasks now if this thread is rendering the window. */
	fun drain() {
		if (tasks.isNotEmpty()) turn { }
	}

	/** Queues [task] for the next turn and asks the window to render one; any thread. */
	fun post(task: (GpuResources) -> Unit) {
		tasks += task
		javax.swing.SwingUtilities.invokeLater { ensureInstalled(); requestRedraw() }
	}

	private fun runTasks() {
		while (true) {
			val task = tasks.poll() ?: return
			try {
				task(resources)
			} catch (failure: Throwable) {
				System.err.println("GPU task failed: $failure")
			}
		}
	}

	/** Runs posted tasks at the start of every frame the window renders, before Compose draws. */
	private fun ensureInstalled() {
		val current = layer.renderDelegate ?: return
		if (current === installed) return
		val inner = current
		val wrapper = object : SkikoRenderDelegate {
			override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
				pacing.frame(System.nanoTime())
				if (tasks.isNotEmpty()) turn { }
				if (!multisampled.render(canvas, width, height) { inner.onRender(it, width, height, nanoTime) })
					inner.onRender(canvas, width, height, nanoTime)
			}
		}
		installed = wrapper
		layer.renderDelegate = wrapper
	}

	internal fun requestRedraw() {
		runCatching { layer.needRedraw() }
	}

	private val pacing = AdaptiveVsync(layer, redrawer)
	private val multisampled = MultisampledWindow(this)
}

/**
 * Draws the window through a multisampled target. Skia turns a frame's draws into GPU work on the CPU, and without
 * MSAA it makes the coverage of every antialiased path and shape there too; with MSAA the GPU's own rasterizer does,
 * which halves that part of the frame. Skiko's window surface is single-sampled, so the frame Compose records is
 * replayed into a multisampled texture of the window's size and the window draws that texture: Skiko then replays
 * one image instead of the whole window. Drawing Compose straight into the texture instead of recording first is
 * far slower: its layers are Skiko render nodes, which flush poorly into a GPU canvas.
 *
 * Samples from `psd2live.msaa` (default 4; 0 or 1 keeps Skiko's own path).
 */
internal class MultisampledWindow(private val gpu: WindowGpu) {
	private val samples = System.getProperty("psd2live.msaa")?.toIntOrNull() ?: 4
	private var surface: org.jetbrains.skia.Surface? = null
	private var failed = false

	/** Records [draw] and draws it into [canvas] through the multisampled target; false when it did not. */
	fun render(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, draw: (org.jetbrains.skia.Canvas) -> Unit): Boolean {
		if (samples <= 1 || failed || width <= 0 || height <= 0) return false
		if (!gpu.isCurrentContext()) return false
		val context = gpu.directContext() ?: return false
		var surface = surface
		if (surface == null || surface.width != width || surface.height != height) {
			surface?.close()
			surface = org.jetbrains.skia.Surface.makeRenderTarget(context, false, org.jetbrains.skia.ImageInfo.makeN32Premul(width, height),
				samples, org.jetbrains.skia.SurfaceOrigin.TOP_LEFT, org.jetbrains.skia.SurfaceProps(pixelGeometry = gpu.pixelGeometry ?: org.jetbrains.skia.PixelGeometry.UNKNOWN), false)
			this.surface = surface
			if (surface == null) { failed = true; return false }
		}
		val picture = org.jetbrains.skia.PictureRecorder().use { recorder ->
			draw(recorder.beginRecording(org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat())))
			recorder.finishRecordingAsPicture()
		}
		// Last frame's image may still be held by Skiko's last picture: give the surface new pixels rather than copy them.
		surface.notifyContentWillChange(org.jetbrains.skia.ContentChangeMode.DISCARD)
		surface.canvas.clear(0)
		surface.canvas.drawPicture(picture)
		picture.close()
		surface.makeImageSnapshot().use { canvas.drawImage(it, 0f, 0f) }
		return true
	}
}

/**
 * Skiko presents with swap interval 0 and, with vsync on, waits after every frame until the desktop composes
 * (`DwmFlush`): a frame that takes a little longer than a refresh waits for the one after, so 240 Hz falls to
 * 120 FPS. While frames keep missing the refresh, this turns that wait off, so each frame is presented as soon as
 * it is drawn and the desktop shows the latest at every refresh (no tearing: the window is composed); once frames
 * fit a refresh again, the wait comes back and paces them to the display.
 */
internal class AdaptiveVsync(private val layer: SkiaLayer, redrawer: Any) {
	private val properties: Any? = runCatching {
		redrawer.javaClass.getDeclaredField("properties").apply { isAccessible = true }.get(redrawer)
	}.getOrNull()
	private val vsync: Field? = properties?.let {
		runCatching { it.javaClass.getDeclaredField("isVsyncEnabled").apply { isAccessible = true } }.getOrNull()
	}
	private val enabled = vsync != null && vsync.getBoolean(properties) &&
		System.getProperty("psd2live.adaptiveVsync") == "true"
	private var period = 0L
	private var periodCheckedAt = 0L
	private var last = 0L
	private var late = 0
	private var early = 0
	/** Whether frames are presented without waiting for the desktop. */
	@Volatile var free = false
		private set

	fun frame(now: Long) {
		if (!enabled) return
		if (now - periodCheckedAt > 1_000_000_000L) {
			periodCheckedAt = now
			val rate = runCatching { layer.graphicsConfiguration?.device?.displayMode?.refreshRate ?: 0 }.getOrDefault(0)
			period = if (rate > 0) 1_000_000_000L / rate else 0L
		}
		val interval = now - last
		last = now
		if (period == 0L || interval > period * 4) { late = 0; early = 0; return }
		if (!free) {
			// Waiting made this frame take two refreshes or more.
			if (interval > period * 3 / 2) late++ else late = 0
			if (late >= 3) set(true)
		} else {
			// Drawn faster than the display refreshes: waiting would no longer drop a refresh.
			if (interval < period * 17 / 20) early++ else early = 0
			if (early >= 8) set(false)
		}
	}

	private fun set(free: Boolean) {
		this.free = free
		late = 0
		early = 0
		runCatching { vsync!!.setBoolean(properties, !free) }
	}
}
