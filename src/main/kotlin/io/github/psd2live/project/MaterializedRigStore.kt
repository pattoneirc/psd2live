package io.github.psd2live.project

import io.github.psd2live.core.AuthoredRig
import io.github.psd2live.core.MaterializedRigCodec
import io.github.psd2live.core.PreviewRigSources
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.format.compile.RigIrObjects
import io.github.psd2live.history.WorkspaceHistoryState
import kotlinx.serialization.json.*
import java.io.IOException
import java.lang.ref.SoftReference
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The authored rig of each revision ([AuthoredRig]): what a preview builds from without generating its base or
 * replaying its journal, keyed by revision id.
 *
 * Two kinds of entry: those this process built (kept softly, the authored state derived from the build's sources on
 * first use) and those an opened archive stored (read and decoded on first use from its extracted `rig/` folder).
 * A save writes every revision it can into the archive's `rig/` folder:
 *
 * - `rig/objects/<sha256>.bin` - one [RigIrObjects] object, named by the SHA-256 of its bytes and shared between
 *   revisions;
 * - `rig/revisions/<revision>.json` - `{header, frame, deformers:[...], meshes:[...]}`: the [MaterializedRigCodec]
 *   header and the object hashes in rig order.
 *
 * The folder is optional: a revision without an entry, or with one that cannot be read, builds by generation and
 * replay as before. Builds that predate it unpack it with the rest of the archive and never read it.
 */
internal object MaterializedRigStore {
	const val FOLDER = "rig"
	private const val LIVE_CAPACITY = 48
	private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
	private val HASH = Regex("[0-9a-f]{64}")

	private val logger = System.getLogger("io.github.psd2live.project.MaterializedRigStore")
	private val json = Json { ignoreUnknownKeys = true }

	/** Off with `-Dpsd2live.materializedRigs=false`: builds neither use nor record authored rigs, saves write none. */
	val enabled: Boolean get() = System.getProperty("psd2live.materializedRigs")?.equals("false", ignoreCase = true) != true

	/** A revision's authored rig and the binding key of the atlas it was bound to. */
	class Entry(val authored: AuthoredRig, val bindingKey: String)

	private class Live(val overlay: RigEditOverlay, val sources: PreviewRigSources, val bindingKey: () -> String?) {
		@Volatile var encoded: MaterializedRigCodec.Encoded? = null
	}

	private class Stored(val folder: Path, val index: JsonObject) {
		@Volatile var decoded: SoftReference<Entry>? = null
	}

