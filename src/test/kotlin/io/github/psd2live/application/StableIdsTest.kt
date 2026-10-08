package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Objects a request creates without naming them are named from the request: the same request on the same document -
 * retried, or previewed and then committed - names the same objects, and an ID already taken gets a counter.
 */
class StableIdsTest {
	private fun capture(): WorkspaceCapture<RigPreviewModel> = runBlocking {
		val runtime = WorkspaceRuntime<RigPreviewModel>({ WorkspacePreviewBuilder().build(it) })
		simulationFixture(runtime)
	}

	/** [request] as two different sends of it: another state, request and project. */
	private fun sent(request: JsonObject, attempt: Int) = JsonObject(request + buildJsonObject {
		put("state", "state-$attempt"); put("request_id", "request-$attempt"); put("project_id", "project-$attempt")
	})

	private fun applied(capture: WorkspaceCapture<RigPreviewModel>, operation: String, request: JsonObject,
						document: WorkspaceDocument = capture.document) =
		WorkspaceDocumentEdits.apply(WorkspaceDocumentOperation(operation, request), document, capture.model)

	private fun lastId(document: WorkspaceDocument) = document.rigEdits.authoringJournal.last().getValue("id").jsonPrimitive.content

	@Test fun anIdIsMadeFromTheRequestAndCountsOnlyWhenTaken() {
		val request = buildJsonObject { put("name", "Tail"); putJsonArray("meshes") { add("m") } }
		val first = StableIds.of("Warp_", sent(request, 1)) { false }
		assertEquals(first, StableIds.of("Warp_", sent(request, 2)) { false })
		assertTrue(first.startsWith("Warp_"))
		assertNotEquals(first, StableIds.of("Warp_", JsonObject(request + ("name" to JsonPrimitive("Head")))) { false })
		assertEquals("${first}_2", StableIds.of("Warp_", request) { it == first })
		assertEquals("${first}_3", StableIds.of("Warp_", request) { it == first || it == "${first}_2" })
	}

	@Test fun canvasWarpsRigWarpsAndPathsAreNamedTheSameOnEverySend() {
		val capture = capture()
		val mesh = capture.model.rig.puppet.drawables.single()
		val canvas = buildJsonObject { put("name", "Wrap"); putJsonArray("meshes") { add(mesh.id.raw) }; put("rows", 2); put("columns", 2) }
		val first = applied(capture, "canvas_warp", sent(canvas, 1))
		assertEquals(lastId(first), lastId(applied(capture, "canvas_warp", sent(canvas, 2))))
		// The same request again on the document that now holds that Warp makes a second one beside it.
		val again = WorkspaceDocumentEdits.apply(WorkspaceDocumentOperation("canvas_warp", sent(canvas, 3)), first,
			WorkspacePreviewBuilder().let { runBlocking { it.build(first) } })
		assertEquals("${lastId(first)}_2", lastId(again))

		val points = mesh.mesh!!.positions
		val path = buildJsonObject { put("target", "mesh:${mesh.id.raw}"); putJsonArray("points") {
			add(buildJsonArray { add(points[0]); add(points[1]) }); add(buildJsonArray { add(points[points.size - 2]); add(points.last()) }) } }
		assertEquals(WorkspacePathEdits.createPutCommand(capture.model.rig.puppet, sent(path, 1)).first,
			WorkspacePathEdits.createPutCommand(capture.model.rig.puppet, sent(path, 2)).first)
	}

	@Test fun splitPiecesAreNamedTheSameOnEverySend() {
		val capture = capture()
		val split = buildJsonObject {
			put("layer_id", "strip"); putJsonArray("names") { add("Top"); add("Bottom") }
			putJsonArray("polygon") { listOf(0 to 0, 12 to 0, 12 to 36, 0 to 36).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
		}
		fun pieces(attempt: Int) = applied(capture, "source_split_polygon", sent(split, attempt)).source.layers.map { it.id.raw }
		val first = pieces(1)
		assertEquals(2, first.size)
		assertTrue(first.all { it.startsWith("split:") })
		assertEquals(first, pieces(2))
	}
}
