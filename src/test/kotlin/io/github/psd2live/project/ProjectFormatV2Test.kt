package io.github.psd2live.project

import io.github.psd2live.core.GeneratedOverrides
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*

/**
 * v1 projects open and migrate to v2 on their next save; v2 projects round-trip. The fixture
 * `projects/v1-sample.psd2live` was written by the v1 writer: a branched history of four revisions with a
 * swing override in the journal, generated-motion settings, a staged asset, a view, a task and a log image.
 */
class ProjectFormatV2Test {
	@TempDir lateinit var temporary: Path

	private val head = "history-b5cf8838-743f-4fa0-a235-72c9848c03bd"
	private val revisions = mapOf(
		"history-137f27f9-18f3-4f92-9184-4e08901f6ce4" to "revision-26a776f429a2a59896a42747d9154f628fb445cfdfd2a947235c4dd280239239",
		"history-47510230-1897-494d-8824-c070d6da7966" to "revision-a00d7844af0484183ae33d6ceb97dacf7185ec434ce14dd8f2f0772a8ee84a34",
		"history-7397c7e0-1d0a-41ab-81b1-ed9ccb9f2001" to "revision-d9d25a98000d81624ffc4dc5130b4d64082cfabfba9c0ff495c62d39c747b8ba",
		head to "revision-4678c74e74d56625c6ed41dda15dcccf79184e6b67040e2a51e7dcfcd9d2fbc5",
	)

	private fun fixture(): Path {
		val target = temporary.resolve("sample.psd2live")
		ProjectFormatV2Test::class.java.getResourceAsStream("/projects/v1-sample.psd2live")!!.use { Files.copy(it, target) }
		return target
	}

	private fun entries(file: Path) = ZipFile(file.toFile()).use { zip -> zip.entries().asSequence().map { it.name }.toList() }

	/** Everything a save needs from an opened project, as the desktop captures it. */
	private fun capture(opened: OpenedProject) = ProjectSaveCapture(opened.projectId, opened.history.state(), opened.presentation, opened.source,
		opened.store, listOfNotNull(opened.store.loadSpatial(opened.projectId, "view-1")?.let { "view-1" to it }).toMap(),
		opened.store.loadTasks(opened.projectId), buildJsonObject { put("assetCatalog", opened.store.existingAssetCatalog(opened.projectId).encode()) })

