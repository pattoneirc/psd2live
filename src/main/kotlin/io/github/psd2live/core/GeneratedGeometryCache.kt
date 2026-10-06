package io.github.psd2live.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.format.art.SourceArt
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.lang.ref.SoftReference

/**
 * The last few rigs generated from a saved generation source, before their UVs are moved onto the current
 * texture atlas.
 *
 * Once a project keeps a separate generation input, painting changes only the current pixels, so the
 * geometry rig (meshes, skeleton skinning, deformers) is identical across paint commits and is the
 * dominant rebuild cost. The key holds every generation input exactly; the only relaxation is the
 * point payload of `canvas_geometry` journal entries, which no generation stage reads (it is replayed
 * afterwards onto the generated base), and generated overrides, which merge after every generator.
 * Entry order and ids stay in the key.
 *
 * Undo, redo and history checkout return to recent inputs, so a few entries are kept, least recently used
 * first out. The keys compare structurally (a string hash of a [PipelineConfig] would not be canonical), and
 * each entry holds its geometry atlas pages, so only the newest is held strongly: older ones are softly
 * reachable and give way under memory pressure.
 */
internal class GeneratedGeometryCache {
	internal class Entry(val atlas: PackedAtlas, val rig: BuiltRig)

	private data class LayerKey(
		val type: Class<*>, val id: String, val name: String, val groupPath: String, val kind: Any, val visible: Boolean,
		val idIsStable: Boolean, val order: Int, val bounds: Any, val opacity: Float, val clipped: Boolean, val blend: Any,
		val channelMask: Any, val width: Int, val height: Int, val digest: String,
	)

	private data class GroupKey(
		val path: String, val name: String, val visible: Boolean, val opacity: Float, val clipped: Boolean,
		val blend: Any, val passThrough: Boolean,
	)

	private data class Key(
		val width: Int, val height: Int, val groups: List<GroupKey>, val layers: List<LayerKey>,
		val generation: PipelineConfig, val baseline: PipelineConfig,
	)

	private var newest: Pair<Key, Entry>? = null
	private val older = object : LinkedHashMap<Key, SoftReference<Entry>>(8, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, SoftReference<Entry>>?) = size > CAPACITY - 1
	}

	@Synchronized
	fun getOrPut(geometry: SourceArt, generationConfig: PipelineConfig, baselineConfig: PipelineConfig, build: () -> Entry): Entry {
		val key = Key(geometry.widthPx, geometry.heightPx,
			geometry.groups.map { GroupKey(it.path, it.name, it.visible, it.opacity, it.clipped, it.blend, it.passThrough) },
			geometry.layers.map { layer ->
				LayerKey(layer.javaClass, layer.id.raw, layer.name, layer.groupPath, layer.kind, layer.visible, layer.idIsStable,
					layer.order, layer.bounds, layer.opacity, layer.clipped, layer.blend, layer.channelMask,
					layer.raster.width, layer.raster.height, digest(layer.raster.rgba))
			},
			withoutGeometryPoints(generationConfig), withoutGeometryPoints(baselineConfig))
		newest?.let { (cached, value) -> if (cached == key) return value }
		val value = older.remove(key)?.get() ?: build()
		newest?.let { (previous, entry) -> older[previous] = SoftReference(entry) }
		newest = key to value
		return value
	}

	companion object {
		/** Entries kept: the newest and up to this many less one older ones. */
		private const val CAPACITY = 4
		private val digests = Collections.synchronizedMap(WeakHashMap<ByteArray, String>())

		/** Saved generation rasters keep their identity across rebuilds, so each is hashed once. */
		private fun digest(rgba: ByteArray): String = digests.getOrPut(rgba) {
			java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rgba))
		}

		private fun withoutGeometryPoints(config: PipelineConfig): PipelineConfig {
			val journal = config.rigEdits.authoringJournal
			if (journal.none { it.isCanvasGeometry() || GeneratedOverrides.isOverride(it) }) return config
			// Generated overrides merge after every generator, so no generation stage reads them either.
			return config.copy(rigEdits = config.rigEdits.copy(authoringJournal = journal.filterNot(GeneratedOverrides::isOverride).map { command ->
				// Version 1 holds absolute points, version 2 quantized deltas (CanvasGeometryJournal).
				if (command.isCanvasGeometry()) JsonObject(command - GEOMETRY_PAYLOAD) else command
			}))
		}

		private val GEOMETRY_PAYLOAD = setOf("points", "d", "i", "q")

		private fun JsonObject.isCanvasGeometry() = this["op"]?.jsonPrimitive?.contentOrNull == "canvas_geometry"
	}
}
