package io.github.psd2live.format.eval

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.psd2live.format.compile.GeometryEvaluator
import io.github.psd2live.format.compile.GeometrySession
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.runtime.P2lrt
import java.io.File

/**
 * The PSD2Live runtime (runtime/, a native library) loaded through JNA. [locate] finds it from the
 * `psd2live.runtime.library` system property, the `PSD2LIVE_RUNTIME` environment variable (a file or a
 * directory), the `psd2live.runtime.dir` system property, a packaged app's resources directory, or the library
 * path.
 */
public class P2lRuntime private constructor(private val native: Bindings) {
	@Suppress("FunctionName", "LocalVariableName")
	private interface Bindings : Library {
		fun p2l_rig_load(bytes: ByteArray, len: Long, error: ByteArray, errorCapacity: Long): Pointer?
		fun p2l_rig_free(rig: Pointer)
		fun p2l_rig_failure(rig: Pointer): String?
		fun p2l_version(): String
		fun p2l_abi_version(): Int
		fun p2l_parameter_count(rig: Pointer): Int
		fun p2l_parameter_id(rig: Pointer, index: Int): String?
		fun p2l_parameter_values(rig: Pointer): Pointer
		fun p2l_evaluate(rig: Pointer)
		fun p2l_update(rig: Pointer, dt: Float)
		fun p2l_physics_reset(rig: Pointer)
		fun p2l_clip_count(rig: Pointer): Int
		fun p2l_clip_id(rig: Pointer, index: Int): String?
		fun p2l_play(rig: Pointer, index: Int)
		fun p2l_mesh_count(rig: Pointer): Int
		fun p2l_mesh_id(rig: Pointer, index: Int): String?
		fun p2l_mesh_vertex_count(rig: Pointer, index: Int): Int
		fun p2l_mesh_vertices(rig: Pointer, index: Int): Pointer?
		fun p2l_mesh_opacity(rig: Pointer, index: Int): Float
		fun p2l_mesh_draw_order(rig: Pointer, index: Int): Float
		fun p2l_render_order(rig: Pointer, out: IntArray?, capacity: Int): Int
		fun p2l_canvas(rig: Pointer, width: FloatArray, height: FloatArray)
		fun p2l_parameter_range(rig: Pointer, index: Int, min: FloatArray?, max: FloatArray?, defaultValue: FloatArray?): Byte
		fun p2l_parameter_current(rig: Pointer): Pointer?
		fun p2l_behaviors(rig: Pointer, flags: Int)
		fun p2l_mesh_uvs(rig: Pointer, index: Int): Pointer?
		fun p2l_mesh_indices(rig: Pointer, index: Int, count: IntArray): Pointer?
		fun p2l_mesh_texture(rig: Pointer, index: Int): Int
		fun p2l_mesh_blend(rig: Pointer, index: Int, culling: ByteArray): Int
		fun p2l_mesh_masks(rig: Pointer, index: Int, out: IntArray?, capacity: Int, inverted: ByteArray?): Int
		fun p2l_mesh_colors(rig: Pointer, index: Int, multiply: FloatArray, screen: FloatArray)
		fun p2l_mesh_alpha_blend(rig: Pointer, index: Int): Int
		fun p2l_render_commands(rig: Pointer, out: IntArray?, capacity: Int): Int
		fun p2l_part_count(rig: Pointer): Int
		fun p2l_part_id(rig: Pointer, index: Int): String?
		fun p2l_part_group(rig: Pointer, index: Int, blend: IntArray?, alphaBlend: IntArray?, invertMask: ByteArray?): Int
		fun p2l_part_masks(rig: Pointer, index: Int, out: IntArray?, capacity: Int): Int
		fun p2l_part_composite(rig: Pointer, index: Int, opacity: FloatArray?, multiply: FloatArray?, screen: FloatArray?): Byte
		fun p2l_advanced_available(rig: Pointer): Int
		fun p2l_set_advanced(rig: Pointer, features: Int): Int
		fun p2l_sim_reset(rig: Pointer)
	}

	/** The library's build version, `major.minor.patch`. */
	public val version: String get() = native.p2l_version()

	/** The ABI the library implements, `major shl 16 or minor`. */
	public val abiVersion: Int get() = native.p2l_abi_version()

