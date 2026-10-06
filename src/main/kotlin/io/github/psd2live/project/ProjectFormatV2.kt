package io.github.psd2live.project

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The v2 layout of a `.psd2live` archive, and its conversion to and from the working store.
 *
 * The working store (and a v1 archive) keeps one self-contained JSON snapshot per history revision. v2 splits
 * each snapshot into content-addressed document nodes by kind - source art, layer state, settings, rig
 * definitions, the authoring journal, generator overrides and motion clips - so revisions share every part
 * they did not change and each part carries its own schema version:
 *
 * | path | content |
 * | --- | --- |
 * | `history/HEAD.json`, `history/nodes/` | the current node, node order and immutable node metadata (as v1) |
 * | `history/revisions/<key>.json` | per revision: the hash of each document node it is made of |
 * | `document/nodes/<kind>/<sha256>.json` | document nodes, `{schema, kind, value}` |
 * | `document/overrides/<sha256>.json` | generator overrides with their place in the journal |
 * | `document/clips/<sha256>.json` | motion clips and generated-motion settings |
 * | `assets/` | deduplicated rasters (PNG) |
 * | `auxiliary/` | staged assets, views, workflow records and tasks |
 *
 * A node's file name is the SHA-256 of its bytes. Splitting is lossless: unpacking rebuilds each snapshot with
 * the same content, so revision ids and history survive a v1 → v2 migration. The rig model is never stored;
 * opening rebuilds it from the document as before.
 */
internal object ProjectFormatV2 {
	const val VERSION = 2
	const val NODE_SCHEMA = 1
	const val REVISION_SCHEMA = 1

	private val json = Json { ignoreUnknownKeys = true }

	private val sourceKeys = listOf("canvasWidth", "canvasHeight", "groups", "layers")
	private val layerKeys = listOf("layerVisibility", "deletedLayerIds", "layerOverrides", "parentOverrides", "meshOverrides")
	private val sourceParts = mapOf("generationSource" to "generation-source", "meshSource" to "mesh-source", "placementSource" to "placement-source")
	private val clipKeys = listOf("motions", "motionPresets")
	private const val JOURNAL = "authoringJournal"
	private const val OVERRIDE = "generated_override"
	private val auxiliaryFolders = listOf("assets", "views", "view-images", "workflow")

	/** Moves the working-store layout under [root]/workspace/[projectId] into the v2 layout under [root]. */
	fun pack(root: Path, projectId: String) {
		val workspace = root.resolve("workspace")
		val store = workspace.resolve(projectId)
		require(Files.isRegularFile(store.resolve("HEAD.json"))) { "Project has no history" }
		val history = root.resolve("history")
		move(store.resolve("HEAD.json"), history.resolve("HEAD.json"))
		moveTree(store.resolve("history/nodes"), history.resolve("nodes"))
		val snapshots = store.resolve("history/snapshots")
		if (Files.isDirectory(snapshots)) Files.list(snapshots).use { paths -> paths.sorted().toList() }.forEach { file ->
			val index = split(root, json.parseToJsonElement(Files.readString(file)).jsonObject)
			write(history.resolve("revisions").resolve(file.fileName.toString()), index.toString().encodeToByteArray())
			Files.delete(file)
		}
		moveTree(store.resolve("blobs"), root.resolve("assets"))
		for (folder in auxiliaryFolders) moveTree(store.resolve(folder), root.resolve("auxiliary").resolve(folder))
		if (Files.isRegularFile(store.resolve("tasks.json"))) move(store.resolve("tasks.json"), root.resolve("auxiliary/tasks.json"))
		val left = Files.walk(store).use { paths -> paths.filter(Files::isRegularFile).map { store.relativize(it).toString() }.toList() }
		require(left.isEmpty()) { "Unknown project store entries: $left" }
		deleteTree(workspace)
	}

