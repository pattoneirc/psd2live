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
		val newer = JsonObject(index + ("schema" to JsonPrimitive(3)))
		assertFailsWith<IllegalArgumentException> { ProjectFormatV2.join(root, newer) }
		// Schema 2 is only for revisions that list payloads.
		assertFailsWith<IllegalArgumentException> { ProjectFormatV2.join(root, JsonObject(index + ("schema" to JsonPrimitive(2)))) }
	}

	/** The nodes and revision indexes the v1 sample saved as before texture fields and shared working storage. */
	private val legacyNodes = listOf(
		"document/clips/6223e62d20d49fdd3c22b2cef3e3b5060844117d20d0e65f4204551e9312596e.json",
		"document/nodes/document/1a96f27c97a7d0dc1a6d8ea0d81fa8652252adbde959b3fcf8ac95250d7be10f.json",
		"document/nodes/generation-source/fbeea4d579a7d57823f306629ed438ef75d079899d95f511fa49e768fa24bca4.json",
		"document/nodes/journal/67ddb6d429b7f0c82e15cb654b7574e9abed013843cce140658b00b47ad68db4.json",
		"document/nodes/journal/9791ce52dd9704a236e82ebe2a5ee57c1e01532dd748ac07c40ae9953edb551a.json",
		"document/nodes/layers/44a8b23f0d01bfa01a9f4b28e7bfa557a60074eca2dfe2c9063d7aaaca7edbcc.json",
		"document/nodes/layers/58c5d9f27aabca80e9ff0ee879c1e8a15669bdb2c6688701793b00bc245e0f07.json",
		"document/nodes/rig/e4b1ebd278d09557382db1138d63f7d68be583b38f2d86dc62292d859dde8275.json",
		"document/nodes/settings/d18a4016d7a62f4e7dc028fc8de02b85f7ecfe4829dc263113c6ddbd203b63c0.json",
		"document/nodes/source/18791148ca57e25c7f16bf149a1ffcf82dfe248e55d74884f5f07a6d5987b327.json",
		"document/nodes/source/5bdbbb3d8b3b149c1ea9751d49441f6c623ecfbf877cb77ca8639a89ec52121f.json",
		"document/overrides/a1a2306f464b083a779438f57a72416c0ce9c61f83769a30745d17ad241edf22.json",
	)
	private val legacyRevisions = mapOf(
		"0c29b7bc4a35e902510cec30f804138b41cac63b0e0ef3d863af46560f6758e6" to
			"""{"schema":1,"nodes":{"source":"18791148ca57e25c7f16bf149a1ffcf82dfe248e55d74884f5f07a6d5987b327","layers":"58c5d9f27aabca80e9ff0ee879c1e8a15669bdb2c6688701793b00bc245e0f07","settings":"d18a4016d7a62f4e7dc028fc8de02b85f7ecfe4829dc263113c6ddbd203b63c0","generation-source":"fbeea4d579a7d57823f306629ed438ef75d079899d95f511fa49e768fa24bca4","rig":"e4b1ebd278d09557382db1138d63f7d68be583b38f2d86dc62292d859dde8275","journal":"9791ce52dd9704a236e82ebe2a5ee57c1e01532dd748ac07c40ae9953edb551a","document":"1a96f27c97a7d0dc1a6d8ea0d81fa8652252adbde959b3fcf8ac95250d7be10f"},"overrides":"a1a2306f464b083a779438f57a72416c0ce9c61f83769a30745d17ad241edf22","clips":"6223e62d20d49fdd3c22b2cef3e3b5060844117d20d0e65f4204551e9312596e"}""",
		"af4471bc85a71a93243d168de1fc4d426cdc6d429dc9e29aafe7928eb2cbb810" to
			"""{"schema":1,"nodes":{"source":"5bdbbb3d8b3b149c1ea9751d49441f6c623ecfbf877cb77ca8639a89ec52121f","layers":"44a8b23f0d01bfa01a9f4b28e7bfa557a60074eca2dfe2c9063d7aaaca7edbcc","settings":"d18a4016d7a62f4e7dc028fc8de02b85f7ecfe4829dc263113c6ddbd203b63c0","generation-source":"fbeea4d579a7d57823f306629ed438ef75d079899d95f511fa49e768fa24bca4","rig":"e4b1ebd278d09557382db1138d63f7d68be583b38f2d86dc62292d859dde8275","journal":"67ddb6d429b7f0c82e15cb654b7574e9abed013843cce140658b00b47ad68db4","document":"1a96f27c97a7d0dc1a6d8ea0d81fa8652252adbde959b3fcf8ac95250d7be10f"},"clips":"6223e62d20d49fdd3c22b2cef3e3b5060844117d20d0e65f4204551e9312596e"}""",
		"b63fa95ddef57f5d4c3b44f717f6b1f0522865e59d69c3c9b554b8dbb153696d" to
			"""{"schema":1,"nodes":{"source":"5bdbbb3d8b3b149c1ea9751d49441f6c623ecfbf877cb77ca8639a89ec52121f","layers":"44a8b23f0d01bfa01a9f4b28e7bfa557a60074eca2dfe2c9063d7aaaca7edbcc","settings":"d18a4016d7a62f4e7dc028fc8de02b85f7ecfe4829dc263113c6ddbd203b63c0","generation-source":"fbeea4d579a7d57823f306629ed438ef75d079899d95f511fa49e768fa24bca4","rig":"e4b1ebd278d09557382db1138d63f7d68be583b38f2d86dc62292d859dde8275","journal":"9791ce52dd9704a236e82ebe2a5ee57c1e01532dd748ac07c40ae9953edb551a","document":"1a96f27c97a7d0dc1a6d8ea0d81fa8652252adbde959b3fcf8ac95250d7be10f"},"overrides":"a1a2306f464b083a779438f57a72416c0ce9c61f83769a30745d17ad241edf22","clips":"6223e62d20d49fdd3c22b2cef3e3b5060844117d20d0e65f4204551e9312596e"}""",
		"c1171b8a0e6c902643accbeaf3c49323cf201eb3121149fc0dafef234d22cd51" to
			"""{"schema":1,"nodes":{"source":"5bdbbb3d8b3b149c1ea9751d49441f6c623ecfbf877cb77ca8639a89ec52121f","layers":"58c5d9f27aabca80e9ff0ee879c1e8a15669bdb2c6688701793b00bc245e0f07","settings":"d18a4016d7a62f4e7dc028fc8de02b85f7ecfe4829dc263113c6ddbd203b63c0","generation-source":"fbeea4d579a7d57823f306629ed438ef75d079899d95f511fa49e768fa24bca4","rig":"e4b1ebd278d09557382db1138d63f7d68be583b38f2d86dc62292d859dde8275","journal":"9791ce52dd9704a236e82ebe2a5ee57c1e01532dd748ac07c40ae9953edb551a","document":"1a96f27c97a7d0dc1a6d8ea0d81fa8652252adbde959b3fcf8ac95250d7be10f"},"overrides":"a1a2306f464b083a779438f57a72416c0ce9c61f83769a30745d17ad241edf22","clips":"6223e62d20d49fdd3c22b2cef3e3b5060844117d20d0e65f4204551e9312596e"}""",
	)

	@Test fun aLegacyProjectSavesByteIdenticalNodes() = runBlocking<Unit> {
		val file = fixture()
		val repository = ProjectRepository()
		repository.open(file).use { repository.save(capture(it), file) }
		ZipFile(file.toFile()).use { zip ->
			val names = zip.entries().asSequence().map { it.name }.toList()
			assertEquals(legacyNodes, names.filter { it.startsWith("document/") }.sorted())
			val revisions = names.filter { it.startsWith("history/revisions/") }.associate { name ->
				name.removePrefix("history/revisions/").removeSuffix(".json") to zip.getInputStream(zip.getEntry(name)).readBytes().decodeToString()
			}
			assertEquals(legacyRevisions, revisions)
			// Node file names are their SHA-256, so equal names mean equal bytes.
			for (name in legacyNodes) assertEquals(name.substringAfterLast('/').removeSuffix(".json"),
				java.security.MessageDigest.getInstance("SHA-256").digest(zip.getInputStream(zip.getEntry(name)).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) })
		}
	}

	private fun nodeSchema(root: Path, kind: String, hash: String): Int =
		Json.parseToJsonElement(Files.readString(root.resolve("document/nodes/$kind/$hash.json"))).jsonObject.getValue("schema").jsonPrimitive.int

	@Test fun textureFieldsRaiseOnlyTheirNodesToSchema2() {
		val root = Files.createDirectories(temporary.resolve("textures"))
		fun layer(rect: Boolean) = buildJsonObject {
			put("id", "a"); put("left", 1); put("top", 1); put("width", 3); put("height", 3)
			if (rect) putJsonArray("rect") { add(1.5f); add(1.25f); add(2.5f); add(2.75f) }
		}
		val legacy = buildJsonObject {
			put("settings", buildJsonObject { put("atlasSize", 2048) }); put("version", 1)
			put("canvasWidth", 4); put("canvasHeight", 4); putJsonArray("groups") {}; putJsonArray("layers") { add(layer(false)) }
			put("generationSource", buildJsonObject { put("canvasWidth", 4); putJsonArray("layers") { add(layer(false)) } })
			putJsonObject("layerVisibility") {}
			putJsonObject("rigEdits") { putJsonArray("authoringJournal") { add(buildJsonObject { put("op", "canvas_geometry") }) } }
		}
		val legacyIndex = ProjectFormatV2.split(root, legacy)
		val legacyNodes = legacyIndex.getValue("nodes").jsonObject.mapValues { it.value.jsonPrimitive.content }
		assertTrue(legacyNodes.all { (kind, hash) -> nodeSchema(root, kind, hash) == 1 }, "a document without texture fields stays schema 1")

		val textured = JsonObject(legacy + mapOf(
			"settings" to buildJsonObject { put("atlasSize", 2048); putJsonObject("atlas") { put("pageSize", 2048); put("maxPages", 2); put("padding", 4) } },
			"layers" to buildJsonArray { add(layer(true)) },
			"generationSource" to buildJsonObject { put("canvasWidth", 4); putJsonArray("layers") { add(layer(true)) } },
			"textureOverrides" to buildJsonObject { putJsonObject("a") { put("density", 2f); put("lock", true) } },
		))
		val index = ProjectFormatV2.split(root, textured)
		assertEquals(1, index.getValue("schema").jsonPrimitive.int, "texture fields alone need no new revision schema")
		val nodes = index.getValue("nodes").jsonObject.mapValues { it.value.jsonPrimitive.content }
		assertEquals(mapOf("source" to 2, "layers" to 2, "settings" to 2, "generation-source" to 2, "rig" to 1, "journal" to 1, "document" to 1),
			nodes.mapValues { (kind, hash) -> nodeSchema(root, kind, hash) })
		assertEquals(legacyNodes.getValue("rig"), nodes.getValue("rig"))
		assertEquals(textured, ProjectFormatV2.join(root, index))
	}

	@Test fun payloadNodesRoundTripAndOnlyThenRaiseTheRevisionSchema() {
		val root = Files.createDirectories(temporary.resolve("payloads"))
		val large = buildJsonObject { put("op", "canvas_geometry"); putJsonArray("points") { repeat(64) { add(it * 0.5f) } } }
		val snapshot = buildJsonObject {
			put("version", 1)
			putJsonObject("rigEdits") {
				putJsonArray("authoringJournal") {
					add(buildJsonObject { put("op", "structure") })
					add(large)
					add(buildJsonObject { put("op", GeneratedOverrides.OP); put("n", 2) })
				}
			}
		}
		val plain = ProjectFormatV2.split(root, snapshot)
		assertEquals(1, plain.getValue("schema").jsonPrimitive.int)
		assertNull(plain["payloads"])

		val index = ProjectFormatV2.split(root, snapshot, payloadMinChars = 100)
		assertEquals(2, index.getValue("schema").jsonPrimitive.int)
		val payload = index.getValue("payloads").jsonArray.single().jsonPrimitive.content
		assertEquals(large, ProjectFormatV2.readPayload(root, payload))
		val journal = Json.parseToJsonElement(Files.readString(root.resolve("document/nodes/journal/${index.getValue("nodes").jsonObject.getValue("journal").jsonPrimitive.content}.json")))
		assertEquals(buildJsonObject { put(ProjectFormatV2.PAYLOAD_REF, payload) }, journal.jsonObject.getValue("value").jsonObject.getValue("entries").jsonArray[1])
		assertEquals(snapshot, ProjectFormatV2.join(root, index))
		assertEquals(index, ProjectFormatV2.split(root, snapshot, payloadMinChars = 100), "a payload is stored once")
		// A journal may only name payloads its revision lists.
		assertFailsWith<IllegalArgumentException> {
			ProjectFormatV2.join(root, JsonObject(index + ("payloads" to buildJsonArray { add("0".repeat(64)) })))
		}
	}

	@Test fun textureFieldsSurviveAV2SaveAndKeepTheirRevision() = runBlocking<Unit> {
		val raster = org.umamo.format.art.LayerRaster(2, 2, ByteArray(16) { 255.toByte() })
		fun layer(rect: LayerCanvasRect?) = WorkspaceSourceLayer(org.umamo.format.art.LayerId("art"), "Artwork", "", org.umamo.format.art.SourceLayerKind.Raster, true,
			0, org.umamo.format.art.LayerBounds(1, 1, 3, 3), 1f, false, org.umamo.format.art.LayerBlend.Normal, org.umamo.format.art.ChannelMask.ALL, raster,
			null, null, false, rect)
		fun document(rect: LayerCanvasRect?, overrides: Map<String, TextureOverride>, settings: JsonObject) = WorkspaceDocument(
			WorkspaceSourceArt(8, 8, listOf(layer(rect)), emptyList()), emptyMap(), emptySet(), emptyMap(), emptyMap(),
			io.github.psd2live.core.RigEditOverlay.Empty, settings = settings, textureOverrides = overrides)
		val plainSettings = buildJsonObject { put("atlasSize", 2048) }
		val plain = document(null, emptyMap(), plainSettings)
		// Values equal to the defaults are not part of the identity.
		assertEquals(WorkspaceRevisions.of(plain), WorkspaceRevisions.of(document(LayerCanvasRect(1f, 1f, 3f, 3f), mapOf("art" to TextureOverride()), plainSettings)))
		val budget = io.github.psd2live.core.AtlasBudget(pageSize = 2048, maxPages = 3, padding = 4)
		val textured = document(LayerCanvasRect(1.5f, 1.25f, 2.5f, 2.75f),
			mapOf("art" to TextureOverride(density = 2f, lock = true, pin = TexturePin(1, 16, 32))),
			JsonObject(plainSettings + (WorkspaceSettingsCodec.ATLAS to WorkspaceSettingsCodec.encodeAtlasBudget(budget))))
		assertNotEquals(WorkspaceRevisions.of(plain), WorkspaceRevisions.of(textured))
		assertEquals(budget, WorkspaceSettingsCodec.decodeAtlasBudget(textured.settings))
		assertNull(WorkspaceSettingsCodec.decodeAtlasBudget(plain.settings))
		assertEquals(io.github.psd2live.core.AtlasBudget(pageSize = 2048), WorkspaceSettingsCodec.atlasBudget(plain.settings), "no budget keeps the legacy page size")

		val revision = WorkspaceRevisions.of(plain)
		val tree = io.github.psd2live.history.WorkspaceHistoryTree(plain, revision, revision)
		val next = WorkspaceRevisions.of(textured)
		tree.commit(tree.head().node.id, textured, next, next, "Texture settings", "agent")
		val target = temporary.resolve("textured.psd2live")
		val repository = ProjectRepository()
		repository.save(ProjectSaveCapture("project", tree.state(), JsonObject(emptyMap()), null, WorkspaceStore(temporary.resolve("workspace"))), target)
		repository.open(target).use { opened ->
			val head = opened.history.head().snapshot
			assertEquals(next, WorkspaceRevisions.of(head))
			assertEquals(textured.textureOverrides, head.textureOverrides)
			assertEquals(LayerCanvasRect(1.5f, 1.25f, 2.5f, 2.75f), (head.source.layers.single() as WorkspaceSourceLayer).rect)
			assertEquals(budget, WorkspaceSettingsCodec.decodeAtlasBudget(head.settings))
			assertEquals(revision, WorkspaceRevisions.of(opened.history.selections().single { it.node.parentId == null }.snapshot))
		}
	}
}