	/** Loads a compiled rig; throws [IllegalArgumentException] with the runtime's message when it is invalid. */
	public fun load(bytes: ByteArray): Rig {
		val error = ByteArray(512)
		val handle = native.p2l_rig_load(bytes, bytes.size.toLong(), error, error.size.toLong())
			?: throw IllegalArgumentException(String(error, 0, error.indexOf(0).coerceAtLeast(0), Charsets.UTF_8))
		return Rig(handle)
	}

	public fun load(ir: RigIR): Rig = load(P2lrt.write(ir))

	/** One loaded rig. Not thread-safe; [close] frees it. */
	public inner class Rig internal constructor(private var handle: Pointer?) : AutoCloseable {
		private fun h() = checkNotNull(handle) { "The rig is closed" }

		public val parameterIds: List<String> = List(native.p2l_parameter_count(h())) { native.p2l_parameter_id(h(), it)!! }
		public val meshIds: List<String> = List(native.p2l_mesh_count(h())) { native.p2l_mesh_id(h(), it)!! }
		public val clipIds: List<String> = List(native.p2l_clip_count(h())) { native.p2l_clip_id(h(), it)!! }
		public val partIds: List<String> = List(native.p2l_part_count(h())) { native.p2l_part_id(h(), it)!! }
		private val parameterIndex = parameterIds.withIndex().associate { it.value to it.index }

		/** The values the next update reads and writes, one per parameter. */
		public var values: FloatArray
			get() = native.p2l_parameter_values(h()).getFloatArray(0, parameterIds.size)
			set(value) { require(value.size == parameterIds.size) { "One value per parameter" }; native.p2l_parameter_values(h()).write(0, value, 0, value.size) }

		/** Sets the named parameters, leaving the others as they are. */
		public fun set(parameters: Map<String, Float>) {
			val v = values
			for ((id, value) in parameters) parameterIndex[id]?.let { v[it] = value }
			values = v
		}

		/** Why a call panicked inside the runtime, after which the rig answers nothing; null while it works. */
		public val failure: String? get() = native.p2l_rig_failure(h())

		/** Deforms the rig at [values]; throws [IllegalStateException] when the runtime fails on it. */
		public fun evaluate() {
			native.p2l_evaluate(h())
			checkWorking()
		}

		/** Advances clips and physics by [dt] seconds, then evaluates; throws as [evaluate] does. */
		public fun update(dt: Float) {
			native.p2l_update(h(), dt)
			checkWorking()
		}

		private fun checkWorking() {
			failure?.let { throw IllegalStateException("The PSD2Live runtime failed: $it") }
		}

		public fun resetPhysics(): Unit = native.p2l_physics_reset(h())

		/** Starts a clip by id, or stops with null. */
		public fun play(clip: String?) {
			native.p2l_play(h(), clip?.let { id -> clipIds.indexOf(id).also { require(it >= 0) { "Unknown clip: $id" } } } ?: -1)
		}

		/** Mesh [index]'s vertices from the last evaluation, canvas pixels with y down. */
		public fun vertices(index: Int): FloatArray =
			native.p2l_mesh_vertices(h(), index)?.getFloatArray(0, native.p2l_mesh_vertex_count(h(), index) * 2) ?: FloatArray(0)

		/**
		 * Mesh [index]'s vertices from the last evaluation as a view of the runtime's own memory (canvas pixels,
		 * y down, native byte order), valid until the next evaluation; null for a mesh without vertices.
		 */
		public fun verticesBuffer(index: Int): java.nio.ByteBuffer? {
			val count = native.p2l_mesh_vertex_count(h(), index)
			if (count == 0) return null
			return native.p2l_mesh_vertices(h(), index)?.getByteBuffer(0, count * 8L)?.order(java.nio.ByteOrder.nativeOrder())
		}

		public fun opacity(index: Int): Float = native.p2l_mesh_opacity(h(), index)
		public fun drawOrder(index: Int): Float = native.p2l_mesh_draw_order(h(), index)

		/** Mesh indices back to front. */
		public fun renderOrder(): IntArray {
			val n = native.p2l_render_order(h(), null, 0)
			return IntArray(n).also { native.p2l_render_order(h(), it, n) }
		}

		/**
		 * [renderOrder] with isolated groups kept: a mesh index, [beginGroup] of a part opening its isolated group,
		 * [END_GROUP] closing it (`p2l_render_commands`).
		 */
		public fun renderCommands(): IntArray {
			val n = native.p2l_render_commands(h(), null, 0)
			return IntArray(n).also { if (n > 0) native.p2l_render_commands(h(), it, n) }
		}

		/** The canvas size in pixels. */
		public val canvas: Pair<Float, Float> = FloatArray(1).let { w -> FloatArray(1).let { hh -> native.p2l_canvas(h(), w, hh); w[0] to hh[0] } }

		/** Each parameter's default, in [parameterIds] order. */
		public val defaults: FloatArray = FloatArray(parameterIds.size) { i ->
			FloatArray(1).also { native.p2l_parameter_range(h(), i, null, null, it) }[0]
		}

		/** The values the last evaluation used, after clips, behaviors, physics and simulation. */
		public val current: FloatArray
			get() = native.p2l_parameter_current(h())?.getFloatArray(0, parameterIds.size) ?: values

		/** Switches the runtime's own behaviors (1 blink, 2 breathing, 4 gaze, 8 lip sync); blinking and breathing start on. */
		public fun behaviors(flags: Int): Unit = native.p2l_behaviors(h(), flags)

		/** The advanced features the file offers (1 skin, 2 exact links, 4 simulation, 8 collision). */
		public val advancedAvailable: Int get() = native.p2l_advanced_available(h())

		/** Turns advanced [features] on, the rest off; returns those now on. */
		public fun setAdvanced(features: Int): Int = native.p2l_set_advanced(h(), features)

		public fun resetSimulation(): Unit = native.p2l_sim_reset(h())

		/** Mesh [index]'s static drawing data: texture coordinates (v down), triangles and how it draws. */
		public fun mesh(index: Int): Mesh {
			val vertices = native.p2l_mesh_vertex_count(h(), index)
			val count = IntArray(1)
			val indices = native.p2l_mesh_indices(h(), index, count)?.getIntArray(0, count[0]) ?: IntArray(0)
			val culling = ByteArray(1)
			val blend = native.p2l_mesh_blend(h(), index, culling)
			val inverted = ByteArray(1)
			val maskCount = native.p2l_mesh_masks(h(), index, null, 0, null)
			val masks = IntArray(maskCount).also { if (maskCount > 0) native.p2l_mesh_masks(h(), index, it, maskCount, inverted) else native.p2l_mesh_masks(h(), index, null, 0, inverted) }
			return Mesh(
				uvs = if (vertices == 0) FloatArray(0) else native.p2l_mesh_uvs(h(), index)?.getFloatArray(0, vertices * 2) ?: FloatArray(0),
				indices = indices, texture = native.p2l_mesh_texture(h(), index), blend = blend, culling = culling[0].toInt() != 0,
				masks = masks, invertMask = inverted[0].toInt() != 0, alphaBlend = native.p2l_mesh_alpha_blend(h(), index),
			)
		}

		/** Mesh [index]'s multiply and screen colors from the last evaluation, RGB each, into [multiply] and [screen]. */
		public fun colors(index: Int, multiply: FloatArray, screen: FloatArray): Unit = native.p2l_mesh_colors(h(), index, multiply, screen)

		/** Part [index]'s static grouping: how it groups its meshes and how its isolated group composites. */
		public fun part(index: Int): Part {
			val blend = IntArray(1)
			val alphaBlend = IntArray(1)
			val invert = ByteArray(1)
			val group = native.p2l_part_group(h(), index, blend, alphaBlend, invert)
			val maskCount = native.p2l_part_masks(h(), index, null, 0)
			val masks = IntArray(maskCount).also { if (maskCount > 0) native.p2l_part_masks(h(), index, it, maskCount) }
			return Part(group = group, blend = blend[0], alphaBlend = alphaBlend[0], invertMask = invert[0].toInt() != 0, masks = masks)
		}

		/**
		 * Part [index]'s isolated group at the last evaluation: its multiply and screen colors, RGB each, into
		 * [multiply] and [screen]; returns its opacity, NaN when there is no such part.
		 */
		public fun partComposite(index: Int, multiply: FloatArray, screen: FloatArray): Float {
			val opacity = FloatArray(1)
			return if (native.p2l_part_composite(h(), index, opacity, multiply, screen).toInt() != 0) opacity[0] else Float.NaN
		}

		override fun close() {
			handle?.let(native::p2l_rig_free)
			handle = null
		}
	}

