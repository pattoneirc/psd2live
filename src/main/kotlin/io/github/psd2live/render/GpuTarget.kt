package io.github.psd2live.render

import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL33

/** What a view drew last: [image] holds it in its top-left [width] × [height] texels. */
class GpuFrame internal constructor(val image: Image, val width: Int, val height: Int)

/**
 * A view's render target in Skia's own context: one texture Skia owns and samples (top row first), a
 * depth-stencil buffer, and for drawers that render with GL's bottom-up rows (Cubism) a scene buffer that is
 * copied in turned upright. Each frame overwrites the same texture: Skia sampled the last one in an earlier frame
 * of the same context, so the GPU finishes that before this frame's draw lands. It only grows, so a dock drag
 * that resizes every frame allocates nothing.
 */
internal class GpuTarget {
	private var texture = 0
	private var depthStencil = 0
	private var framebuffer = 0
	private var scene = 0
	private var sceneFramebuffer = 0
	private var capacityWidth = 0
	private var capacityHeight = 0
	private var image: Image? = null
	private var drawn: GpuFrame? = null

	/**
	 * Binds a cleared framebuffer of [width] × [height] for [draw]: the output itself, whose first row is the
	 * frame's top (the drawer maps the top to clip y = -1), or with [bottomUp] the scene buffer, copied in flipped.
	 */
	fun render(skia: DirectContext, width: Int, height: Int, bottomUp: Boolean, draw: () -> Unit): GpuFrame? {
		val w = width.coerceAtLeast(1)
		val h = height.coerceAtLeast(1)
		ensure(w, h)
		if (bottomUp && sceneFramebuffer == 0) createScene()
		val target = if (bottomUp) sceneFramebuffer else framebuffer
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target)
		GL11.glViewport(0, 0, w, h)
		GL11.glDisable(GL11.GL_SCISSOR_TEST)
		GL11.glColorMask(true, true, true, true)
		GL11.glStencilMask(0xff)
		GL11.glClearColor(0f, 0f, 0f, 0f)
		GL11.glClearStencil(0)
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT or GL11.GL_STENCIL_BUFFER_BIT)
		// The default vertex array, emptied of what Skia left on it: Cubism's renderer draws from client-side arrays
		// there, and a buffer still bound (or an attribute still enabled) would make it read Skia's buffers instead.
		cleanDefaultVertexArray()
		GL20.glUseProgram(0)
		draw()
		if (bottomUp) {
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sceneFramebuffer)
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
			GL30.glBlitFramebuffer(0, 0, w, h, 0, h, w, 0, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST)
		}
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
		val adopted = image ?: Image.adoptTextureFrom(skia,
			BackendTexture.makeGL(capacityWidth, capacityHeight, false, texture, GL11.GL_TEXTURE_2D, GL11.GL_RGBA8),
			SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888).also { image = it }
		return GpuFrame(adopted, w, h).also { drawn = it }
	}

	/** The last frame drawn, while its texture stands. */
	val last: GpuFrame? get() = drawn

	/** The last frame's pixels, top row first, premultiplied RGBA; for the development tools only. */
	fun pixels(): Triple<Int, Int, ByteArray>? {
		val frame = drawn ?: return null
		val buffer = org.lwjgl.system.MemoryUtil.memAlloc(capacityWidth * capacityHeight * 4)
		try {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
			GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
			val out = ByteArray(frame.width * frame.height * 4)
			for (y in 0 until frame.height) {
				buffer.position(y * capacityWidth * 4)
				buffer.get(out, y * frame.width * 4, frame.width * 4)
			}
			return Triple(frame.width, frame.height, out)
		} finally {
			org.lwjgl.system.MemoryUtil.memFree(buffer)
		}
	}

	private fun ensure(width: Int, height: Int) {
		if (framebuffer != 0 && width <= capacityWidth && height <= capacityHeight) return
		val newWidth = roundUp(maxOf(width, capacityWidth))
		val newHeight = roundUp(maxOf(height, capacityHeight))
		dispose()
		capacityWidth = newWidth
		capacityHeight = newHeight
		texture = colorTexture()
		depthStencil = GL30.glGenRenderbuffers()
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthStencil)
		GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, capacityWidth, capacityHeight)
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0)
		framebuffer = framebuffer(texture)
	}

	private fun createScene() {
		scene = colorTexture()
		sceneFramebuffer = framebuffer(scene)
	}

	private fun framebuffer(color: Int): Int {
		val id = GL30.glGenFramebuffers()
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, id)
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0)
		GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depthStencil)
		val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
		check(status == GL30.GL_FRAMEBUFFER_COMPLETE) { "Render target incomplete: 0x${Integer.toHexString(status)}" }
		return id
	}

	private fun colorTexture(): Int {
		val id = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, capacityWidth, capacityHeight, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
		return id
	}

	/** Frees everything; an adopted texture goes with its image (its context is current: this runs in a turn). */
	fun dispose() {
		drawn = null
		val adopted = image
		image = null
		if (adopted != null) adopted.close() else if (texture != 0) GL11.glDeleteTextures(texture)
		if (scene != 0) GL11.glDeleteTextures(scene)
		if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer)
		if (sceneFramebuffer != 0) GL30.glDeleteFramebuffers(sceneFramebuffer)
		if (depthStencil != 0) GL30.glDeleteRenderbuffers(depthStencil)
		texture = 0; scene = 0; framebuffer = 0; sceneFramebuffer = 0; depthStencil = 0
	}

	private fun roundUp(value: Int) = ((value.coerceAtLeast(1) + 63) / 64) * 64
}

