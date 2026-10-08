package io.github.psd2live.project

import io.github.psd2live.core.AuthoredRig
import io.github.psd2live.core.MaterializedRigCodec
import io.github.psd2live.core.PreviewRigSources
import io.github.psd2live.core.RigCheckpoint
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigObjects
import io.github.psd2live.history.WorkspaceHistoryState
import kotlinx.serialization.json.*
import java.lang.ref.SoftReference
import java.nio.file.Files
import java.nio.file.Path

/**
 * The authored rig of each revision ([AuthoredRig]) a preview can build from without generating its base or replaying
 * its journal, keyed by revision id: those this process built (kept softly, the authored state derived from the
 * build's sources on first use) and those an opened archive stored.
 *
 * In an archive: `rig/revisions/<revision>.json` holds a [MaterializedRigCodec] index whose objects are in
 * `rig/objects/` ([RigObjects]), shared with every other revision and with the `rig_checkpoint` records
 * ([RigCheckpoint]) whose objects a save always writes there too. The revision entries are optional: a revision
 * without one, or with one that cannot be read, builds by generation and replay.
 */
internal object MaterializedRigStore {
	const val FOLDER = "rig"
	private const val LIVE_CAPACITY = 48
	private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

	private val logger = System.getLogger("io.github.psd2live.project.MaterializedRigStore")
	private val json = Json { ignoreUnknownKeys = true }

	/** Off with `-Dpsd2live.materializedRigs=false`: builds neither use nor record authored rigs, saves write none. */
	val enabled: Boolean get() = System.getProperty("psd2live.materializedRigs")?.equals("false", ignoreCase = true) != true

	/** A revision's authored rig and the binding key of the atlas it was bound to. */
	class Entry(val authored: AuthoredRig, val bindingKey: String)

	private class Live(val overlay: RigEditOverlay, val sources: PreviewRigSources, val bindingKey: () -> String?) {
		@Volatile var index: JsonObject? = null
	}

	private class Stored(val index: JsonObject) {
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
			val decoded = MaterializedRigCodec.fromIndex(entry.index)
			Entry(decoded.authored, decoded.bindingKey).also { entry.decoded = SoftReference(it) }
		} catch (failure: Exception) {
			logger.log(System.Logger.Level.WARNING, "Stored authored rig of $revision not used", failure)
			synchronized(stored) { stored.remove(revision) }
			null
		}
	}

	/** Registers the rig objects and authored rigs an archive extracted to [root] holds. A damaged index is skipped. */
	fun adopt(root: Path) {
		val folder = root.resolve(FOLDER)
		RigObjects.addFolder(root.resolve(ProjectFormatV2.RIG_OBJECTS))
		if (!enabled || !Files.isDirectory(folder.resolve("revisions"))) return
		try {
			Files.list(folder.resolve("revisions")).use { files ->
				for (file in files) {
					val name = file.fileName.toString()
					if (!name.endsWith(".json") || !name.removeSuffix(".json").matches(SAFE_NAME)) continue
					val index = json.parseToJsonElement(Files.readString(file)).jsonObject
					synchronized(stored) { stored[name.removeSuffix(".json")] = Stored(index) }
				}
			}
		} catch (failure: Exception) {
			logger.log(System.Logger.Level.WARNING, "Stored authored rigs not read", failure)
		}
	}

	/**
	 * Writes into [root] the rig objects [history]'s checkpoints name - required: a missing one fails the save - and
	 * the authored rig of every revision this store has.
	 */
	fun write(root: Path, history: WorkspaceHistoryState<WorkspaceDocument>) {
		val objects = root.resolve(ProjectFormatV2.RIG_OBJECTS)
		val required = history.selections.flatMap { RigCheckpoint.hashes(it.snapshot.rigEdits.authoringJournal) }
		if (required.isNotEmpty()) RigObjects.writeTo(objects, required)
		if (!enabled) return
		val written = HashSet<String>()
		for (selection in history.selections) {
			// The key builds look the rig up by: a history node of an older build may carry a revision id of another form.
			val revision = WorkspaceRevisions.of(selection.snapshot)
			if (!revision.matches(SAFE_NAME) || !written.add(revision)) continue
			try {
				val index = synchronized(stored) { stored[revision] }?.index ?: liveIndex(revision) ?: continue
				RigObjects.writeTo(objects, MaterializedRigCodec.hashes(index))
				Files.createDirectories(root.resolve("$FOLDER/revisions"))
				Files.writeString(root.resolve("$FOLDER/revisions/$revision.json"), index.toString())
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

	private fun liveIndex(revision: String): JsonObject? {
		val entry = synchronized(live) { live[key(revision)]?.get() ?: live.entries.firstOrNull { it.key.startsWith("$revision|") }?.value?.get() }
			?: return null
		entry.index?.let { return it }
		val bindingKey = entry.sources.bindingKey ?: entry.bindingKey() ?: return null
		return MaterializedRigCodec.index(entry.sources.authored(entry.overlay), bindingKey).also { entry.index = it }
	}
}