	/**
	 * How a mesh draws: [blend] is 0 normal, 1 add, 2 multiply as Cubism draws them, 3 to 17 the extended modes
	 * (`p2l_mesh_blend`), [alphaBlend] 0 over, 1 atop, 2 out, 3 conjoint over, 4 disjoint over
	 * (`p2l_mesh_alpha_blend`); [masks] are the meshes that clip it, outside them when [invertMask].
	 */
	public class Mesh(
		public val uvs: FloatArray, public val indices: IntArray, public val texture: Int, public val blend: Int,
		public val culling: Boolean, public val masks: IntArray, public val invertMask: Boolean,
		public val alphaBlend: Int = 0,
	)

	/**
	 * How a part groups its meshes ([group]: [GROUP_PASS_THROUGH], [GROUP_SORTED] or [GROUP_ISOLATED]) and how its
	 * isolated group composites: blend modes as a mesh's, and the meshes masking it (the masking parts' included),
	 * outside them when [invertMask] (`p2l_part_group`, `p2l_part_masks`).
	 */
	public class Part(
		public val group: Int, public val blend: Int, public val alphaBlend: Int, public val invertMask: Boolean,
		public val masks: IntArray,
	)

	public companion object {
		public const val GROUP_PASS_THROUGH: Int = 0
		public const val GROUP_SORTED: Int = 1
		public const val GROUP_ISOLATED: Int = 2

		/** The render command closing an isolated group ([Rig.renderCommands]). */
		public const val END_GROUP: Int = -1

		/** The render command opening the isolated group of [part]. */
		public fun beginGroup(part: Int): Int = -2 - part

		/** The part whose group [command] opens, or -1 for a mesh or a group's end. */
		public fun groupPart(command: Int): Int = if (command <= -2) -2 - command else -1

		private val name = System.mapLibraryName("p2l_runtime")

		/** The native library file, or null when none is configured or found. */
		public fun locate(): File? {
			System.getProperty("psd2live.runtime.library")?.let { return File(it).takeIf(File::isFile) }
			System.getenv("PSD2LIVE_RUNTIME")?.takeIf { it.isNotBlank() }?.let { File(it) }?.let { f ->
				return if (f.isDirectory) File(f, name).takeIf(File::isFile) else f.takeIf(File::isFile)
			}
			System.getProperty("psd2live.runtime.dir")?.let { File(it, name) }?.takeIf(File::isFile)?.let { return it }
			// A packaged app keeps it among its resources.
			System.getProperty("compose.application.resources.dir")?.let { File(it, name) }?.takeIf(File::isFile)?.let { return it }
			return System.getProperty("java.library.path").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
				.map { File(it, name) }.firstOrNull(File::isFile)
		}

		/** The ABI these bindings need: the same major version and at least this minor one. */
		public const val ABI_MAJOR: Int = 1
		public const val ABI_MINOR: Int = 1

		/**
		 * The runtime from [library] (or [locate]d), or null when there is none or its ABI does not match the
		 * bindings' (a library without `p2l_abi_version` predates ABI 1.0).
		 */
		public fun load(library: File? = locate()): P2lRuntime? {
			library ?: return null
			val runtime = P2lRuntime(Native.load(library.absolutePath, Bindings::class.java))
			val abi = try {
				runtime.abiVersion
			} catch (_: UnsatisfiedLinkError) {
				return null
			}
			return runtime.takeIf { (abi ushr 16) == ABI_MAJOR && (abi and 0xffff) >= ABI_MINOR }
		}
	}
}

/** Deforms rigs with the native runtime, as a target bakes them; poses match the editor's evaluator. */
public class NativeGeometryEvaluator(private val runtime: P2lRuntime) : GeometryEvaluator {
	override fun open(ir: RigIR): GeometrySession {
		val rig = runtime.load(ir)
		val defaults = FloatArray(ir.parameters.size) { ir.parameters[it].default }
		val index = rig.parameterIds.withIndex().associate { it.value to it.index }
		return object : GeometrySession {
			override fun evaluate(parameters: Map<String, Float>): PoseGeometry {
				val values = defaults.copyOf()
				for ((id, v) in parameters) index[id]?.let { values[it] = v }
				rig.values = values
				rig.evaluate()
				val ids = rig.meshIds
				return PoseGeometry(
					ids.indices.associate { ids[it] to rig.vertices(it) },
					ids.indices.associate { ids[it] to rig.opacity(it) },
					ids.indices.associate { ids[it] to rig.drawOrder(it) })
			}

			override fun close() = rig.close()
		}
	}
}