/** Binds vertex array 0 with no array or index buffer and every attribute array off. */
internal fun cleanDefaultVertexArray() {
	GL30.glBindVertexArray(0)
	GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
	GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0)
	for (attribute in 0 until 16) {
		GL20.glDisableVertexAttribArray(attribute)
		GL33.glVertexAttribDivisor(attribute, 0)
	}
}

/**
 * What one Skia context holds for the renderer: a target per view, the editing canvas renderer, and objects
 * other parts keep per context (the p2lrt renderer and its rigs). Touched only inside [WindowGpu.turn].
 */
class GpuResources internal constructor(private val gpu: WindowGpu) {
	private val targets = HashMap<String, GpuTarget>()
	private val services = HashMap<Any, AutoCloseable>()
	private var canvas: GlCanvasRenderer? = null

	internal val skia: DirectContext get() = checkNotNull(gpu.directContext()) { "No Skia context" }

	/** See [WindowGpu.coreProfile]. */
	val coreProfile: Boolean get() = gpu.coreProfile

	/** Draws [viewId]'s frame with [draw] (see [GpuTarget.render]). */
	fun render(viewId: String, width: Int, height: Int, bottomUp: Boolean, draw: () -> Unit): GpuFrame? =
		targets.getOrPut(viewId) { GpuTarget() }.render(skia, width, height, bottomUp, draw)

	/** What [viewId] drew last. */
	fun frame(viewId: String): GpuFrame? = targets[viewId]?.last

	/** [viewId]'s last frame as width, height and top-down premultiplied RGBA; for the development tools only. */
	internal fun pixels(viewId: String): Triple<Int, Int, ByteArray>? = targets[viewId]?.pixels()

	internal val canvasRenderer: GlCanvasRenderer get() = canvas ?: GlCanvasRenderer().also { canvas = it }

	/** This context's [key] object, made on first use and closed with the context's resources. */
	@Suppress("UNCHECKED_CAST")
	fun <T : AutoCloseable> service(key: Any, make: () -> T): T = services.getOrPut(key, make) as T

	fun serviceOrNull(key: Any): AutoCloseable? = services[key]

	fun closeService(key: Any) {
		services.remove(key)?.let { runCatching { it.close() } }
	}

	/** Frees [viewId]'s target and what the editing renderer keeps for it. */
	fun release(viewId: String) {
		targets.remove(viewId)?.dispose()
		canvas?.release(viewId)
	}

	/**
	 * Puts the state Skia leaves behind back to GL's defaults: a turn starts from where Skia stopped (its stencil
	 * clip test, sampler objects, pixel buffers, row lengths), and drawers such as Cubism's set only what they use.
	 * Runs at the start and the end of every turn.
	 */
	internal fun restoreDefaults() {
		cleanDefaultVertexArray()
		GL20.glUseProgram(0)
		for (unit in 0 until 8) {
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
			GL33.glBindSampler(unit, 0)
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
		}
		GL13.glActiveTexture(GL13.GL_TEXTURE0)
		GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
		GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, 0)
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0)
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0)
		GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4)
		GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, 0)
		GL11.glDisable(GL11.GL_STENCIL_TEST)
		GL11.glDisable(GL11.GL_CULL_FACE)
		GL11.glDisable(GL11.GL_DEPTH_TEST)
		GL11.glDisable(GL11.GL_SCISSOR_TEST)
		GL11.glDisable(GL11.GL_DITHER)
		GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB)
		GL11.glDisable(GL31.GL_PRIMITIVE_RESTART)
		GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL)
		GL11.glFrontFace(GL11.GL_CCW)
		GL14.glBlendEquation(GL14.GL_FUNC_ADD)
		GL11.glColorMask(true, true, true, true)
		GL11.glDepthMask(true)
		GL11.glStencilMask(0xff)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
	}
}
