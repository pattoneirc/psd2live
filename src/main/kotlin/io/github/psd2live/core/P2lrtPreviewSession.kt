package io.github.psd2live.core

import io.github.psd2live.format.eval.P2lRuntime
import io.github.psd2live.render.GpuResources
import io.github.psd2live.render.P2lrtGlRenderer
import io.github.psd2live.render.WindowGpu
import io.github.psd2live.targets.runtime.P2lrt
import org.umamo.runtime.model.ParameterId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * Previews the rig as the exported `.p2lrt` plays: PSD2Live's own runtime (`runtime/`, through [P2lRuntime])
 * evaluates the pose and [P2lrtGlRenderer] draws it, as a task of the view's window ([WindowGpu.post]) with its
 * Skia context current, straight into the view's texture there. The rig compiles on a thread of its own.
 *
 * Like the Cubism session, the workspace clock supplies the whole pose and the runtime only evaluates it. In
 * advanced mode each view's rig also steps its live simulation and skins along arcs (`p2l_set_advanced`); the
 * runtime's own physics then follows the same inputs as the editor's, and its idle behaviors stay off.
 */
class P2lrtPreviewSession(
	private val onFrame: (PreviewFrame) -> Unit,
	private val onStatus: (String?) -> Unit,
) : AutoCloseable {
	private class Loaded(val generation: Long, val bytes: ByteArray, val pages: List<java.awt.image.BufferedImage>)

	private class View(val rig: P2lRuntime.Rig, val gpu: WindowGpu, val generation: Long) {
		var advanced = -1
		/** The frame time of the last step, for the simulation's own clock. */
		var lastFrameNanos = 0L
	}

	/** One context's renderer and the rig generation its meshes and pages are uploaded for. */
	private class ContextRenderer(val renderer: P2lrtGlRenderer) : AutoCloseable {
		var generation = -1L
		override fun close() = renderer.close()
	}

	private val compiler = Executors.newSingleThreadExecutor { Thread(it, "p2lrt-preview-compile").apply { isDaemon = true } }
	private val latestRender = CanvasLatestQueue<PreviewRenderRequest>()
	private val renderScheduled = ConcurrentHashMap<WindowGpu, AtomicBoolean>()
	private val latestDelivery = CanvasLatestQueue<PreviewFrame>()
	private val deliveryScheduled = AtomicBoolean(false)
	private val pendingModel = AtomicReference<RigPreviewModel?>(null)
	private val compileScheduled = AtomicBoolean(false)
	private val lock = Any()
	@Volatile private var generation = 0L
	@Volatile private var loaded: Loaded? = null
	@Volatile private var closed = false
	private var runtime: P2lRuntime? = null
	private val views = HashMap<String, View>()
	private val lastRequests = HashMap<String, PreviewRenderRequest>()
	private val serviceKey = Any()

	/** How frames reach the window, for the log. */
	val renderPath: String get() = "texture"

	/** Compiles [model] to `.p2lrt` in the background and plays it; requests coalesce to the newest model. */
	fun load(model: RigPreviewModel) {
		if (closed) return
		generation++
		pendingModel.set(model)
		scheduleCompile()
	}

	private fun scheduleCompile() {
		if (closed || !compileScheduled.compareAndSet(false, true)) return
		try {
			compiler.execute(::drainCompiles)
		} catch (_: RejectedExecutionException) {
			compileScheduled.set(false)
		}
	}

	private fun drainCompiles() {
		try {
			while (!closed) {
				val model = pendingModel.getAndSet(null) ?: break
				val target = generation
				val result = runCatching {
					val rt = synchronized(lock) {
						runtime ?: (P2lRuntime.load() ?: error("The PSD2Live runtime library is not available")).also { runtime = it }
					}
					val bytes = P2lrt.write(RigIrCompiler.compile(model, simulations = true))
					rt.load(bytes).close() // validated here, off the render thread
					Loaded(target, bytes, model.atlas.pages.map { it.image })
				}
				result.onFailure { postStatus("compile p2lrt: ${it.message ?: it.javaClass.simpleName}") }
				val next = result.getOrNull() ?: continue
				if (target != generation) continue
				loaded = next
				postStatus("ready")
				// Every view renders its last request again with the new rig.
				val gpus = synchronized(lock) { lastRequests.values.mapNotNull { it.gpu }.toSet() }
				gpus.forEach(::scheduleRender)
			}
		} finally {
			compileScheduled.set(false)
			if (!closed && pendingModel.get() != null) scheduleCompile()
		}
	}

	fun render(request: PreviewRenderRequest) {
		if (closed || request.width <= 0 || request.height <= 0) return
		val gpu = request.gpu ?: return postStatus("The preview needs a window drawn with OpenGL")
		latestRender.put(request.viewId, request)
		synchronized(lock) { lastRequests[request.viewId] = request }
		scheduleRender(gpu)
	}

	private fun scheduleRender(gpu: WindowGpu) {
		val flag = renderScheduled.getOrPut(gpu) { AtomicBoolean(false) }
		if (closed || loaded == null || !flag.compareAndSet(false, true)) return
		gpu.post { resources -> try { drainRenders(gpu, resources) } finally { flag.set(false) } }
	}

	private fun drainRenders(gpu: WindowGpu, resources: GpuResources) {
		val current = loaded ?: return
		val others = ArrayList<PreviewRenderRequest>()
		val rendered = HashSet<String>()
		while (!closed) {
			val request = latestRender.poll() ?: break
			if (request.gpu !== gpu) { others += request; continue }
			renderFrame(current, resources, gpu, request)
			rendered += request.viewId
		}
		others.forEach { latestRender.putIfAbsent(it.viewId, it) }
		// A new rig redraws this window's views that asked for nothing since.
		val stale = synchronized(lock) {
			lastRequests.values.filter { it.gpu === gpu && it.viewId !in rendered && views[it.viewId]?.generation != current.generation }
		}
		stale.forEach { renderFrame(current, resources, gpu, it) }
		others.mapNotNull { it.gpu }.toSet().forEach(::scheduleRender)
	}

	private fun renderFrame(current: Loaded, resources: GpuResources, gpu: WindowGpu, request: PreviewRenderRequest): Unit = synchronized(lock) {
		val rt = runtime ?: return
		try {
			var view = views[request.viewId]
			if (view == null || view.generation != current.generation || view.gpu !== gpu) {
				view?.rig?.close()
				view = View(rt.load(current.bytes).also { it.behaviors(0) }, gpu, current.generation)
				views[request.viewId] = view
			}
			val context = resources.service(serviceKey) { ContextRenderer(P2lrtGlRenderer()) }
			if (context.generation != current.generation) {
				context.renderer.setRig(view.rig, current.pages)
				context.generation = current.generation
			}
			val rig = view.rig
			// The physics switch decides whether the simulation runs; without it the baked swing shows, skinned.
			val features = when {
				!request.advanced -> 0
				request.physics -> rig.advancedAvailable
				else -> rig.advancedAvailable and (SIM or COLLISION).inv()
			}
			if (view.advanced != features) {
				rig.setAdvanced(features)
				rig.resetPhysics()
				if (features and SIM != 0) rig.resetSimulation()
				view.advanced = features
				view.lastFrameNanos = 0L
			}
			// The pose is the whole pose: parameters it leaves out sit at their defaults.
			val values = rig.defaults.copyOf()
			val index = rig.parameterIds.withIndex().associate { it.value to it.index }
			for ((id, value) in request.parameterOverrides) index[id.raw]?.let { values[it] = value }
			rig.values = values
			if (features and SIM != 0) {
				// Stepped by the frame clock, not playback: a paused preview with physics on keeps swinging.
				val elapsed = if (view.lastFrameNanos == 0L) 0f else ((request.frameTimeNanos - view.lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
				view.lastFrameNanos = request.frameTimeNanos
				rig.update(elapsed)
			} else {
				rig.evaluate()
			}

			// Cubism's camera (the canvas height fits the shorter side times scale, offsets in clip units) in view pixels.
			val (canvasWidth, canvasHeight) = rig.canvas
			val pixelsPerCanvas = request.scale * minOf(request.width, request.height) / canvasHeight.coerceAtLeast(1f)
			val centerX = request.width * 0.5f * (1f + request.offsetX)
			val centerY = request.height * 0.5f * (1f - request.offsetY)
			resources.render(request.viewId, request.width, request.height, bottomUp = false) {
				context.renderer.draw(rig, request.width, request.height, pixelsPerCanvas,
					centerX - canvasWidth * 0.5f * pixelsPerCanvas, centerY - canvasHeight * 0.5f * pixelsPerCanvas)
			} ?: return
			val currentValues = rig.current
			val parameters = HashMap<ParameterId, Float>(currentValues.size)
			for ((i, id) in rig.parameterIds.withIndex()) parameters[ParameterId(id)] = currentValues[i]
			postFrame(PreviewFrame(
				width = request.width, height = request.height, parameters = parameters, animationEnabled = request.animationEnabled,
				viewId = request.viewId, cameraScale = request.scale, cameraOffsetX = request.offsetX, cameraOffsetY = request.offsetY,
				backend = PreviewBackend.P2LRT, advanced = features != 0,
			))
		} catch (failure: Throwable) {
			postStatus(failure.message ?: failure.javaClass.simpleName)
		}
	}

	fun removeView(viewId: String) {
		latestRender.remove(viewId)
		latestDelivery.remove(viewId)
		val view = synchronized(lock) { lastRequests.remove(viewId); views.remove(viewId) } ?: return
		view.gpu.post { resources -> synchronized(lock) { view.rig.close() }; resources.release(viewId) }
	}

	private fun postFrame(frame: PreviewFrame) {
		latestDelivery.put(frame.viewId, frame)
		if (deliveryScheduled.compareAndSet(false, true)) SwingUtilities.invokeLater(::deliver)
	}

	private fun deliver() {
		try {
			val frame = latestDelivery.poll()
			if (!closed && frame != null) onFrame(frame)
		} finally {
			deliveryScheduled.set(false)
			if (!closed && !latestDelivery.isEmpty() && deliveryScheduled.compareAndSet(false, true)) SwingUtilities.invokeLater(::deliver)
		}
	}

	private fun postStatus(status: String?) {
		if (!closed) SwingUtilities.invokeLater { if (!closed) onStatus(status) }
	}

	override fun close() {
		if (closed) return
		closed = true
		generation++
		latestRender.clear()
		latestDelivery.clear()
		pendingModel.set(null)
		compiler.shutdownNow()
		val byGpu = synchronized(lock) {
			lastRequests.clear()
			views.entries.groupBy({ it.value.gpu }, { it.key to it.value }).also { views.clear() }
		}
		for ((gpu, entries) in byGpu) gpu.post { resources ->
			entries.forEach { (viewId, view) -> runCatching { view.rig.close() }; resources.release(viewId) }
			resources.closeService(serviceKey)
		}
	}

	companion object {
		/** `P2L_SIM` and `P2L_COLLISION`: the advanced features the physics switch turns on and off. */
		private const val SIM = 4
		private const val COLLISION = 8

		/** Whether the runtime library can be found; the p2lrt preview needs it. */
		val runtimeAvailable: Boolean by lazy { P2lRuntime.locate() != null }
	}
}
