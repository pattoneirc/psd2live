package io.github.psd2live.core

import io.github.psd2live.format.compile.GeometrySession
import io.github.psd2live.format.eval.NativeGeometryEvaluator
import io.github.psd2live.format.eval.P2lRuntime
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDrawableIds
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The software preview's deformation through the Rust runtime: one runtime session per preview model,
 * compiled in the background the first time the model is evaluated (the engine answers meanwhile, so
 * transient drag previews never wait) and kept while the model lives. Results come back in the engine's
 * form (world y up, visible drawables only), pose for pose the engine's evaluation (see
 * NativeRuntimeConformanceTest). Without the runtime library, or when a rig fails to compile, callers keep
 * the engine; `-Dpsd2live.preview.runtime=false` turns it off.
 */
internal object NativePreview {
	private val runtime: P2lRuntime? by lazy {
		if (System.getProperty("psd2live.preview.runtime") == "false") null
		else runCatching { P2lRuntime.load() }.getOrNull()
	}

	private class Session(val geometry: GeometrySession, val visible: Set<DrawableId>)

	/** Sessions by puppet identity; a failed compile is remembered as null so it is not retried. */
	private val sessions: MutableMap<PuppetModel, Session?> = Collections.synchronizedMap(WeakHashMap())
	private val compiling = AtomicBoolean(false)
	private val compiler = Executors.newSingleThreadExecutor { Thread(it, "native-preview-compile").apply { isDaemon = true } }

	/** The model at [parameters] through the runtime, or null when the engine should answer. */
	fun evaluate(model: RigPreviewModel, parameters: Map<ParameterId, Float>): DeformedGeometry? {
		val runtime = runtime ?: return null
		val puppet = model.rig.puppet
		val session = synchronized(sessions) {
			if (puppet in sessions) sessions[puppet] else null.also { schedule(runtime, model) }
		} ?: return null
		val pose = synchronized(session) {
			runCatching { session.geometry.evaluate(parameters.mapKeys { it.key.raw }) }.getOrNull()
		} ?: return null
		val positions = HashMap<DrawableId, FloatArray>()
		val opacity = HashMap<DrawableId, Float>()
		val drawOrder = HashMap<DrawableId, Float>()
		for ((id, points) in pose.positions) {
			val drawable = DrawableId(id)
			if (drawable !in session.visible) continue
			// The engine's world space negates canvas y.
			positions[drawable] = FloatArray(points.size) { if (it % 2 == 0) points[it] else -points[it] }
			pose.opacity[id]?.let { opacity[drawable] = it }
			pose.drawOrder[id]?.let { drawOrder[drawable] = it }
		}
		return DeformedGeometry(positions, drawOrder, opacity)
	}

	/** Compiles [model] in the background unless a compile is already running. */
	private fun schedule(runtime: P2lRuntime, model: RigPreviewModel) {
		if (!compiling.compareAndSet(false, true)) return
		compiler.execute {
			try {
				val puppet = model.rig.puppet
				val session = runCatching {
					Session(NativeGeometryEvaluator(runtime).open(RigIrCompiler.compile(model)), puppet.visibleDrawableIds())
				}.getOrNull()
				sessions[puppet] = session
			} finally {
				compiling.set(false)
			}
		}
	}

	/** Waits for a background compile, for tests. */
	internal fun awaitIdle() {
		while (compiling.get()) Thread.sleep(5)
	}
}
