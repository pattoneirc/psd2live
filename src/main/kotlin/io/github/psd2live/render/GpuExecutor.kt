package io.github.psd2live.render

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Where a runtime's GPU work runs, one task after another. In the app it is a window's turn ([WindowGpu.post]):
 * Skia's context is current and the task gets its [GpuResources]. Tests run tasks on a plain thread without one.
 */
interface GpuExecutor {
	/** Whether tasks run with Skia's context current, so a runtime initializes on it rather than on its own. */
	val providesContext: Boolean

	/** Queues [task]; false once closed. */
	fun execute(task: (GpuResources?) -> Unit): Boolean

	/** Lets queued work finish for a moment, then stops taking more. */
	fun close() {}
}

/** Tasks on [gpu]'s window, run when it next renders or a canvas of it draws. */
class WindowGpuExecutor(private val gpu: WindowGpu) : GpuExecutor {
	@Volatile private var closed = false
	override val providesContext: Boolean get() = true

	override fun execute(task: (GpuResources?) -> Unit): Boolean {
		if (closed) return false
		gpu.post { task(it) }
		return true
	}

	override fun close() { closed = true }
}

/**
 * Tasks on the main window ([SkiaGpu.primary]); those queued before the window is captured wait for it. Runtimes
 * whose GL state lives in one context, as Cubism's framework does, draw here.
 */
class PrimaryGpuExecutor : GpuExecutor {
	private val waiting = ConcurrentLinkedQueue<(GpuResources?) -> Unit>()
	@Volatile private var closed = false
	override val providesContext: Boolean get() = true

	override fun execute(task: (GpuResources?) -> Unit): Boolean {
		if (closed) return false
		val gpu = SkiaGpu.primary
		if (gpu == null) {
			waiting += task
			SkiaGpu.whenPrimary { ready -> while (true) { val next = waiting.poll() ?: break; ready.post { next(it) } } }
		} else {
			gpu.post { task(it) }
		}
		return true
	}

	override fun close() { closed = true; waiting.clear() }
}

/** Tasks on a thread of their own, without a GPU context; for tests and tools. */
class ThreadGpuExecutor(name: String) : GpuExecutor {
	private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }
	override val providesContext: Boolean get() = false

	override fun execute(task: (GpuResources?) -> Unit): Boolean = try {
		executor.execute { task(null) }
		true
	} catch (_: RejectedExecutionException) {
		false
	}

	override fun close() {
		executor.shutdown()
		runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
	}
}