	/** Rebuilds the working-store layout under [root]/workspace/[projectId] from an extracted v2 archive. */
	fun unpack(root: Path, projectId: String) {
		val history = root.resolve("history")
		require(Files.isRegularFile(history.resolve("HEAD.json"))) { "Project has no history" }
		val store = root.resolve("workspace").resolve(projectId)
		require(!Files.exists(store)) { "Project store entries in a v2 archive" }
		val revisions = history.resolve("revisions")
		val cache = HashMap<String, JsonObject>()
		if (Files.isDirectory(revisions)) Files.list(revisions).use { paths -> paths.sorted().toList() }.forEach { file ->
			val snapshot = join(root, json.parseToJsonElement(Files.readString(file)).jsonObject, cache)
			write(store.resolve("history/snapshots").resolve(file.fileName.toString()), snapshot.toString().encodeToByteArray())
		}
		move(history.resolve("HEAD.json"), store.resolve("HEAD.json"))
		moveTree(history.resolve("nodes"), store.resolve("history/nodes"))
		moveTree(root.resolve("assets"), store.resolve("blobs"))
		for (folder in auxiliaryFolders) moveTree(root.resolve("auxiliary").resolve(folder), store.resolve(folder))
		if (Files.isRegularFile(root.resolve("auxiliary/tasks.json"))) move(root.resolve("auxiliary/tasks.json"), store.resolve("tasks.json"))
		deleteTree(history); deleteTree(root.resolve("document")); deleteTree(root.resolve("auxiliary"))
	}

	/** Writes [snapshot]'s parts as content-addressed nodes under [root] and returns the revision index naming them. */
	internal fun split(root: Path, snapshot: JsonObject): JsonObject {
		val nodes = LinkedHashMap<String, String>()
		var overrides: String? = null
		var clips: String? = null
		fun subset(keys: List<String>) = JsonObject(snapshot.filterKeys { it in keys })
		subset(sourceKeys).takeIf { it.isNotEmpty() }?.let { nodes["source"] = node(root, "source", it) }
		subset(layerKeys).takeIf { it.isNotEmpty() }?.let { nodes["layers"] = node(root, "layers", it) }
		subset(listOf("settings")).takeIf { it.isNotEmpty() }?.let { nodes["settings"] = node(root, "settings", it) }
		for ((key, kind) in sourceParts) snapshot[key]?.let { nodes[kind] = node(root, kind, JsonObject(mapOf(key to it))) }
		snapshot["rigEdits"]?.jsonObject?.let { rig ->
			nodes["rig"] = node(root, "rig", JsonObject(mapOf("rigEdits" to JsonObject(rig.filterKeys { it != JOURNAL && it !in clipKeys }))))
			rig[JOURNAL]?.jsonArray?.let { journal ->
				val (generated, authored) = journal.withIndex().partition { (_, command) ->
					(command as? JsonObject)?.get("op")?.jsonPrimitive?.contentOrNull == OVERRIDE
				}
				nodes["journal"] = node(root, "journal", buildJsonObject { put("entries", JsonArray(authored.map { it.value })) })
				if (generated.isNotEmpty()) overrides = content(root, "document/overrides", buildJsonObject {
					put("schema", NODE_SCHEMA); put("kind", "overrides")
					putJsonArray("entries") { generated.forEach { (index, command) -> addJsonObject { put("index", index); put("command", command) } } }
				})
			}
			JsonObject(rig.filterKeys { it in clipKeys }).takeIf { it.isNotEmpty() }?.let { value ->
				clips = content(root, "document/clips", buildJsonObject { put("schema", NODE_SCHEMA); put("kind", "clips"); put("value", value) })
			}
		}
		val known = sourceKeys + layerKeys + "settings" + sourceParts.keys + "rigEdits"
		nodes["document"] = node(root, "document", JsonObject(snapshot.filterKeys { it !in known }))
		return buildJsonObject {
			put("schema", REVISION_SCHEMA)
			put("nodes", JsonObject(nodes.mapValues { JsonPrimitive(it.value) }))
			overrides?.let { put("overrides", it) }
			clips?.let { put("clips", it) }
		}
	}