	private val live = object : LinkedHashMap<String, SoftReference<Live>>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SoftReference<Live>>?) = size > LIVE_CAPACITY
	}
	private val stored = HashMap<String, Stored>()

	/**
	 * Records that [revision]'s preview was built with [sources] for [overlay]. [bindingKey] gives the binding key of
	 * the atlas the base was bound to, when a save needs it and [sources] does not know it.
	 */
	fun remember(revision: String, overlay: RigEditOverlay, sources: PreviewRigSources, bindingKey: () -> String?) {
		if (!enabled) return
		synchronized(live) { live[key(revision)] = SoftReference(Live(overlay, sources, bindingKey)) }
	}

	/** [revision]'s authored rig, built in this process or stored by an opened archive; null when neither has it. */
	fun lookup(revision: String): Entry? {
		if (!enabled) return null
		synchronized(live) { live[key(revision)]?.get() }?.let { entry ->
			val bindingKey = entry.sources.bindingKey ?: return@let
			return Entry(entry.sources.authored(entry.overlay), bindingKey)
		}
		val entry = synchronized(stored) { stored[revision] } ?: return null
		entry.decoded?.get()?.let { return it }
		return try {
			decode(entry).also { entry.decoded = SoftReference(it) }
		} catch (failure: Exception) {
			logger.log(System.Logger.Level.WARNING, "Stored authored rig of $revision not used", failure)
			synchronized(stored) { stored.remove(revision) }
			null
		}
	}

	/** Registers the authored rigs an archive extracted to [root] stores. Never fails: a damaged folder is skipped. */
	fun adopt(root: Path) {
		val folder = root.resolve(FOLDER)
		if (!enabled || !Files.isDirectory(folder.resolve("revisions"))) return
		try {
			Files.list(folder.resolve("revisions")).use { files ->
				for (file in files) {
					val name = file.fileName.toString()
					if (!name.endsWith(".json") || !name.removeSuffix(".json").matches(SAFE_NAME)) continue
					val index = json.parseToJsonElement(Files.readString(file)).jsonObject
					synchronized(stored) { stored[name.removeSuffix(".json")] = Stored(folder, index) }
				}
			}
		} catch (failure: Exception) {
			logger.log(System.Logger.Level.WARNING, "Stored authored rigs not read", failure)
		}
	}

	/** Writes the authored rig of every revision of [history] this store has into [root]'s `rig/` folder. */
	fun write(root: Path, history: WorkspaceHistoryState<WorkspaceDocument>) {
		if (!enabled) return
		val folder = root.resolve(FOLDER)
		val written = HashSet<String>()
		for (selection in history.selections) {
			// The key builds look the rig up by: a history node of an older build may carry a revision id of another form.
			val revision = WorkspaceRevisions.of(selection.snapshot)
			if (!revision.matches(SAFE_NAME) || !written.add(revision)) continue
			try {
				val index = storedIndex(revision, folder) ?: liveIndex(revision, folder) ?: continue
				Files.createDirectories(folder.resolve("revisions"))
				Files.writeString(folder.resolve("revisions/$revision.json"), index.toString())
			} catch (failure: Exception) {
				logger.log(System.Logger.Level.WARNING, "Authored rig of $revision not saved", failure)
			}
		}
	}

	/** Forgets every entry: for tests that need a cold store. */
	fun clear() {
		synchronized(live) { live.clear() }
		synchronized(stored) { stored.clear() }
	}

	private fun key(revision: String) = "$revision|${io.github.psd2live.i18n.I18n.currentLanguage.tag}"

	private fun storedIndex(revision: String, target: Path): JsonObject? {
		val entry = synchronized(stored) { stored[revision] } ?: return null
		for (hash in hashes(entry.index)) {
			val destination = target.resolve("objects/$hash.bin")
			if (Files.exists(destination)) continue
			Files.createDirectories(destination.parent)
			val source = entry.folder.resolve("objects/$hash.bin")
			if (!Files.isRegularFile(source)) return null
			try { Files.createLink(destination, source) } catch (_: Exception) { Files.copy(source, destination) }
		}
		return entry.index
	}

	private fun liveIndex(revision: String, target: Path): JsonObject? {
		val entry = synchronized(live) { live[key(revision)]?.get() }
			?: synchronized(live) { live.entries.firstOrNull { it.key.startsWith("$revision|") }?.value?.get() } ?: return null
		val encoded = entry.encoded ?: run {
			val bindingKey = entry.sources.bindingKey ?: entry.bindingKey() ?: return null
			MaterializedRigCodec.encode(entry.sources.authored(entry.overlay), bindingKey).also { entry.encoded = it }
		}
		fun objectHash(bytes: ByteArray): String {
			val hash = sha256(bytes)
			val path = target.resolve("objects/$hash.bin")
			if (!Files.exists(path)) { Files.createDirectories(path.parent); Files.write(path, bytes) }
			return hash
		}
		return buildJsonObject {
			put("header", encoded.header)
			put("frame", objectHash(encoded.objects.frame))
			put("deformers", JsonArray(encoded.objects.deformers.map { JsonPrimitive(objectHash(it)) }))
			put("meshes", JsonArray(encoded.objects.meshes.map { JsonPrimitive(objectHash(it)) }))
		}
	}

	private fun hashes(index: JsonObject): List<String> = (listOf(index.getValue("frame").jsonPrimitive.content) +
		index.getValue("deformers").jsonArray.map { it.jsonPrimitive.content } + index.getValue("meshes").jsonArray.map { it.jsonPrimitive.content })
		.onEach { require(it.matches(HASH)) { "Invalid stored rig object name" } }

	private fun decode(entry: Stored): Entry {
		fun read(hash: String): ByteArray {
			require(hash.matches(HASH)) { "Invalid stored rig object name" }
			val bytes = Files.readAllBytes(entry.folder.resolve("objects/$hash.bin"))
			if (sha256(bytes) != hash) throw IOException("Stored rig object checksum mismatch: $hash")
			return bytes
		}
		val index = entry.index
		val objects = RigIrObjects.Objects(read(index.getValue("frame").jsonPrimitive.content),
			index.getValue("deformers").jsonArray.map { read(it.jsonPrimitive.content) },
			index.getValue("meshes").jsonArray.map { read(it.jsonPrimitive.content) })
		val decoded = MaterializedRigCodec.decode(index.getValue("header").jsonObject, objects)
		return Entry(decoded.authored, decoded.bindingKey)
	}

	private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
