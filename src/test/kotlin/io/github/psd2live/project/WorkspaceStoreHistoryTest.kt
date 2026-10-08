package io.github.psd2live.project

import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.history.WorkspaceHistoryTree
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.*

/** Revision identity stays the v1 definition while it gets cheaper, and history storage shares unchanged content. */
class WorkspaceStoreHistoryTest {
	@TempDir lateinit var temporary: Path

	private fun document(journal: List<JsonObject> = emptyList(), settings: JsonObject = JsonObject(emptyMap())): WorkspaceDocument {
		val raster = LayerRaster(2, 2, ByteArray(16) { 200.toByte() })
		val layer = WorkspaceSourceLayer(LayerId("art"), "Artwork", "", SourceLayerKind.Raster, true,
			0, LayerBounds(1, 1, 2, 2), 1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
		return WorkspaceDocument(WorkspaceSourceArt(4, 4, listOf(layer), emptyList()), emptyMap(), emptySet(), emptyMap(), emptyMap(),
			RigEditOverlay.Empty.copy(authoringJournal = journal), settings = settings)
	}

	/** A `canvas_geometry`-sized entry: [points] coordinates (about 17 KB of JSON at 2000). */
	private fun entry(n: Int, points: Int = 2000): JsonObject = buildJsonObject {
		put("op", "canvas_geometry"); put("n", n)
		val random = Random(n)
		putJsonArray("points") { repeat(points) { add(random.nextFloat() * 1000f) } }
	}

	@Test fun incrementalRevisionsMatchTheWholeText() {
		val journal = ArrayList<JsonObject>()
		repeat(30) { i ->
			journal.add(entry(i, points = 20))
			val document = document(journal.toList())
			assertEquals(WorkspaceRevisions.reference(document), WorkspaceRevisions.of(document), "append $i")
		}
		// An edit in the middle, a branch back to a prefix, other settings and other overlay fields.
		val edited = journal.toMutableList().also { it[10] = entry(100, points = 20) }
		val variants = listOf(
			document(edited),
			document(journal.take(12)),
			document(journal.take(12) + entry(200, points = 20)),
			document(journal, buildJsonObject { put("meshSpacing", 40) }),
			document(journal).let { it.copy(rigEdits = it.rigEdits.copy(splitBaselineLayerIds = setOf("art"), physicsFps = 240)) },
			document(emptyList()),
			document(journal).copy(textureOverrides = mapOf("art" to TextureOverride(density = 0.5f))),
		)
		repeat(2) { for (variant in variants) assertEquals(WorkspaceRevisions.reference(variant), WorkspaceRevisions.of(variant)) }
		// The same content in new objects hashes the same.
		val copy = document(Json.parseToJsonElement(JsonArray(journal).toString()).jsonArray.map { it.jsonObject })
		assertEquals(WorkspaceRevisions.of(document(journal)), WorkspaceRevisions.of(copy))
	}

	@Test fun cachedEntryTextKeepsLengthsAndRevisions() {
		val named = buildJsonObject { put("op", "rename"); put("name", "前髪 \uD83C\uDF38 é"); put("n", 1) }
		assertEquals(named.toString().length, JournalEntryDigests.of(named).length, "lengths count UTF-16 units, as chunking always did")
		val journal = listOf(named) + List(5) { entry(it, points = 20) }
		// The overlay around the journal changes, so the whole journal is hashed again from the cached text.
		for (physicsFps in listOf(60, 120, 30)) {
			val document = document(journal).let { it.copy(rigEdits = it.rigEdits.copy(physicsFps = physicsFps)) }
			assertEquals(WorkspaceRevisions.reference(document), WorkspaceRevisions.of(document))
		}
	}

	@Test fun appendedJournalEntriesShareStoredChunks() {
		val store = WorkspaceStore(temporary.resolve("store"))
		val journal = ArrayList<JsonObject>()
		var current = document()
		val first = WorkspaceRevisions.of(current)
		val tree = WorkspaceHistoryTree(current, first, first)
		var wholeSnapshots = 0L
		repeat(50) { i ->
			journal.add(entry(i))
			current = document(journal.toList())
			val revision = WorkspaceRevisions.of(current)
			tree.commit(tree.head().node.id, current, revision, revision, "Edit $i", "agent")
			store.persistHistory("project", tree.state())
			// What a whole-snapshot store wrote for this revision's journal alone.
			wholeSnapshots += JsonArray(journal).toString().length
		}
		val project = store.projectRoot("project")
		fun size(folder: String) = Files.walk(project.resolve(folder)).use { paths -> paths.filter(Files::isRegularFile).mapToLong(Files::size).sum() }
		val stored = size("history")
		val journalBytes = JsonArray(journal).toString().length.toLong()
		println("History after 50 appended entries: $stored bytes (journal $journalBytes, whole snapshots $wholeSnapshots)")
		assertTrue(stored < wholeSnapshots / 8, "history holds $stored bytes; whole snapshots would need more than $wholeSnapshots")
		assertTrue(size("history/snapshots") < 51 * 4096, "snapshots name chunks instead of copying the journal")
		assertTrue(size("history/journal") < journalBytes * 3, "chunks grow with new entries, not with the journal: ${size("history/journal")} vs $journalBytes")

		val loaded = assertNotNull(WorkspaceStore(temporary.resolve("store")).loadHistory("project"))
		for (selection in loaded.selections()) assertEquals(selection.node.revisionId, WorkspaceRevisions.of(selection.snapshot))
		assertEquals(journal, loaded.head().snapshot.rigEdits.authoringJournal)
	}

	@Test fun persistingAgainWritesOnlyNewNodesAndRecoversARemovedHistory() {
		val store = WorkspaceStore(temporary.resolve("store"))
		var current = document()
		val first = WorkspaceRevisions.of(current)
		val tree = WorkspaceHistoryTree(current, first, first)
		store.persistHistory("project", tree.state())
		val project = store.projectRoot("project")
		val nodes = project.resolve("history/nodes")
		val firstNode = Files.list(nodes).use { it.toList().single() }
		val written = Files.getLastModifiedTime(firstNode)
		repeat(5) { i ->
			current = document(List(i + 1) { entry(it, points = 8) })
			val revision = WorkspaceRevisions.of(current)
			tree.commit(tree.head().node.id, current, revision, revision, "Edit $i", "user")
			store.persistHistory("project", tree.state())
		}
		assertEquals(6, Files.list(nodes).use { it.count() })
		assertEquals(written, Files.getLastModifiedTime(firstNode), "stored nodes are not written again")

		// A history removed behind the store's back is written again in full on the next commit.
		Files.walk(project.resolve("history")).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
		store.persistHistory("project", tree.state())
		val loaded = assertNotNull(WorkspaceStore(temporary.resolve("store")).loadHistory("project"))
		assertEquals(6, loaded.selections().size)
		assertEquals(current.rigEdits.authoringJournal, loaded.head().snapshot.rigEdits.authoringJournal)
	}

	@Test fun sharedSnapshotsExpandToTheSelfContainedForm() {
		val store = WorkspaceStore(temporary.resolve("store"))
		val journal = List(12) { entry(it, points = 8) }
		val cmo3 = "A".repeat(70_000)
		val document = document(journal).let { it.copy(rigEdits = it.rigEdits.copy(importedCmo3 = cmo3)) }
		val revision = WorkspaceRevisions.of(document)
		store.persistHistory("project", WorkspaceHistoryTree(document, revision, revision).state())
		val project = store.projectRoot("project")
		val snapshotFile = Files.list(project.resolve("history/snapshots")).use { it.toList().single() }
		val stored = Json.parseToJsonElement(Files.readString(snapshotFile)).jsonObject
		val rig = stored.getValue("rigEdits").jsonObject
		// Readers without chunk support fail on these shapes instead of dropping the journal.
		assertIs<JsonObject>(rig.getValue("authoringJournal"))
		assertIs<JsonObject>(rig.getValue("importedCmo3"))
		val expanded = WorkspaceStore.expandSnapshot(project, stored)
		assertEquals(JsonArray(journal), expanded.getValue("rigEdits").jsonObject.getValue("authoringJournal"))
		assertEquals(JsonPrimitive(cmo3), expanded.getValue("rigEdits").jsonObject.getValue("importedCmo3"))
		assertEquals(stored.keys.toList(), expanded.keys.toList(), "keys keep their places")
		assertEquals(rig.keys.toList(), expanded.getValue("rigEdits").jsonObject.keys.toList())
		assertEquals(revision, WorkspaceRevisions.of(assertNotNull(store.loadHistory("project")).head().snapshot))

		// A damaged chunk is rejected.
		val chunk = Files.list(project.resolve("history/journal")).use { it.toList().first() }
		Files.writeString(chunk, Files.readString(chunk).replaceFirst("\"n\":", "\"m\":"))
		assertFailsWith<IllegalArgumentException> { WorkspaceStore(temporary.resolve("store")).loadHistory("project") }
	}
}
