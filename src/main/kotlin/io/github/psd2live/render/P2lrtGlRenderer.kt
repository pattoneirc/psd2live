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
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.IdentityHashMap

/**
 * Draws a `.p2lrt` rig the PSD2Live runtime evaluated, as the web player and the Godot node draw it: meshes back
 * to front in the runtime's render order, the mesh's texture times its multiply color under its screen color,
 * masks through the stencil (texels at least half opaque cover), normal, additive and multiplicative blending
 * as Cubism composes them, and back-face culling where the mesh asks for it. Output is premultiplied, as Skia
 * expects it.
 *
 * Every call runs in a turn of the window whose Skia context created it ([WindowGpu.turn]).
 */
internal class P2lrtGlRenderer : AutoCloseable {
	private class MeshBuffers(val array: Int, val positions: Int, val uvs: Int, val indices: Int, val indexCount: Int,
		val vertexCount: Int, val mesh: P2lRuntime.Mesh)

	private val program: Int
	private val view: Int
	private val multiply: Int
	private val screen: Int
	private val opacity: Int
	private val maskThreshold: Int
	private var meshes: List<MeshBuffers> = emptyList()
	/** Page textures by the atlas page image they were made from, so a reload with unchanged pages uploads nothing. */
	private val textures = IdentityHashMap<BufferedImage, Int>()
	private var pageTextures: List<Int> = emptyList()
	private val multiplyColor = FloatArray(3)
	private val screenColor = FloatArray(3)

	init {
		program = link(VERTEX, FRAGMENT)
		view = GL20.glGetUniformLocation(program, "view")
		multiply = GL20.glGetUniformLocation(program, "multiplyColor")
		screen = GL20.glGetUniformLocation(program, "screenColor")
		opacity = GL20.glGetUniformLocation(program, "opacity")
		maskThreshold = GL20.glGetUniformLocation(program, "maskThreshold")
		GL20.glUseProgram(program)
		GL20.glUniform1i(GL20.glGetUniformLocation(program, "tex"), 0)
		GL20.glUseProgram(0)
	}

	/** Takes [rig]'s meshes and [pages], the atlas pages its meshes sample, by index. */
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
		GL20.glUseProgram(program)
		// The frame's top row goes to clip y = -1, the texture's first row, which Skia samples as the top.
		val sx = 2f * scale / width
		val sy = 2f * scale / height
		GL20.glUniform4f(view, sx, sy, 2f * offsetX / width - 1f, 2f * offsetY / height - 1f)
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
		var stencilRef = 0
		for (index in rig.renderOrder()) {
			val buffers = meshes.getOrNull(index) ?: continue
			val meshOpacity = rig.opacity(index)
			if (meshOpacity <= 0f || buffers.indexCount == 0) continue
			val mesh = buffers.mesh
			val masked = mesh.masks.isNotEmpty()
			if (masked) {
				if (stencilRef == 255) { GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT); stencilRef = 0 }
				val ref = ++stencilRef
				// Masks write the reference where they cover, without colour; the mesh draws where it matches (or not).
				GL11.glEnable(GL11.GL_STENCIL_TEST)
				GL11.glColorMask(false, false, false, false)
				GL11.glStencilFunc(GL11.GL_ALWAYS, ref, 0xff)
				GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE)
				GL11.glDisable(GL11.GL_CULL_FACE)
				GL20.glUniform1f(maskThreshold, 0.5f)
				GL20.glUniform1f(opacity, 1f)
				for (mask in mesh.masks) meshes.getOrNull(mask)?.let(::drawMesh)
				GL11.glColorMask(true, true, true, true)
				GL11.glStencilFunc(if (mesh.invertMask) GL11.GL_NOTEQUAL else GL11.GL_EQUAL, ref, 0xff)
				GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)
			}
			blend(mesh.blend)
			if (mesh.culling) GL11.glEnable(GL11.GL_CULL_FACE) else GL11.glDisable(GL11.GL_CULL_FACE)
			rig.colors(index, multiplyColor, screenColor)
			GL20.glUniform3f(multiply, multiplyColor[0], multiplyColor[1], multiplyColor[2])
			GL20.glUniform3f(screen, screenColor[0], screenColor[1], screenColor[2])
			GL20.glUniform1f(maskThreshold, 0f)
			GL20.glUniform1f(opacity, meshOpacity.coerceIn(0f, 1f))
			drawMesh(buffers)
			if (masked) GL11.glDisable(GL11.GL_STENCIL_TEST)
		}
		GL11.glDisable(GL11.GL_CULL_FACE)
		GL30.glBindVertexArray(0)
		GL20.glUseProgram(0)
	}

	private fun drawMesh(buffers: MeshBuffers) {
		val texture = pageTextures.getOrNull(buffers.mesh.texture) ?: return
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
		GL30.glBindVertexArray(buffers.array)
		GL11.glDrawElements(GL11.GL_TRIANGLES, buffers.indexCount, GL11.GL_UNSIGNED_INT, 0L)
	}

	/** Cubism's compositing on premultiplied colour; the extended modes fall back to their nearest basic one. */
	private fun blend(mode: Int) {
		when (mode) {
			1, 3, 4 -> GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE)
			2, 6 -> GL14.glBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE)
			else -> GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
		}
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
	}

	override fun close() {
		releaseMeshes()
		textures.values.forEach(GL11::glDeleteTextures)
		textures.clear()
		pageTextures = emptyList()
		GL20.glDeleteProgram(program)
	}

	private fun link(vertex: String, fragment: String): Int {
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
		GL20.glBindAttribLocation(id, 0, "position")
		GL20.glBindAttribLocation(id, 1, "uv")
		GL20.glLinkProgram(id)
		GL20.glDeleteShader(vs)
		GL20.glDeleteShader(fs)
		check(GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == GL11.GL_TRUE) { "Program link failed: ${GL20.glGetProgramInfoLog(id)}" }
		return id
	}

	private companion object {
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
uniform float maskThreshold; // above zero: draw as a mask, keeping only covered texels
in vec2 vUv;
out vec4 color;
void main() {
	vec4 c = texture(tex, vUv);
	if (maskThreshold > 0.0) {
		if (c.a < maskThreshold) discard;
		color = vec4(0.0);
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
