package io.github.psd2live.render

import io.github.psd2live.format.eval.P2lRuntime
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.umamo.render.glsl.desktopCompositeShaderSources
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.IdentityHashMap

/**
 * Draws a `.p2lrt` rig the PSD2Live runtime evaluated, by the compositing rules of `p2l_runtime.h` ("Drawing"):
 * the runtime's render commands back to front, the mesh's texture times its multiply color under its screen
 * color, masks through the stencil (texels at least half opaque cover), back-face culling where the mesh asks
 * for it. Normal over, and Cubism's add and multiply under any alpha mode, blend in fixed function; every other
 * pair of color and alpha blend modes draws the mesh alone into a layer and composites it with the editor's
 * composite shader (umamo's `CompositeShaders`, whose reference is `BlendMath.compositeReference`). An isolated
 * group draws its commands into a cleared layer and composites it with the group's modes, opacity, multiply
 * and screen colors and mask coverage (the largest texture alpha of its masks); a group that would composite as
 * plain source-over of plain source-over meshes draws in place. Output is premultiplied, as Skia expects it.
 *
 * Every call runs in a turn of the window whose Skia context created it ([WindowGpu.turn]), into a bound
 * framebuffer with a stencil buffer and the viewport at its origin.
 */
internal class P2lrtGlRenderer : AutoCloseable {
	private class MeshBuffers(val array: Int, val positions: Int, val uvs: Int, val indices: Int, val indexCount: Int,
		val vertexCount: Int, val mesh: P2lRuntime.Mesh)

	/** An offscreen color texture of the targets' capacity, with a depth-stencil buffer when meshes draw into it. */
	private class Offscreen(val framebuffer: Int, val texture: Int, val depthStencil: Int)

	/** A framebuffer meshes draw into, and the stencil reference its masks used last. */
	private class Surface(val framebuffer: Int) {
		var stencilRef = 0
	}

	private val program: Int
	private val view: Int
	private val multiply: Int
	private val screen: Int
	private val opacity: Int
	private val maskMode: Int
	private val composite: Int
	private val compositeScreenSize: Int
	private val compositeColorMode: Int
	private val compositeAlphaMode: Int
	private val compositeOpacity: Int
	private val compositeMultiply: Int
	private val compositeScreen: Int
	private val compositeUseMask: Int
	private val compositeInvertMask: Int
	/** The composite pass draws a triangle from `gl_VertexID` alone, but a core profile wants a vertex array bound. */
	private val emptyArray: Int
	private var meshes: List<MeshBuffers> = emptyList()
	private var parts: List<P2lRuntime.Part> = emptyList()
	/** Page textures by the atlas page image they were made from, so a reload with unchanged pages uploads nothing. */
	private val textures = IdentityHashMap<BufferedImage, Int>()
	private var pageTextures: List<Int> = emptyList()
	private val multiplyColor = FloatArray(3)
	private val screenColor = FloatArray(3)
	/** One layer per nesting depth, the destination snapshot a composite reads and the mask coverage; grow-only. */
	private val layers = ArrayList<Offscreen>()
	private var snapshot: Offscreen? = null
	private var coverage: Offscreen? = null
	private var capacityWidth = 0
	private var capacityHeight = 0
	private var frameWidth = 0
	private var frameHeight = 0

