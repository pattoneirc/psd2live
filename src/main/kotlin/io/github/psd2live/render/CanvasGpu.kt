package io.github.psd2live.render

import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The editing canvases' and the texture atlas page's GPU drawing, inside the canvas's own draw: [draw] renders
 * the scene into the view's texture in Skia's context ([WindowGpu.turn]) and returns it for Compose to draw in
 * the same frame. Nothing is queued, read back or reprojected: the frame shown is the scene just built.
 *
 * A view whose scene key did not change draws its last texture without touching GL, so a hover or an overlay
 * redraw costs no GPU work.
 */
internal object CanvasGpu {
	private class Drawn(val key: List<Any?>, val window: WindowGpu)

	private val drawn = ConcurrentHashMap<String, Drawn>()
	/** Frames rendered per view since start, for the development tools. */
	val framesDrawn = ConcurrentHashMap<String, AtomicInteger>()

	/** Whether canvases in [window] can draw on the GPU. */
	fun available(window: Window?): Boolean = SkiaGpu.of(window) != null

	/**
	 * [viewId]'s frame for [key], rendering [scene] when the key changed; null when [window] has no GPU or is not
	 * the one rendering now (the canvas then paints in software).
	 */
	fun draw(window: Window?, viewId: String, key: List<Any?>, scene: () -> GpuScene): GpuFrame? {
		val gpu = SkiaGpu.of(window) ?: return null
		val last = drawn[viewId]
		if (last != null && last.window === gpu && last.key == key) gpu.resources.frame(viewId)?.let { return it }
		return gpu.turn { resources ->
			val built = scene()
			resources.render(viewId, built.width, built.height, bottomUp = false) {
				resources.canvasRenderer.draw(viewId, built)
			}?.also {
				drawn[viewId] = Drawn(key, gpu)
				framesDrawn.getOrPut(viewId) { AtomicInteger() }.incrementAndGet()
			}
		} ?: gpu.resources.frame(viewId)
	}

	/** Forgets [viewId] and frees its GPU objects when its window next renders. */
	fun release(viewId: String) {
		val last = drawn.remove(viewId) ?: return
		last.window.post { it.release(viewId) }
	}
}
