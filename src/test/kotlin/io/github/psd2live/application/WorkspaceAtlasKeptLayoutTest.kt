package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With the automatic arrangement off, a texture edit moves only the tiles it names. A tile placed into free space
 * (displaced by a grown neighbour, or new to the arrangement) keeps that spot, instead of jumping into the room
 * a later move or shrink leaves.
 */
@org.junit.jupiter.api.Tag("slow")
class WorkspaceAtlasKeptLayoutTest {
	@Test fun aDisplacedTileStaysWhereItWasPlacedWhileOtherTilesMove() = runBlocking<Unit> {
		val builder = WorkspacePreviewBuilder()
		lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
		runtime = WorkspaceRuntime({ document -> builder.build(document, runtime.capture().model) })
		WorkspaceSourceImporter(runtime).importPsd(Path.of("examples/tml/psd-input/tml.psd").toAbsolutePath(), null, runtime.state.value.state)
		val textures = WorkspaceTextureCommands(runtime)
		fun placements() = runtime.capture().model.atlas.placementByLayerId
		suspend fun edit(edit: WorkspaceTextureEdit) = runtime.capture().let { textures.execute(it.projectId, it.state, edit, MutationAuthor.USER) }
		fun moved(before: Map<String, AtlasPlacement>) = placements().filter { (id, at) -> before[id]?.let { it.page != at.page || it.x != at.x || it.y != at.y } != false }.keys

		val start = placements()
		val ordered = start.entries.sortedByDescending { it.value.width * it.value.height }.map { it.key }
		// Doubling a large tile pushes a neighbour out of its stored spot into free space.
		val grown = ordered[3]
		edit(WorkspaceTextureEdit.SetPixelDensity(listOf(grown), 2f))
		val displaced = moved(start) - grown
		assertTrue(displaced.isNotEmpty(), "the fixture no longer displaces a neighbour")

		// Every later edit moves only its own tile; the displaced ones stay put.
		for (id in ordered.filter { it != grown && it !in displaced }.take(4)) {
			val before = placements()
			edit(WorkspaceTextureEdit.SetPixelDensity(listOf(id), 0.5f))
			assertEquals(emptySet(), moved(before) - id, "halving $id")
		}
		// Moving the grown tile back to a spot of its own leaves room the displaced tiles do not jump into.
		val before = placements()
		val far = before.getValue(grown)
		val free = runtime.capture().model.atlas.pages[far.page].image.width - far.width
		val result = edit(WorkspaceTextureEdit.SetTile(grown, TexturePin(far.page, free, free)))
		assertEquals(listOf(grown), result.result.layerIds)
		assertEquals(emptySet(), moved(before) - grown)

		// Reopened, the document builds the same layout.
		val capture = runtime.capture()
		assertEquals(capture.model.atlas.placementByLayerId, builder.build(capture.document).atlas.placementByLayerId)
	}
}
