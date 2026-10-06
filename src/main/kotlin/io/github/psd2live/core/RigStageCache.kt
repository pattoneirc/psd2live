package io.github.psd2live.core

import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The outputs of the rig builder's stages, each kept under a content key of only what that stage reads.
 *
 * [RigBuilder.build] runs as stages - per-layer mesh footprints, the scaffold (anchors, face rig, frames, body
 * and face deformers), `mesh:<layer>` (one layer's mesh, mouth outline and keyforms), assembly, [UvBinding] and
 * the skeleton - and asks this cache for each stage's output before computing it. A key holds the stage's inputs
 * by value (layer metadata, a raster's digest, the settings the stage reads), never the document as a whole, so
 * a change reruns only the stages that read it: one layer's mesh setting reruns that layer's mesh, a role change
 * of a body layer leaves the face layers' meshes alone. Every stage is a pure function of its key, so a build
 * through the cache equals a build without it ([RigStageCacheTest]).
 *
 * Each kind keeps its most recent entries (least recently used out). Held per pipeline, next to
 * [PreviewMeshCache]; a pipeline without one builds every stage.
 */
internal class RigStageCache {
	private class Kind(val capacity: Int) {
		val entries = object : LinkedHashMap<Any, Any>(16, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Any>?) = size > capacity
		}
		val hits = AtomicLong()
		val misses = AtomicLong()
	}

	private val kinds = ConcurrentHashMap<String, Kind>()

	/** The output of stage [kind] for [key], computed by [build] (outside any lock) when not kept. */
	@Suppress("UNCHECKED_CAST")
	fun <T : Any> get(kind: String, capacity: Int, key: Any, build: () -> T): T {
		val entries = kinds.computeIfAbsent(kind) { Kind(capacity) }
		synchronized(entries) { entries.entries[key] }?.let { entries.hits.incrementAndGet(); return it as T }
		entries.misses.incrementAndGet()
		val value = build()
		synchronized(entries) { entries.entries[key] = value }
		return value
	}

	fun hits(kind: String): Long = kinds[kind]?.hits?.get() ?: 0L
	fun misses(kind: String): Long = kinds[kind]?.misses?.get() ?: 0L

	fun clear() = kinds.values.forEach { synchronized(it) { it.entries.clear() } }

	companion object {
		/** Off: every stage runs, as before stages were cached (`-Dpsd2live.rigStages=false`; the measurement tools compare both). */
		@Volatile var enabled: Boolean = System.getProperty("psd2live.rigStages") != "false"

		const val FOOTPRINT = "footprint"
		const val DEFORMERS = "deformers"
		const val MESH = "mesh"
		const val LEAN = "lean"
		const val CHANNELS = "channels"
		const val LIPS = "lips"
		const val BOUND = "bound"
	}
}

/**
 * SHA-256 of a raster's pixels, once per array: rasters are immutable once built (every producer fills a new
 * array), so a raster that keeps its array across rebuilds is hashed once.
 */
internal object RasterDigest {
	private val digests = Collections.synchronizedMap(WeakHashMap<ByteArray, String>())

	fun of(rgba: ByteArray): String = digests[rgba] ?: compute(rgba).also { digests[rgba] = it }

	/** The digest computed afresh, without the per-array memo. */
	fun compute(rgba: ByteArray): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rgba))
}