	init {
		program = link(VERTEX, FRAGMENT, bindAttributes = true)
		view = GL20.glGetUniformLocation(program, "view")
		multiply = GL20.glGetUniformLocation(program, "multiplyColor")
		screen = GL20.glGetUniformLocation(program, "screenColor")
		opacity = GL20.glGetUniformLocation(program, "opacity")
		maskMode = GL20.glGetUniformLocation(program, "maskMode")
		GL20.glUseProgram(program)
		GL20.glUniform1i(GL20.glGetUniformLocation(program, "tex"), 0)
		val (compositeVertex, compositeFragment) = desktopCompositeShaderSources()
		composite = link(compositeVertex, compositeFragment, bindAttributes = false)
		compositeScreenSize = GL20.glGetUniformLocation(composite, "screenTexSize")
		compositeColorMode = GL20.glGetUniformLocation(composite, "colorMode")
		compositeAlphaMode = GL20.glGetUniformLocation(composite, "alphaMode")
		compositeOpacity = GL20.glGetUniformLocation(composite, "opacity")
		compositeMultiply = GL20.glGetUniformLocation(composite, "multiplyColor")
		compositeScreen = GL20.glGetUniformLocation(composite, "screenColor")
		compositeUseMask = GL20.glGetUniformLocation(composite, "useMask")
		compositeInvertMask = GL20.glGetUniformLocation(composite, "invertMask")
		GL20.glUseProgram(composite)
		GL20.glUniform1i(GL20.glGetUniformLocation(composite, "layerTexture"), LAYER_UNIT)
		GL20.glUniform1i(GL20.glGetUniformLocation(composite, "destTexture"), SNAPSHOT_UNIT)
		GL20.glUniform1i(GL20.glGetUniformLocation(composite, "maskTexture"), COVERAGE_UNIT)
		GL20.glUseProgram(0)
		emptyArray = GL30.glGenVertexArrays()
	}

