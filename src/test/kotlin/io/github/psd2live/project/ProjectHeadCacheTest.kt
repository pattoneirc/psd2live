package io.github.psd2live.project

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRuntime
import io.github.psd2live.application.WorkspaceSourceImporter
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.SkeletonAutoBuilder
import io.github.psd2live.core.SkeletonRig
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*

/**
 * The head cache of a v2 archive only seeds the skeleton bake: opening with it rebuilds exactly what a cold open
 * rebuilds, it never touches revisions or document nodes, and a stale or damaged cache is ignored.
 */
@Tag("slow")
class ProjectHeadCacheTest {
	@TempDir lateinit var temporary: Path
	private val builder = WorkspacePreviewBuilder()

	private class Fixture(val capture: ProjectSaveCapture, val head: WorkspaceDocument, val model: RigPreviewModel)

	/** tml with its auto skeleton, as the head of a one-revision history; the bake is in the process cache. */
	private suspend fun fixture(): Fixture {
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
		val psd = Path.of("examples/tml/psd-input/tml.psd").toAbsolutePath()
		WorkspaceSourceImporter(runtime).importPsd(psd, null, runtime.state.value.state, initialConfig = PipelineConfig())
		val plain = runtime.capture()
		val spec = SkeletonAutoBuilder.build(plain.model.analysis, plain.model.rig)
		val document = plain.document.copy(rigEdits = plain.document.rigEdits.copy(skeleton = spec))
		val model = builder.build(document)
		runtime.install(runtime.state.value.state, plain.projectId, document, model, discardUnsaved = true)
		return Fixture(ProjectSaveCapture(plain.projectId, runtime.history(), JsonObject(emptyMap()), psd,
			WorkspaceStore(temporary.resolve("store"))), document, model)
	}

	private fun hash(model: RigPreviewModel) = ContentHash.of(PuppetIr.toIr(model.rig.puppet))

	private fun entries(file: Path) = ZipFile(file.toFile()).use { zip ->
		zip.entries().asSequence().filter { !it.isDirectory }.associate { it.name to zip.getInputStream(it).readBytes() }
	}

	/** Opens [archive] on a cold skeleton cache and rebuilds its head; returns the model and the bakes it ran. */
	private suspend fun open(archive: Path): Pair<RigPreviewModel, Int> {
		SkeletonRig.clearCache()
		val head = ProjectRepository().open(archive).use { it.history.head().snapshot }
		val misses = SkeletonRig.cacheMisses
		val model = builder.build(head)
		return model to SkeletonRig.cacheMisses - misses
	}

	/** Extracts [archive], lets [change] edit the extracted files and writes it back as a valid archive. */
	private fun rewrite(archive: Path, target: Path, change: (Path) -> Unit) {
		val root = ProjectArchive.extract(archive)
		try {
			change(root)
			val id = ProjectArchive.readJson(root.resolve("manifest.json")).getValue("projectId").jsonPrimitive.content
			ProjectArchive.write(root, target, id)
		} finally { ProjectArchive.deleteTemporaryDirectory(root) }
	}

	@Test fun aCachedBakeOpensToTheColdModelWithoutChangingTheDocument() = runBlocking<Unit> {
		val fixture = fixture()
		val cached = temporary.resolve("cached.psd2live")
		val plain = temporary.resolve("plain.psd2live")
		ProjectRepository().save(fixture.capture, cached)
		ProjectRepository(writeHeadCache = false).save(fixture.capture, plain)

		// The cache is a folder of its own: history, document nodes and everything else are byte for byte the same.
		val withCache = entries(cached)
		val without = entries(plain)
		val cacheEntries = withCache.keys.filter { it.startsWith("cache/") }
		assertEquals(setOf("cache/head/manifest.json", "cache/head/skeleton-bake.bin"), cacheEntries.toSet())
		assertTrue(without.keys.none { it.startsWith("cache/") })
		assertEquals(without.keys - "manifest.json" - "README.txt", withCache.keys - cacheEntries.toSet() - "manifest.json" - "README.txt")
		for (name in without.keys - "manifest.json" - "README.txt") assertContentEquals(without.getValue(name), withCache.getValue(name), name)
		val manifest = Json.parseToJsonElement(withCache.getValue("cache/head/manifest.json").decodeToString()).jsonObject
		assertEquals(fixture.capture.history.selections.single().node.revisionId, manifest.getValue("revision").jsonPrimitive.content)
		val bake = withCache.getValue("cache/head/skeleton-bake.bin")
		assertTrue(bake.size < 16 * 1024 * 1024, "the stored bake carries no atlas or sources: ${bake.size} bytes")

		// Opening with the cache needs no bake and gives the cold model; without it the bake runs again.
		val (warm, warmBakes) = open(cached)
		assertEquals(0, warmBakes, "the seeded bake is used")
		val (cold, coldBakes) = open(plain)
		assertEquals(1, coldBakes)
		assertEquals(hash(cold), hash(warm))
		assertEquals(hash(fixture.model), hash(warm))
		assertEquals(PuppetIr.toIr(cold.baseRig.puppet), PuppetIr.toIr(warm.baseRig.puppet))
		ProjectRepository().open(cached).use { opened ->
			assertEquals(WorkspaceRevisions.of(fixture.head), WorkspaceRevisions.of(opened.history.head().snapshot))
			assertEquals(fixture.capture.history.selections.single().node.revisionId, opened.history.head().node.revisionId)
		}

		// The whole IR of a built rig, pages included, survives the binary encoding.
		val ir = PuppetIr.toIr(fixture.model.rig.puppet)
		assertEquals(ir, RigIrBinary.decode(RigIrBinary.encode(ir)))
	}

