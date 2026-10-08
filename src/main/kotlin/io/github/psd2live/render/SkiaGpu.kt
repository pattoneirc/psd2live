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
import kotlin.coroutines.resume

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
		// The window's HDC, to make its context current outside Skiko's own draw (Windows only).
		val device = if (isLinux) 0L else runCatching { type.getDeclaredField("device").apply { isAccessible = true }.getLong(redrawer) }.getOrDefault(0L)
		return WindowGpu(layer, redrawer, context, device, handler, field, if (isLinux) Platform.GLX else Platform.WGL)
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
	/** The window's `HDC` on Windows, else 0. */
	private val device: Long,
	private val handler: Any,
	private val contextField: Field,
	private val platform: SkiaGpu.Platform,
) {
	private val tasks = ConcurrentLinkedQueue<(GpuResources) -> Unit>()
	private var installed: SkikoRenderDelegate? = null
	private var compositor: WindowCompositor? = null
	private val frameWaiters = ArrayList<kotlinx.coroutines.CancellableContinuation<Long>>()
	private val _composited = androidx.compose.runtime.mutableStateOf(false)

	/**
	 * Whether the window draws its Compose picture over its [CompositedView]s ([WindowCompositor]): a preview canvas
	 * then leaves a hole for its view instead of drawing its frame. Snapshot state, read where canvases draw, so they
	 * draw again when it changes.
	 */
	val composited: androidx.compose.runtime.State<Boolean> get() = _composited

	/** Whether the frame being rendered composites now, read from inside a canvas's draw. */
	val compositing: Boolean get() = compositor?.active == true

	/** The compositor's frames and the frames its picture was replayed in, for the development tools. */
	val compositorFrames: Pair<Long, Long> get() = compositor?.let { it.frames.get() to it.pictureFrames.get() } ?: (0L to 0L)

	internal val pixelGeometry: org.jetbrains.skia.PixelGeometry? get() = runCatching { layer.pixelGeometry }.getOrNull()
	internal val contentScale: Float get() = runCatching { layer.contentScale }.getOrDefault(1f)
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

	/** Shows [view] under the window's picture until [hideView]; see [CompositedView]. */
	fun showView(viewId: String, view: CompositedView) { compositor?.show(viewId, view) }

	fun hideView(viewId: String) { compositor?.hide(viewId) }

	/**
	 * Suspends until the window next renders and returns its frame time, asking for that frame. A preview's frame
	 * pump waits here rather than on Compose's frame clock: a waiter there makes Compose draw the whole window
	 * every frame. Resumed at the start of the frame, before its GPU tasks run, so a caller on an immediate EDT
	 * dispatcher requests its preview frame in time for this one.
	 */
	suspend fun awaitFrame(): Long = kotlinx.coroutines.suspendCancellableCoroutine { waiter ->
		synchronized(frameWaiters) { frameWaiters += waiter }
		waiter.invokeOnCancellation { synchronized(frameWaiters) { frameWaiters -= waiter } }
		javax.swing.SwingUtilities.invokeLater { ensureInstalled(); requestRedraw() }
	}

	private fun resumeFrameWaiters(nanoTime: Long) {
		val ready = synchronized(frameWaiters) {
			if (frameWaiters.isEmpty()) return
			ArrayList(frameWaiters).also { frameWaiters.clear() }
		}
		for (waiter in ready) if (waiter.isActive) runCatching { waiter.resume(nanoTime) }
	}

	/** Makes the window's context current for work before Skiko's own draw does; true when it is. */
	private fun makeCurrent(): Boolean {
		if (isCurrent()) return true
		if (platform != SkiaGpu.Platform.WGL || device == 0L) return false
		runCatching { wglMakeCurrent?.invoke(null, device, glContext) }
		return isCurrent()
	}

	/** Runs posted tasks at the start of every frame the window renders, then draws it through the compositor. */
	private fun ensureInstalled() {
		val current = layer.renderDelegate ?: return
		if (current === installed) return
		val inner = current
		// The replaced compositor is not closed: its wrapper may be rendering now (this runs inside a frame's turn).
		val composer = WindowCompositor(this, inner)
		compositor = composer
		val wrapper = object : SkikoRenderDelegate {
			override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
				// Replaced by a wrapper around this one (another delegate wrapped it meanwhile): that one does the work.
				if (compositor !== composer) return inner.onRender(canvas, width, height, nanoTime)
				resumeFrameWaiters(nanoTime)
				if (tasks.isNotEmpty()) turn { }
				if (compositor !== composer) return inner.onRender(canvas, width, height, nanoTime)
				val skia = if (COMPOSITING && makeCurrent()) directContext() else null
				val composited = skia != null && runCatching { composer.render(canvas, width, height, nanoTime, skia) }
					.onFailure { System.err.println("Window compositor failed: $it") }.getOrDefault(false)
				if (!composited) inner.onRender(canvas, width, height, nanoTime)
				if (composited != _composited.value) {
					_composited.value = composited
					composer.invalidate()
				}
			}
		}
		installed = wrapper
		layer.renderDelegate = wrapper
	}

	internal fun requestRedraw() {
		runCatching { layer.needRedraw() }
	}

	private companion object {
		/** `-Dpsd2live.compositor=false` draws windows as Compose does, for comparison and in case of trouble. */
		val COMPOSITING = System.getProperty("psd2live.compositor") != "false"

		/** Skiko's `makeCurrent(HDC, HGLRC)`. */
		val wglMakeCurrent: java.lang.reflect.Method? by lazy {
			runCatching {
				Class.forName("org.jetbrains.skiko.redrawer.WindowsOpenGLRedrawerKt")
					.getDeclaredMethod("access\$makeCurrent", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
					.apply { isAccessible = true }
			}.getOrNull()
		}
	}
}
