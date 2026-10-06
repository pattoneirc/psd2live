package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A paint or image replace rebuilds from the model it starts from - borrowing its atlas pages and preview PNG
 * strips, its analysed layers and its bound base rig - yet must equal a build of the committed document from
 * nothing: same IR, so same layout, texture coordinates, pixels and exports.
 */
@org.junit.jupiter.api.Tag("slow")
class WorkspaceSourcePixelsRebuildTest {
	private fun ir(model: RigPreviewModel) = ContentHash.of(RigIrCompiler.compile(model))

	private suspend fun fresh(document: WorkspaceDocument) = WorkspacePreviewBuilder().build(document)

	private fun assertSameAsFresh(model: RigPreviewModel, document: WorkspaceDocument) = runBlocking {
		val rebuilt = fresh(document)
		assertEquals(rebuilt.atlas.placementByLayerId, model.atlas.placementByLayerId)
		for ((index, page) in rebuilt.atlas.pages.withIndex()) {
			val image = model.atlas.pages[index].image
			assertContentEquals(page.image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
				image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
		}
		assertEquals(ir(rebuilt), ir(model))
	}

	@Test fun paintAndReplaceRebuildLikeAFreshBuildAndUndoReturnsToTheSameAtlas() = runBlocking<Unit> {
		val builder = WorkspacePreviewBuilder()
		lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
		runtime = WorkspaceRuntime({ document -> builder.build(document, runtime.capture().model) })
		WorkspaceSourceImporter(runtime).importPsd(Path.of("examples/tml/psd-input/tml.psd").toAbsolutePath(), null, runtime.state.value.state)
		val raster = WorkspaceRasterCommands(runtime)
		val start = runtime.capture()
		val meshLayers = start.model.rig.puppet.drawables.filter { it.mesh != null }.mapNotNullTo(HashSet()) { start.model.rig.layerIdByDrawableId[it.id.raw] }
		val layers = start.document.source.layers.filter { it.id.raw in meshLayers && it.raster.width * it.raster.height >= 32 * 32 }
			.sortedBy { it.raster.width * it.raster.height }
		val small = layers.first(); val middle = layers[layers.size / 2]

		// A repaint inside the layer keeps every tile's size: one tile is redrawn in place and the bound base rig
		// is the very same instance, so replay checkpoints keyed by it keep hitting.
		val image = start.document.sourceLayerImage(small.id.raw)
		val cx = small.bounds.left + small.bounds.width / 2; val cy = small.bounds.top + small.bounds.height / 2
		for (y in cy - 3 until cy + 3) for (x in cx - 3 until cx + 3) image.setRGB(x, y, 0xff3366aa.toInt())
		val painted = raster.commitRaster(start.projectId, start.state, WorkspacePaintRaster.capture(small.id.raw, image, rebuildMesh = false),
			"Paint", MutationAuthor.USER).commit.capture
		assertNotNull(painted.document.generationSource)
		assertEquals(start.document.rigEdits.authoringJournal, painted.document.rigEdits.authoringJournal)
		assertEquals(start.model.atlas.placementByLayerId, painted.model.atlas.placementByLayerId)
		assertSameAsFresh(painted.model, painted.document)
		val again = raster.commitRaster(painted.projectId, painted.state, WorkspacePaintRaster.capture(small.id.raw,
			painted.document.sourceLayerImage(small.id.raw).also { it.setRGB(cx, cy, 0xff10aa20.toInt()) }, rebuildMesh = false),
			"Paint again", MutationAuthor.USER).commit.capture
		assertSame(painted.model.baseRig.puppet, again.model.baseRig.puppet)
		val page = again.model.atlas.placementByLayerId.getValue(small.id.raw).page
		for (index in again.model.atlas.pages.indices) {
			if (index == page) assertNotSame(painted.model.atlas.pages[index], again.model.atlas.pages[index])
			else assertSame(painted.model.atlas.pages[index], again.model.atlas.pages[index])
		}
		assertSameAsFresh(again.model, again.document)

		// Painting past the layer's edge grows its tile and reflows the shelf.
		val grown = again.document.sourceLayerImage(small.id.raw)
		for (y in small.bounds.top until small.bounds.top + 4) for (x in small.bounds.left - 9 until small.bounds.left) grown.setRGB(x, y, 0xffaa3366.toInt())
		val reflowed = raster.commitRaster(again.projectId, again.state, WorkspacePaintRaster.capture(small.id.raw, grown, rebuildMesh = false),
			"Paint beyond", MutationAuthor.USER).commit.capture
		assertEquals(small.raster.width + 9, reflowed.model.atlas.placementByLayerId.getValue(small.id.raw).width)
		assertSameAsFresh(reflowed.model, reflowed.document)

		// An image replace with a mesh rebuild, and an undo back to the first paint.
		val mirrored = reflowed.document.sourceLayerImage(middle.id.raw)
		val b = middle.bounds
		val source = reflowed.document.sourceLayerImage(middle.id.raw)
		for (y in b.top until b.top + b.height) for (x in b.left until b.left + b.width) mirrored.setRGB(x, y, source.getRGB(b.left + b.width - 1 - (x - b.left), y))
		val replaced = raster.commitRaster(reflowed.projectId, reflowed.state, WorkspacePaintRaster.capture(middle.id.raw, mirrored, rebuildMesh = true),
			"Replace", MutationAuthor.USER).commit.capture
		assertTrue(replaced.document.rigEdits.authoringJournal.size > reflowed.document.rigEdits.authoringJournal.size)
		assertSameAsFresh(replaced.model, replaced.document)
		val undone = runtime.checkout(replaced.projectId, replaced.state, painted.historyHead)
		assertEquals(ir(painted.model), ir(undone.model))
		assertSameAsFresh(undone.model, undone.document)
	}
}
