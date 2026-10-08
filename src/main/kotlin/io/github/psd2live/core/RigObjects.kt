package io.github.psd2live.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stored rig objects ([io.github.psd2live.format.compile.RigIrObjects]) by the SHA-256 of their bytes: those this
 * process made, held in memory until a folder that is read back holds them, and those folders hold. A project's
 * working store and an opened archive register their object folders; a save copies what its revisions reference.
 */
internal object RigObjects {
	private val HASH = Regex("[0-9a-f]{64}")
	private val held = ConcurrentHashMap<String, ByteArray>()
	private val folders = CopyOnWriteArrayList<Path>()

	/** Holds [bytes] and returns their hash. */
	fun put(bytes: ByteArray): String = sha256(bytes).also { held.putIfAbsent(it, bytes) }

	/** The object [hash] names, checked against it; [IOException] when no holder has it. */
	fun get(hash: String): ByteArray {
		require(hash.matches(HASH)) { "Invalid rig object name" }
		held[hash]?.let { return it }
		for (folder in folders) {
			val path = folder.resolve("$hash.bin")
			if (!Files.isRegularFile(path)) continue
			val bytes = Files.readAllBytes(path)
			if (sha256(bytes) != hash) throw IOException("Rig object checksum mismatch: $hash")
			return bytes
		}
		throw IOException("Rig object is missing: $hash")
	}

	fun has(hash: String): Boolean = hash.matches(HASH) && (held.containsKey(hash) || folders.any { Files.isRegularFile(it.resolve("$hash.bin")) })

	/** Reads objects from [folder] too (an archive's or a working store's object folder). */
	fun addFolder(folder: Path) {
		val normalized = folder.toAbsolutePath().normalize()
		if (normalized !in folders) folders.add(0, normalized)
	}

	/**
	 * Writes the objects [hashes] name into [folder], linking or copying those another folder has. Objects held in
	 * memory that [folder] - a registered folder - now holds are let go.
	 */
	fun writeTo(folder: Path, hashes: Collection<String>) {
		Files.createDirectories(folder)
		val registered = folder.toAbsolutePath().normalize() in folders
		for (hash in hashes.toSet()) {
			require(hash.matches(HASH)) { "Invalid rig object name" }
			val target = folder.resolve("$hash.bin")
			if (!Files.exists(target)) {
				val bytes = held[hash]
				if (bytes != null) Files.write(target, bytes) else {
					val source = folders.map { it.resolve("$hash.bin") }.firstOrNull { it != target && Files.isRegularFile(it) }
						?: throw IOException("Rig object is missing: $hash")
					try { Files.createLink(target, source) } catch (_: Exception) { Files.copy(source, target) }
				}
			}
			if (registered) held.remove(hash)
		}
	}

	/** Forgets held objects and folders: for tests that need a cold process. */
	fun clear() { held.clear(); folders.clear() }

	private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
