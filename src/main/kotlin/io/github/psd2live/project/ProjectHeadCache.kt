package io.github.psd2live.project

import io.github.psd2live.core.SkeletonRig
import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.RigIrBinary
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The optional, non-authoritative `cache/head/` folder of a v2 archive: expensive intermediate results of
 * rebuilding the head revision, so opening the project can skip recomputing them. Replay stays the source of
 * truth - the cache only seeds in-memory generator caches, whose own content keys decide whether an entry is
 * used, so a rebuild with the cache gives exactly what a cold rebuild gives.
 *
 * Layout: `cache/head/manifest.json` (`{format, version, build, generator, revision, language, entries:[{kind,
 * file, sha256, bytes}]}`) and one file per entry. Entries today: `skeleton-bake` - the skeleton bake of the head
 * ([SkeletonRig.StoredBake], its output IR in [RigIrBinary]).
 *
 * The folder is outside `history/` and `document/`: it never enters a revision id or a document node, and an
 * archive without it opens as before. Builds that predate it unpack it with the rest of the archive and never
 * read it. Reading discards the whole cache - never failing the open - when the manifest's format, version,
 * build ([Compiler.version]), [GENERATOR] constant, head revision or interface language differ, or when an
 * entry is missing, damaged or unreadable.
 */
internal object ProjectHeadCache {
	const val FOLDER = "cache/head"
	private const val FORMAT = "psd2live-head-cache"
	const val VERSION = 1
	/**
	 * Raise when generation changes in a way the entries' own keys would not notice, so caches written by
	 * earlier builds of the same version are ignored.
	 */
	const val GENERATOR = 1
	private const val SKELETON = "skeleton-bake"
	/** An entry larger than this is not written: rebuilding is cheaper than carrying it in every save. */
	const val MAX_ENTRY_BYTES = 64 * 1024 * 1024

	/** Off with `-Dpsd2live.headCache=false`: saves write no cache and opens ignore one. */
	val enabled: Boolean get() = System.getProperty("psd2live.headCache")?.equals("false", ignoreCase = true) != true

	private val logger = System.getLogger("io.github.psd2live.project.ProjectHeadCache")
	private val json = Json { ignoreUnknownKeys = true }

	/** What the cache is valid for. */
	data class Key(val revision: String, val language: String = io.github.psd2live.i18n.I18n.currentLanguage.tag,
	               val build: String = Compiler.version, val generator: Int = GENERATOR)

	/** Writes the cache of [head] under [root]; returns whether anything was written. Failures only skip the cache. */
	fun write(root: Path, head: WorkspaceDocument, key: Key, maxEntryBytes: Int = MAX_ENTRY_BYTES): Boolean = if (!enabled) false else try {
		val entries = buildJsonArray {
			val skeleton = head.rigEdits.skeleton?.takeIf { it.enabled }
			val bake = skeleton?.let(SkeletonRig::storedBake)?.let(::encodeBake)
			if (bake != null && bake.size <= maxEntryBytes) add(entry(root, SKELETON, "$SKELETON.bin", bake))
		}
		if (entries.isEmpty()) false else {
			ProjectArchive.writeJson(root.resolve("$FOLDER/manifest.json"), buildJsonObject {
				put("format", FORMAT); put("version", VERSION); put("build", key.build); put("generator", key.generator)
				put("revision", key.revision); put("language", key.language); put("entries", entries)
			})
			true
		}
	} catch (failure: Exception) {
		logger.log(System.Logger.Level.WARNING, "Project head cache not written", failure)
		deleteTree(root.resolve("cache"))
		false
	}