	/** The snapshot a revision [index] names, read from the nodes under [root]. */
	internal fun join(root: Path, index: JsonObject, cache: MutableMap<String, JsonObject> = HashMap()): JsonObject {
		require(index["schema"]?.jsonPrimitive?.intOrNull == REVISION_SCHEMA) { "Unsupported revision schema" }
		val nodes = index.getValue("nodes").jsonObject.mapValues { it.value.jsonPrimitive.content }
		val unknown = nodes.keys - (listOf("source", "layers", "settings", "rig", "journal", "document") + sourceParts.values).toSet()
		require(unknown.isEmpty()) { "Unsupported document nodes: $unknown" }
		fun load(folder: String, kind: String, hash: String): JsonObject {
			val value = cache.getOrPut("$folder/$hash") { read(root, folder, hash) }
			require(value["schema"]?.jsonPrimitive?.intOrNull == NODE_SCHEMA && value["kind"]?.jsonPrimitive?.contentOrNull == kind) {
				"Unsupported document node: $kind $hash"
			}
			return value
		}
		fun value(kind: String) = nodes[kind]?.let { load("document/nodes/$kind", kind, it).getValue("value").jsonObject }
		val snapshot = LinkedHashMap<String, JsonElement>()
		value("document")?.let(snapshot::putAll)
		for (kind in listOf("settings", "source")) value(kind)?.let(snapshot::putAll)
		for (kind in sourceParts.values) value(kind)?.let(snapshot::putAll)
		value("layers")?.let(snapshot::putAll)
		val rig = value("rig")?.getValue("rigEdits")?.jsonObject
		if (rig != null) {
			val edits = LinkedHashMap<String, JsonElement>(rig)
			val authored = value("journal")?.getValue("entries")?.jsonArray
			val generated = index["overrides"]?.jsonPrimitive?.content?.let { load("document/overrides", "overrides", it).getValue("entries").jsonArray }
			if (authored != null || generated != null) {
				val placed = generated.orEmpty().associate { it.jsonObject.getValue("index").jsonPrimitive.int to it.jsonObject.getValue("command") }
				val size = authored.orEmpty().size + generated.orEmpty().size
				require(placed.size == generated.orEmpty().size && placed.keys.all { it in 0 until size }) { "Invalid override positions" }
				val remaining = authored.orEmpty().iterator()
				edits[JOURNAL] = JsonArray(List(size) { i -> placed[i] ?: remaining.next() })
			}
			index["clips"]?.jsonPrimitive?.content?.let { edits.putAll(load("document/clips", "clips", it).getValue("value").jsonObject) }
			snapshot["rigEdits"] = JsonObject(edits)
		} else require(nodes["journal"] == null && index["overrides"] == null && index["clips"] == null) { "Journal without rig definitions" }
		return JsonObject(snapshot)
	}

	private fun node(root: Path, kind: String, value: JsonObject): String =
		content(root, "document/nodes/$kind", buildJsonObject { put("schema", NODE_SCHEMA); put("kind", kind); put("value", value) })

	/** Writes [value] under [folder] named by the SHA-256 of its bytes, once. */
	private fun content(root: Path, folder: String, value: JsonObject): String {
		val bytes = value.toString().encodeToByteArray()
		val hash = sha256(bytes)
		val path = root.resolve(folder).resolve("$hash.json")
		if (!Files.exists(path)) write(path, bytes)
		return hash
	}

	private fun read(root: Path, folder: String, hash: String): JsonObject {
		require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid document node reference" }
		val path = root.resolve(folder).resolve("$hash.json")
		require(Files.isRegularFile(path)) { "Document node is missing: $folder/$hash" }
		val bytes = Files.readAllBytes(path)
		require(sha256(bytes) == hash) { "Document node checksum mismatch: $folder/$hash" }
		return json.parseToJsonElement(bytes.decodeToString()).jsonObject
	}

	/** The format version of the archive at [file], or null when it is not a readable project archive. */
	fun versionOf(file: Path): Int? = runCatching {
		java.util.zip.ZipFile(file.toFile()).use { zip ->
			val entry = zip.getEntry("manifest.json") ?: return null
			val manifest = zip.getInputStream(entry).use { json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
			manifest["version"]?.jsonPrimitive?.intOrNull.takeIf { manifest["format"]?.jsonPrimitive?.contentOrNull == "PSD2Live" }
		}
	}.getOrNull()

	/**
	 * Before a v2 save replaces the v1 project at [target], keeps a copy of it beside it as `<name>.v1.psd2live`,
	 * once: the migration never destroys the only v1 file. Returns the backup made, if any.
	 */
	fun backupV1(target: Path): Path? {
		if (!Files.isRegularFile(target) || versionOf(target) != 1) return null
		val name = target.fileName.toString()
		val stem = if (name.endsWith(".psd2live", ignoreCase = true)) name.dropLast(".psd2live".length) else name
		val backup = target.resolveSibling("$stem.v1.psd2live")
		if (Files.exists(backup)) return null
		Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES)
		return backup
	}

	private fun write(path: Path, bytes: ByteArray) {
		Files.createDirectories(path.parent)
		Files.write(path, bytes)
	}

	private fun move(from: Path, to: Path) {
		Files.createDirectories(to.parent)
		Files.move(from, to)
	}

	private fun moveTree(from: Path, to: Path) {
		if (!Files.isDirectory(from)) return
		Files.walk(from).use { paths -> paths.filter(Files::isRegularFile).toList() }.forEach { file -> move(file, to.resolve(from.relativize(file).toString())) }
		deleteTree(from)
	}

	private fun deleteTree(path: Path) {
		if (!Files.exists(path)) return
		Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
	}

	private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