	/** What must survive any migration: history, revisions, documents, presentation and auxiliary records. */
	private fun assertSample(opened: OpenedProject) {
		assertEquals("fixture", opened.projectId)
		assertEquals(head, opened.history.head().node.id)
		val selections = opened.history.selections()
		assertEquals(revisions, selections.associate { it.node.id to it.node.revisionId })
		for (selection in selections) assertEquals(selection.node.revisionId, WorkspaceRevisions.of(selection.snapshot), "${selection.node.summary} decodes to its revision")
		val root = selections.single { it.node.parentId == null }.snapshot
		assertEquals(listOf("canvas_geometry", GeneratedOverrides.OP, "structure"),
			root.rigEdits.authoringJournal.map { it.getValue("op").jsonPrimitive.content }, "the override keeps its place in the journal")
		assertTrue(root.rigEdits.motionPresets.getValue("Nod").deleted)
		assertEquals("back", root.rigEdits.swingEdits.single().id)
		assertNotNull(root.generationSource)
		assertEquals("Saved", opened.presentation.getValue("logEntries").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
		assertNotNull(opened.store.loadAsset("fixture", "asset-1"))
		assertNotNull(opened.store.loadSpatial("fixture", "view-1"))
		assertEquals("task-1", opened.store.loadTasks("fixture").single().id)
		assertTrue(Files.isRegularFile(opened.source))
	}

	@Test fun aV1ProjectOpensAndMigratesToV2OnSave() = runBlocking {
		val file = fixture()
		val original = Files.readAllBytes(file)
		val repository = ProjectRepository()
		repository.open(file).use { opened ->
			assertEquals(1, opened.formatVersion)
			assertSample(opened)
			repository.save(capture(opened), file)
		}
		// The first v2 save keeps the v1 original beside it.
		val backup = temporary.resolve("sample.v1.psd2live")
		assertContentEquals(original, Files.readAllBytes(backup))
		assertEquals(1, ProjectFormatV2.versionOf(backup))
		assertEquals(2, ProjectFormatV2.versionOf(file))

		val names = entries(file)
		assertTrue(names.none { it.startsWith("workspace/") }, "v2 has no working-store layout: ${names.filter { it.startsWith("workspace/") }}")
		for (prefix in listOf("history/HEAD.json", "history/nodes/", "history/revisions/", "document/nodes/source/", "document/nodes/journal/",
				"document/overrides/", "document/clips/", "assets/", "auxiliary/assets/", "auxiliary/views/", "auxiliary/tasks.json", "source/original.psd", "workspace.json"))
			assertTrue(names.any { it.startsWith(prefix) }, "v2 archive holds $prefix")
		assertEquals(4, names.count { it.startsWith("history/revisions/") })
		// Revisions share the nodes they did not change: one rig definition, two source arts (one paint), two journals.
		assertEquals(1, names.count { it.startsWith("document/nodes/rig/") })
		assertEquals(2, names.count { it.startsWith("document/nodes/source/") })
		assertEquals(2, names.count { it.startsWith("document/nodes/journal/") })
		assertEquals(1, names.count { it.startsWith("document/overrides/") })

		repository.open(file).use { reopened ->
			assertEquals(2, reopened.formatVersion)
			assertSample(reopened)
			// Saving a v2 project again makes no further backup.
			repository.save(capture(reopened), file)
		}
		assertEquals(listOf("sample.psd2live", "sample.v1.psd2live"), Files.list(temporary).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() })
		repository.open(file).use { assertSample(it) }
	}

	@Test fun splittingASnapshotIsLossless() {
		val root = Files.createDirectories(temporary.resolve("split"))
		val snapshot = buildJsonObject {
			put("settings", buildJsonObject { put("meshSpacing", 40) }); put("version", 1); put("future", "kept")
			put("canvasWidth", 4); put("canvasHeight", 4); putJsonArray("groups") {}; putJsonArray("layers") {}
			put("generationSource", buildJsonObject { put("canvasWidth", 4) })
			putJsonObject("layerVisibility") { put("a", false) }
			putJsonObject("rigEdits") {
				putJsonArray("swings") {}
				putJsonArray("authoringJournal") {
					add(buildJsonObject { put("op", GeneratedOverrides.OP); put("n", 0) })
					add(buildJsonObject { put("op", "canvas_geometry"); put("n", 1) })
					add(buildJsonObject { put("op", GeneratedOverrides.OP); put("n", 2) })
					add(buildJsonObject { put("op", "structure"); put("n", 3) })
				}
				putJsonArray("motions") {}
			}
		}
		val index = ProjectFormatV2.split(root, snapshot)
		assertEquals(snapshot, ProjectFormatV2.join(root, index))
		assertEquals(index, ProjectFormatV2.split(root, snapshot), "the same snapshot names the same nodes")
		// A snapshot without a journal or clips keeps them absent.
		val bare = JsonObject(snapshot + ("rigEdits" to buildJsonObject { putJsonArray("swings") {} }))
		assertEquals(bare, ProjectFormatV2.join(root, ProjectFormatV2.split(root, bare)))
	}

	@Test fun aDamagedDocumentNodeIsRejected() {
		val root = Files.createDirectories(temporary.resolve("damaged"))
		val index = ProjectFormatV2.split(root, buildJsonObject { put("version", 1); put("canvasWidth", 4) })
		val source = Files.list(root.resolve("document/nodes/source")).use { it.toList().single() }
		Files.writeString(source, Files.readString(source).replace("4", "5"))
		assertFailsWith<IllegalArgumentException> { ProjectFormatV2.join(root, index) }
		val newer = JsonObject(index + ("schema" to JsonPrimitive(2)))
		assertFailsWith<IllegalArgumentException> { ProjectFormatV2.join(root, newer) }
	}
}