	/**
	 * Seeds the in-memory caches from the cache under [root] when it was written for [key], and removes the
	 * folder. Returns the kinds of entry seeded; anything stale or damaged is discarded silently (logged).
	 */
	fun seed(root: Path, key: Key): List<String> {
		val folder = root.resolve(FOLDER)
		try {
			if (!enabled || !Files.isRegularFile(folder.resolve("manifest.json"))) return emptyList()
			val manifest = json.parseToJsonElement(Files.readString(folder.resolve("manifest.json"))).jsonObject
			fun text(name: String) = manifest[name]?.jsonPrimitive?.contentOrNull
			fun number(name: String) = manifest[name]?.jsonPrimitive?.intOrNull
			val stale = when {
				text("format") != FORMAT || number("version") != VERSION -> "format"
				text("build") != key.build || number("generator") != key.generator -> "build"
				text("revision") != key.revision -> "revision"
				text("language") != key.language -> "language"
				else -> null
			}
			if (stale != null) {
				logger.log(System.Logger.Level.DEBUG, "Project head cache ignored: $stale differs")
				return emptyList()
			}
			val seeded = ArrayList<String>()
			for (entry in manifest["entries"]?.jsonArray.orEmpty()) {
				val item = entry.jsonObject
				val kind = item["kind"]?.jsonPrimitive?.contentOrNull
				val bytes = read(folder, item) ?: continue
				when (kind) {
					SKELETON -> { SkeletonRig.seed(decodeBake(bytes)); seeded += SKELETON }
				}
			}
			return seeded
		} catch (failure: Exception) {
			logger.log(System.Logger.Level.WARNING, "Project head cache discarded", failure)
			return emptyList()
		} finally {
			runCatching { deleteTree(root.resolve("cache")) }
		}
	}

	private fun entry(root: Path, kind: String, file: String, bytes: ByteArray): JsonObject {
		val path = root.resolve("$FOLDER/$file")
		Files.createDirectories(path.parent)
		Files.write(path, bytes)
		return buildJsonObject { put("kind", kind); put("file", file); put("sha256", sha256(bytes)); put("bytes", bytes.size) }
	}

	/** An entry's verified bytes, or null when it is missing, too large or does not match its checksum. */
	private fun read(folder: Path, item: JsonObject): ByteArray? {
		val file = item["file"]?.jsonPrimitive?.contentOrNull ?: return null
		val path = folder.resolve(file).normalize()
		if (!file.matches(Regex("[A-Za-z0-9._-]+")) || !path.startsWith(folder) || !Files.isRegularFile(path)) return null
		if (Files.size(path) > MAX_ENTRY_BYTES) return null
		val bytes = Files.readAllBytes(path)
		if (sha256(bytes) != item["sha256"]?.jsonPrimitive?.contentOrNull) throw IOException("Head cache entry checksum mismatch: $file")
		return bytes
	}

	internal fun encodeBake(bake: SkeletonRig.StoredBake): ByteArray {
		val header = buildJsonObject {
			put("bakeVersion", SkeletonRig.BAKE_VERSION); put("key", bake.key); put("spec", bake.spec)
			put("drawables", JsonArray(bake.drawables.map(::JsonPrimitive))); put("deformers", JsonArray(bake.deformers.map(::JsonPrimitive)))
			put("unread", JsonArray(bake.unread.map(::JsonPrimitive)))
			put("reparentedDrawables", JsonObject(bake.reparentedDrawables.mapValues { JsonPrimitive(it.value) }))
			put("reparentedDeformers", JsonObject(bake.reparentedDeformers.mapValues { JsonPrimitive(it.value) }))
			bake.exact?.let { put("exact", it) }
		}.toString().encodeToByteArray()
		val output = RigIrBinary.encode(bake.output)
		return ByteArrayOutputStream(header.size + output.size + 8).also { bytes ->
			DataOutputStream(bytes).use { out -> out.writeInt(header.size); out.write(header); out.write(output) }
		}.toByteArray()
	}

	internal fun decodeBake(bytes: ByteArray): SkeletonRig.StoredBake {
		val input = DataInputStream(bytes.inputStream())
		val size = input.readInt()
		if (size < 0 || size > bytes.size - 4) throw IOException("Invalid head cache entry")
		val header = json.parseToJsonElement(ByteArray(size).also(input::readFully).decodeToString()).jsonObject
		if (header["bakeVersion"]?.jsonPrimitive?.contentOrNull != SkeletonRig.BAKE_VERSION) throw IOException("Skeleton bake of another version")
		fun strings(name: String) = header.getValue(name).jsonArray.map { it.jsonPrimitive.content }
		fun parents(name: String) = header.getValue(name).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull }
		return SkeletonRig.StoredBake(header.getValue("key").jsonPrimitive.content, header.getValue("spec").jsonPrimitive.content,
			RigIrBinary.decode(bytes.copyOfRange(4 + size, bytes.size)), strings("drawables"), strings("deformers"), strings("unread"),
			parents("reparentedDrawables"), parents("reparentedDeformers"), header["exact"]?.jsonPrimitive?.contentOrNull)
	}

	private fun deleteTree(path: Path) {
		if (!Files.exists(path)) return
		Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
	}

	private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