	@Test fun aStaleOrDamagedCacheIsIgnored() = runBlocking<Unit> {
		val fixture = fixture()
		val cached = temporary.resolve("cached.psd2live")
		ProjectRepository().save(fixture.capture, cached)
		val expected = hash(fixture.model)
		fun edit(name: String, change: (Path) -> Unit): Path = temporary.resolve("$name.psd2live").also { rewrite(cached, it, change) }
		fun manifest(root: Path, key: String, value: JsonPrimitive) {
			val path = root.resolve("cache/head/manifest.json")
			ProjectArchive.writeJson(path, JsonObject(ProjectArchive.readJson(path) + (key to value)))
		}
		val bin = "cache/head/skeleton-bake.bin"
		val variants = mapOf(
			"revision" to edit("revision") { manifest(it, "revision", JsonPrimitive("revision-other")) },
			"language" to edit("language") { manifest(it, "language", JsonPrimitive("xx")) },
			"build" to edit("build") { manifest(it, "build", JsonPrimitive("0.0.0")) },
			"generator" to edit("generator") { manifest(it, "generator", JsonPrimitive(ProjectHeadCache.GENERATOR + 1)) },
			"checksum" to edit("checksum") { root -> Files.write(root.resolve(bin), Files.readAllBytes(root.resolve(bin)).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }) },
			// A damaged entry whose checksum still matches: decoding fails and the cache is dropped.
			"truncated" to edit("truncated") { root ->
				val bytes = Files.readAllBytes(root.resolve(bin)).let { it.copyOf(it.size / 2) }
				Files.write(root.resolve(bin), bytes)
				val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
				val path = root.resolve("cache/head/manifest.json")
				val manifest = ProjectArchive.readJson(path)
				ProjectArchive.writeJson(path, JsonObject(manifest + ("entries" to JsonArray(manifest.getValue("entries").jsonArray.map {
					JsonObject(it.jsonObject + ("sha256" to JsonPrimitive(digest)) + ("bytes" to JsonPrimitive(bytes.size)))
				}))))
			},
			"manifest" to edit("manifest") { Files.writeString(it.resolve("cache/head/manifest.json"), "{not json") },
			"missing" to edit("missing") { Files.delete(it.resolve(bin)) },
		)
		val spec = fixture.head.rigEdits.skeleton!!
		for ((name, archive) in variants) {
			SkeletonRig.clearCache()
			ProjectRepository().open(archive).use { opened -> assertEquals(WorkspaceRevisions.of(fixture.head), WorkspaceRevisions.of(opened.history.head().snapshot), name) }
			assertNull(SkeletonRig.storedBake(spec), "$name: nothing is seeded")
		}
		// The open itself never fails, and the rebuild bakes again to the same model.
		val (model, bakes) = open(variants.getValue("truncated"))
		assertEquals(1, bakes)
		assertEquals(expected, hash(model))
		// An archive without the folder (a build that never wrote one, or a user who deleted it) opens as before.
		SkeletonRig.clearCache()
		ProjectRepository().open(edit("deleted") { it.resolve("cache").toFile().deleteRecursively() }).use {
			assertEquals(WorkspaceRevisions.of(fixture.head), WorkspaceRevisions.of(it.history.head().snapshot))
		}
		assertNull(SkeletonRig.storedBake(spec))
		// The unchanged archive does seed it.
		SkeletonRig.clearCache()
		ProjectRepository().open(cached).close()
		assertNotNull(SkeletonRig.storedBake(spec))
	}

	@Test fun entriesAboveTheSizeBoundAreNotWritten() = runBlocking<Unit> {
		val fixture = fixture()
		val key = ProjectHeadCache.Key(fixture.capture.history.selections.single().node.revisionId)
		val small = Files.createDirectories(temporary.resolve("small"))
		assertFalse(ProjectHeadCache.write(small, fixture.head, key, maxEntryBytes = 1024))
		assertFalse(Files.exists(small.resolve("cache")))
		val full = Files.createDirectories(temporary.resolve("full"))
		assertTrue(ProjectHeadCache.write(full, fixture.head, key))
		// A document without a skeleton has nothing to cache.
		val none = Files.createDirectories(temporary.resolve("none"))
		assertFalse(ProjectHeadCache.write(none, fixture.head.copy(rigEdits = fixture.head.rigEdits.copy(skeleton = null)), key))
		// Seeding with another key leaves the folder unused and removes it.
		SkeletonRig.clearCache()
		assertEquals(emptyList(), ProjectHeadCache.seed(full, key.copy(revision = "revision-other")))
		assertFalse(Files.exists(full.resolve("cache")))
	}
}
