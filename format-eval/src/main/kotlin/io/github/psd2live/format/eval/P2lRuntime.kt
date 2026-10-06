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
 * directory), the `psd2live.runtime.dir` system property, or the library path.
 */
public class P2lRuntime private constructor(private val native: Bindings) {
	@Suppress("FunctionName", "LocalVariableName")
	private interface Bindings : Library {
		fun p2l_rig_load(bytes: ByteArray, len: Long, error: ByteArray, errorCapacity: Long): Pointer?
		fun p2l_rig_free(rig: Pointer)
		fun p2l_version(): String
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
	}

	/** The runtime's version, `major.minor.patch`. */
	public val version: String get() = native.p2l_version()

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

		public fun evaluate(): Unit = native.p2l_evaluate(h())

		/** Advances clips and physics by [dt] seconds, then evaluates. */
		public fun update(dt: Float): Unit = native.p2l_update(h(), dt)

		public fun resetPhysics(): Unit = native.p2l_physics_reset(h())

		/** Starts a clip by id, or stops with null. */
		public fun play(clip: String?) {
			native.p2l_play(h(), clip?.let { id -> clipIds.indexOf(id).also { require(it >= 0) { "Unknown clip: $id" } } } ?: -1)
		}

		/** Mesh [index]'s vertices from the last evaluation, canvas pixels with y down. */
		public fun vertices(index: Int): FloatArray =
			native.p2l_mesh_vertices(h(), index)?.getFloatArray(0, native.p2l_mesh_vertex_count(h(), index) * 2) ?: FloatArray(0)

		public fun opacity(index: Int): Float = native.p2l_mesh_opacity(h(), index)
		public fun drawOrder(index: Int): Float = native.p2l_mesh_draw_order(h(), index)

		/** Mesh indices back to front. */
		public fun renderOrder(): IntArray {
			val n = native.p2l_render_order(h(), null, 0)
			return IntArray(n).also { native.p2l_render_order(h(), it, n) }
		}

		override fun close() {
			handle?.let(native::p2l_rig_free)
			handle = null
		}
	}

	public companion object {
		private val name = System.mapLibraryName("p2l_runtime")

		/** The native library file, or null when none is configured or found. */
		public fun locate(): File? {
			System.getProperty("psd2live.runtime.library")?.let { return File(it).takeIf(File::isFile) }
			System.getenv("PSD2LIVE_RUNTIME")?.takeIf { it.isNotBlank() }?.let { File(it) }?.let { f ->
				return if (f.isDirectory) File(f, name).takeIf(File::isFile) else f.takeIf(File::isFile)
			}
			System.getProperty("psd2live.runtime.dir")?.let { File(it, name) }?.takeIf(File::isFile)?.let { return it }
			return System.getProperty("java.library.path").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
				.map { File(it, name) }.firstOrNull(File::isFile)
		}

		/** The runtime from [library] (or [locate]d), or null when there is none. */
		public fun load(library: File? = locate()): P2lRuntime? {
			library ?: return null
			return P2lRuntime(Native.load(library.absolutePath, Bindings::class.java))
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