	/** Takes [rig]'s meshes and parts and [pages], the atlas pages its meshes sample, by index. */
	fun setRig(rig: P2lRuntime.Rig, pages: List<BufferedImage>) {
		releaseMeshes()
		meshes = rig.meshIds.indices.map { index ->
			val mesh = rig.mesh(index)
			val vertexCount = mesh.uvs.size / 2
			val array = GL30.glGenVertexArrays()
			GL30.glBindVertexArray(array)
			val positions = GL15.glGenBuffers()
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positions)
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexCount * 8L, GL15.GL_STREAM_DRAW)
			GL20.glEnableVertexAttribArray(0)
			GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L)
			val uvs = GL15.glGenBuffers()
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, uvs)
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, mesh.uvs, GL15.GL_STATIC_DRAW)
			GL20.glEnableVertexAttribArray(1)
			GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 0, 0L)
			val indices = GL15.glGenBuffers()
			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indices)
			GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.indices, GL15.GL_STATIC_DRAW)
			GL30.glBindVertexArray(0)
			MeshBuffers(array, positions, uvs, indices, mesh.indices.size, vertexCount, mesh)
		}
		parts = rig.partIds.indices.map(rig::part)
		val kept = java.util.Collections.newSetFromMap(IdentityHashMap<BufferedImage, Boolean>()).apply { addAll(pages) }
		val iterator = textures.entries.iterator()
		while (iterator.hasNext()) {
			val (image, texture) = iterator.next()
			if (image !in kept) { GL11.glDeleteTextures(texture); iterator.remove() }
		}
		pageTextures = pages.map { page -> textures.getOrPut(page) { upload(page) } }
	}

	/**
	 * Draws [rig] as its last evaluation left it into the bound framebuffer of [width] × [height] pixels.
	 * Canvas pixel (x, y) lands at view pixel `x * scale + offsetX`, `y * scale + offsetY`, y down.
	 */
	fun draw(rig: P2lRuntime.Rig, width: Int, height: Int, scale: Float, offsetX: Float, offsetY: Float) {
		if (meshes.isEmpty()) return
		frameWidth = width
		frameHeight = height
		val target = Surface(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING))
		GL20.glUseProgram(program)
		// The frame's top row goes to clip y = -1, the texture's first row, which Skia samples as the top.
		val sx = 2f * scale / width
		val sy = 2f * scale / height
		GL20.glUniform4f(view, sx, sy, 2f * offsetX / width - 1f, 2f * offsetY / height - 1f)
		GL20.glUniform1i(maskMode, MASK_NONE)
		GL11.glEnable(GL11.GL_BLEND)
		// Drawn upside down in clip space, so what Cubism keeps as counter-clockwise faces is clockwise here.
		GL11.glFrontFace(GL11.GL_CW)
		GL13.glActiveTexture(GL13.GL_TEXTURE0)
		// Every mesh's vertices once: masks draw meshes again before their own turn.
		for ((index, buffers) in meshes.withIndex()) {
			if (buffers.vertexCount == 0) continue
			val vertices = rig.verticesBuffer(index) ?: continue
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers.positions)
			GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, vertices)
		}
		val commands = rig.renderCommands()
		drawSpan(rig, commands, groupEnds(commands), 0, commands.size, target, 0)
		GL11.glDisable(GL11.GL_STENCIL_TEST)
		GL11.glDisable(GL11.GL_CULL_FACE)
		GL30.glBindVertexArray(0)
		GL20.glUseProgram(0)
	}

	/** Draws commands [from] until [to] into [surface]; layers it needs come from [depth] on. */
	private fun drawSpan(rig: P2lRuntime.Rig, commands: IntArray, ends: IntArray, from: Int, to: Int, surface: Surface, depth: Int) {
		var i = from
		while (i < to) {
			val command = commands[i]
			if (command >= 0) {
				drawMeshCommand(rig, command, surface, depth)
				i++
			} else if (command <= -2) {
				drawGroup(rig, P2lRuntime.groupPart(command), commands, ends, i + 1, ends[i], surface, depth)
				i = ends[i] + 1
			} else {
				i++
			}
		}
	}

	private fun drawMeshCommand(rig: P2lRuntime.Rig, index: Int, surface: Surface, depth: Int) {
		val buffers = meshes.getOrNull(index) ?: return
		val meshOpacity = rig.opacity(index)
		// Nothing drawn composites to the destination unchanged, whatever the modes.
		if (!(meshOpacity > 0f) || buffers.indexCount == 0) return
		val blend = colorMode(buffers.mesh.blend)
		val alphaBlend = alphaMode(buffers.mesh.alphaBlend)
		if (fixedFunction(blend, alphaBlend)) {
			drawColored(rig, index, buffers, meshOpacity, surface, blend)
			return
		}
		// The mesh alone, over nothing, then composited by its modes.
		val layer = beginLayer(depth)
		drawColored(rig, index, buffers, meshOpacity, layer, P2L_BLEND_NORMAL)
		compositeLayer(surface, layers[depth], blend, alphaBlend, 1f, IDENTITY_MULTIPLY, IDENTITY_SCREEN, masked = false, invertMask = false)
	}

	private fun drawGroup(rig: P2lRuntime.Rig, part: Int, commands: IntArray, ends: IntArray, from: Int, to: Int, surface: Surface, depth: Int) {
		val info = parts.getOrNull(part) ?: return drawSpan(rig, commands, ends, from, to, surface, depth)
		val groupMultiply = FloatArray(3)
		val groupScreen = FloatArray(3)
		val groupOpacity = rig.partComposite(part, groupMultiply, groupScreen)
		if (!(groupOpacity > 0f)) return
		val blend = colorMode(info.blend)
		val alphaBlend = alphaMode(info.alphaBlend)
		if (drawsInPlace(blend, alphaBlend, info.masks.isNotEmpty(), groupOpacity, groupMultiply, groupScreen, commands, ends, from, to)) {
			drawSpan(rig, commands, ends, from, to, surface, depth)
			return
		}
		val layer = beginLayer(depth)
		drawSpan(rig, commands, ends, from, to, layer, depth + 1)
		val masked = info.masks.isNotEmpty()
		if (masked) drawCoverage(info.masks)
		compositeLayer(surface, layers[depth], blend, alphaBlend, groupOpacity.coerceAtMost(1f), groupMultiply, groupScreen, masked, info.invertMask)
	}

	/**
	 * Whether a group composites exactly as its commands drawn in place: source-over at full opacity with neutral
	 * colors and no mask, of meshes and groups that are themselves plain source-over (source-over is associative).
	 */
	private fun drawsInPlace(blend: Int, alphaBlend: Int, masked: Boolean, groupOpacity: Float, groupMultiply: FloatArray,
		groupScreen: FloatArray, commands: IntArray, ends: IntArray, from: Int, to: Int): Boolean {
		if (blend != P2L_BLEND_NORMAL || alphaBlend != P2L_ALPHA_OVER || masked || groupOpacity < 1f) return false
		if (!groupMultiply.contentEquals(IDENTITY_MULTIPLY) || !groupScreen.contentEquals(IDENTITY_SCREEN)) return false
		var i = from
		while (i < to) {
			val command = commands[i]
			if (command >= 0) {
				val mesh = meshes.getOrNull(command)?.mesh
				if (mesh != null && (colorMode(mesh.blend) != P2L_BLEND_NORMAL || alphaMode(mesh.alphaBlend) != P2L_ALPHA_OVER)) return false
				i++
			} else if (command <= -2) {
				val child = parts.getOrNull(P2lRuntime.groupPart(command))
				if (child != null && (colorMode(child.blend) != P2L_BLEND_NORMAL || alphaMode(child.alphaBlend) != P2L_ALPHA_OVER)) return false
				i = ends[i] + 1
			} else {
				i++
			}
		}
		return true
	}

	/** Draws mesh [index] with its colors and opacity into [surface], clipped by its masks, blending by [blend]. */
	private fun drawColored(rig: P2lRuntime.Rig, index: Int, buffers: MeshBuffers, meshOpacity: Float, surface: Surface, blend: Int) {
		val mesh = buffers.mesh
		val masked = mesh.masks.isNotEmpty()
		if (masked) {
			if (surface.stencilRef == 255) { GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT); surface.stencilRef = 0 }
			val ref = ++surface.stencilRef
			// Masks write the reference where they cover, without colour; the mesh draws where it matches (or not).
			GL11.glEnable(GL11.GL_STENCIL_TEST)
			GL11.glColorMask(false, false, false, false)
			GL11.glStencilFunc(GL11.GL_ALWAYS, ref, 0xff)
			GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE)
			GL11.glDisable(GL11.GL_CULL_FACE)
			GL20.glUniform1i(maskMode, MASK_STENCIL)
			GL20.glUniform1f(opacity, 1f)
			for (mask in mesh.masks) meshes.getOrNull(mask)?.let(::drawMesh)
			GL11.glColorMask(true, true, true, true)
			GL11.glStencilFunc(if (mesh.invertMask) GL11.GL_NOTEQUAL else GL11.GL_EQUAL, ref, 0xff)
			GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)
		}
		fixedFunctionBlend(blend)
		if (mesh.culling) GL11.glEnable(GL11.GL_CULL_FACE) else GL11.glDisable(GL11.GL_CULL_FACE)
		rig.colors(index, multiplyColor, screenColor)
		GL20.glUniform3f(multiply, multiplyColor[0], multiplyColor[1], multiplyColor[2])
		GL20.glUniform3f(screen, screenColor[0], screenColor[1], screenColor[2])
		GL20.glUniform1i(maskMode, MASK_NONE)
		GL20.glUniform1f(opacity, meshOpacity.coerceIn(0f, 1f))
		drawMesh(buffers)
		if (masked) GL11.glDisable(GL11.GL_STENCIL_TEST)
	}

	private fun drawMesh(buffers: MeshBuffers) {
		val texture = pageTextures.getOrNull(buffers.mesh.texture) ?: return
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
		GL30.glBindVertexArray(buffers.array)
		GL11.glDrawElements(GL11.GL_TRIANGLES, buffers.indexCount, GL11.GL_UNSIGNED_INT, 0L)
	}

	/** Normal source-over, or Cubism's add and multiply, on premultiplied colour. */
	private fun fixedFunctionBlend(mode: Int) {
		when (mode) {
			P2L_BLEND_CUBISM_ADD -> GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE)
			P2L_BLEND_CUBISM_MULTIPLY -> GL14.glBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE)
			else -> GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
		}
	}

	/** Binds depth [depth]'s layer, cleared to transparent with an empty stencil, for meshes to draw into. */
	private fun beginLayer(depth: Int): Surface {
		ensureCapacity()
		while (layers.size <= depth) layers += offscreen(depthStencil = true)
		val layer = layers[depth]
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, layer.framebuffer)
		GL11.glClearColor(0f, 0f, 0f, 0f)
		GL11.glClearStencil(0)
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_STENCIL_BUFFER_BIT)
		return Surface(layer.framebuffer)
	}

	/** Draws the coverage of [masks] (the largest texture alpha at each pixel) into the coverage target. */
	private fun drawCoverage(masks: IntArray) {
		val target = coverage ?: offscreen(depthStencil = false).also { coverage = it }
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer)
		GL11.glClearColor(0f, 0f, 0f, 0f)
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
		GL11.glDisable(GL11.GL_STENCIL_TEST)
		GL11.glDisable(GL11.GL_CULL_FACE)
		GL14.glBlendEquation(GL14.GL_MAX)
		GL20.glUniform1i(maskMode, MASK_COVERAGE)
		GL20.glUniform1f(opacity, 1f)
		for (mask in masks) meshes.getOrNull(mask)?.let(::drawMesh)
		GL14.glBlendEquation(GL14.GL_FUNC_ADD)
		GL20.glUniform1i(maskMode, MASK_NONE)
	}

	/**
	 * Composites [layer] into [surface] by [blend] and [alphaBlend] with the group's channels, through the composite
	 * shader: it reads a snapshot of [surface] and writes the whole result with blending off.
	 */
	private fun compositeLayer(surface: Surface, layer: Offscreen, blend: Int, alphaBlend: Int, layerOpacity: Float,
		layerMultiply: FloatArray, layerScreen: FloatArray, masked: Boolean, invertMask: Boolean) {
		val destination = snapshot ?: offscreen(depthStencil = false).also { snapshot = it }
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, surface.framebuffer)
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.framebuffer)
		GL30.glBlitFramebuffer(0, 0, frameWidth, frameHeight, 0, 0, frameWidth, frameHeight, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, surface.framebuffer)
		GL11.glDisable(GL11.GL_BLEND)
		GL11.glDisable(GL11.GL_STENCIL_TEST)
		GL11.glDisable(GL11.GL_CULL_FACE)
		GL20.glUseProgram(composite)
		GL20.glUniform2f(compositeScreenSize, capacityWidth.toFloat(), capacityHeight.toFloat())
		GL20.glUniform1i(compositeColorMode, blend)
		GL20.glUniform1i(compositeAlphaMode, alphaBlend)
		GL20.glUniform1f(compositeOpacity, layerOpacity)
		GL20.glUniform3f(compositeMultiply, layerMultiply[0], layerMultiply[1], layerMultiply[2])
		GL20.glUniform3f(compositeScreen, layerScreen[0], layerScreen[1], layerScreen[2])
		GL20.glUniform1i(compositeUseMask, if (masked) 1 else 0)
		GL20.glUniform1i(compositeInvertMask, if (masked && invertMask) 1 else 0)
		bindUnit(LAYER_UNIT, layer.texture)
		bindUnit(SNAPSHOT_UNIT, destination.texture)
		bindUnit(COVERAGE_UNIT, if (masked) coverage?.texture ?: 0 else 0)
		GL30.glBindVertexArray(emptyArray)
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
		// Nothing a later pass renders into stays bound for sampling.
		bindUnit(COVERAGE_UNIT, 0)
		bindUnit(SNAPSHOT_UNIT, 0)
		bindUnit(LAYER_UNIT, 0)
		GL20.glUseProgram(program)
		GL11.glEnable(GL11.GL_BLEND)
	}

	private fun bindUnit(unit: Int, texture: Int) {
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
		GL13.glActiveTexture(GL13.GL_TEXTURE0)
	}

	/** Grows the offscreen targets to hold the frame; they keep the larger size, so a resize drag allocates once. */
	private fun ensureCapacity() {
		if (frameWidth <= capacityWidth && frameHeight <= capacityHeight) return
		releaseOffscreen()
		capacityWidth = roundUp(maxOf(frameWidth, capacityWidth))
		capacityHeight = roundUp(maxOf(frameHeight, capacityHeight))
	}

	private fun offscreen(depthStencil: Boolean): Offscreen {
		val texture = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, capacityWidth, capacityHeight, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
		val renderbuffer = if (depthStencil) GL30.glGenRenderbuffers().also {
			GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, it)
			GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, capacityWidth, capacityHeight)
			GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0)
		} else 0
		val framebuffer = GL30.glGenFramebuffers()
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0)
		if (renderbuffer != 0) GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, renderbuffer)
		val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
		check(status == GL30.GL_FRAMEBUFFER_COMPLETE) { "Layer target incomplete: 0x${Integer.toHexString(status)}" }
		return Offscreen(framebuffer, texture, renderbuffer)
	}

	private fun release(target: Offscreen) {
		GL30.glDeleteFramebuffers(target.framebuffer)
		GL11.glDeleteTextures(target.texture)
		if (target.depthStencil != 0) GL30.glDeleteRenderbuffers(target.depthStencil)
	}

	private fun releaseOffscreen() {
		layers.forEach(::release)
		layers.clear()
		snapshot?.let(::release)
		snapshot = null
		coverage?.let(::release)
		coverage = null
		capacityWidth = 0
		capacityHeight = 0
	}

	/** Uploads [image] premultiplied, with mipmaps so a zoomed-out preview does not shimmer. */
	private fun upload(image: BufferedImage): Int {
		val width = image.width
		val height = image.height
		val raster = (image.raster.dataBuffer as? DataBufferInt)?.data?.takeIf { it.size == width * height }
		val premultipliedInput = raster != null && image.type == BufferedImage.TYPE_INT_ARGB_PRE
		val source = if (raster != null && (premultipliedInput || image.type == BufferedImage.TYPE_INT_ARGB)) raster
			else image.getRGB(0, 0, width, height, null, 0, width)
		val pixels = MemoryUtil.memAlloc(width * height * 4)
		try {
			for (i in 0 until width * height) {
				val c = source[i]
				val a = c ushr 24
				if (premultipliedInput || a == 255) {
					pixels.put((c ushr 16 and 0xff).toByte()).put((c ushr 8 and 0xff).toByte()).put((c and 0xff).toByte()).put(a.toByte())
				} else {
					pixels.put((((c ushr 16 and 0xff) * a + 127) / 255).toByte()).put((((c ushr 8 and 0xff) * a + 127) / 255).toByte())
						.put((((c and 0xff) * a + 127) / 255).toByte()).put(a.toByte())
				}
			}
			pixels.flip()
			val texture = GL11.glGenTextures()
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
			GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
			return texture
		} finally {
			MemoryUtil.memFree(pixels)
		}
	}

	private fun releaseMeshes() {
		for (buffers in meshes) {
			GL30.glDeleteVertexArrays(buffers.array)
			GL15.glDeleteBuffers(buffers.positions)
			GL15.glDeleteBuffers(buffers.uvs)
			GL15.glDeleteBuffers(buffers.indices)
		}
		meshes = emptyList()
		parts = emptyList()
	}

	override fun close() {
		releaseMeshes()
		releaseOffscreen()
		textures.values.forEach(GL11::glDeleteTextures)
		textures.clear()
		pageTextures = emptyList()
		GL30.glDeleteVertexArrays(emptyArray)
		GL20.glDeleteProgram(program)
		GL20.glDeleteProgram(composite)
	}

	private fun link(vertex: String, fragment: String, bindAttributes: Boolean): Int {
		fun compile(type: Int, source: String): Int {
			val shader = GL20.glCreateShader(type)
			GL20.glShaderSource(shader, source)
			GL20.glCompileShader(shader)
			check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) { "Shader compile failed: ${GL20.glGetShaderInfoLog(shader)}" }
			return shader
		}
		val vs = compile(GL20.GL_VERTEX_SHADER, vertex)
		val fs = compile(GL20.GL_FRAGMENT_SHADER, fragment)
		val id = GL20.glCreateProgram()
		GL20.glAttachShader(id, vs)
		GL20.glAttachShader(id, fs)
		if (bindAttributes) {
			GL20.glBindAttribLocation(id, 0, "position")
			GL20.glBindAttribLocation(id, 1, "uv")
		}
		GL20.glLinkProgram(id)
		GL20.glDeleteShader(vs)
		GL20.glDeleteShader(fs)
		check(GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == GL11.GL_TRUE) { "Program link failed: ${GL20.glGetProgramInfoLog(id)}" }
		return id
	}

	companion object {
		private const val P2L_BLEND_NORMAL = 0
		private const val P2L_BLEND_CUBISM_ADD = 1
		private const val P2L_BLEND_CUBISM_MULTIPLY = 2
		private const val P2L_BLEND_LAST = 17
		private const val P2L_ALPHA_OVER = 0
		private const val P2L_ALPHA_LAST = 4
		private const val MASK_NONE = 0
		private const val MASK_STENCIL = 1
		private const val MASK_COVERAGE = 2
		private const val LAYER_UNIT = 0
		private const val SNAPSHOT_UNIT = 1
		private const val COVERAGE_UNIT = 2
		private val IDENTITY_MULTIPLY = floatArrayOf(1f, 1f, 1f)
		private val IDENTITY_SCREEN = floatArrayOf(0f, 0f, 0f)

		/** A color blend mode the runtime does not name draws as normal. */
		private fun colorMode(mode: Int): Int = if (mode in 0..P2L_BLEND_LAST) mode else P2L_BLEND_NORMAL

		/** An alpha blend mode the runtime does not name draws as over. */
		private fun alphaMode(mode: Int): Int = if (mode in 0..P2L_ALPHA_LAST) mode else P2L_ALPHA_OVER

		/** Whether fixed-function blending draws the pair: normal over, and Cubism's add and multiply under any alpha mode. */
		internal fun fixedFunction(blend: Int, alphaBlend: Int): Boolean =
			blend == P2L_BLEND_CUBISM_ADD || blend == P2L_BLEND_CUBISM_MULTIPLY || (blend == P2L_BLEND_NORMAL && alphaBlend == P2L_ALPHA_OVER)

		/**
		 * For each command opening a group, the index of the command closing it ([commands]' size when it is never
		 * closed); 0 elsewhere.
		 */
		internal fun groupEnds(commands: IntArray): IntArray {
			val ends = IntArray(commands.size)
			val open = ArrayDeque<Int>()
			for ((i, command) in commands.withIndex()) {
				if (command <= -2) open.addLast(i)
				else if (command == P2lRuntime.END_GROUP) open.removeLastOrNull()?.let { ends[it] = i }
			}
			while (open.isNotEmpty()) ends[open.removeLast()] = commands.size
			return ends
		}

		private fun roundUp(value: Int): Int = (value + 255) / 256 * 256

		const val VERTEX = """#version 150
in vec2 position;
in vec2 uv;
uniform vec4 view; // scale x, scale y, offset x, offset y
out vec2 vUv;
void main() {
	vUv = uv;
	gl_Position = vec4(position * view.xy + view.zw, 0.0, 1.0);
}"""

		const val FRAGMENT = """#version 150
uniform sampler2D tex;
uniform vec3 multiplyColor;
uniform vec3 screenColor;
uniform float opacity;
uniform int maskMode; // 1: a stencil mask, keeping texels at least half opaque; 2: mask coverage, the texel's alpha
in vec2 vUv;
out vec4 color;
void main() {
	vec4 c = texture(tex, vUv);
	if (maskMode == 1) {
		if (c.a < 0.5) discard;
		color = vec4(0.0);
		return;
	}
	if (maskMode == 2) {
		color = vec4(c.a);
		return;
	}
	vec3 rgb = c.a > 0.0 ? c.rgb / c.a : vec3(0.0);
	rgb *= multiplyColor;
	rgb = rgb + screenColor - rgb * screenColor;
	float a = c.a * opacity;
	color = vec4(rgb * a, a);
}"""
	}
}
