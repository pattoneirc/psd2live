package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceImageFit
import io.github.psd2live.project.LayerCanvasRect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** The texture workspace's view-model commands through the desktop workspace, history and undo. */
@Tag("slow")
class TextureWorkspaceViewModelTest {
    @TempDir lateinit var temporary: Path

    private suspend fun settled(vm: PSD2LiveViewModel) =
        withTimeout(60_000) { vm.state.first { !it.textureWorkspace.busy && !it.workspaceEditBusy } }

    private fun fixture(action: suspend (PSD2LiveViewModel, DesktopWorkspace, List<String>) -> Unit) = runBlocking<Unit> {
        val art = temporary.resolve("art.png")
        val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        for (y in 6..40) for (x in 6..40) image.setRGB(x, y, 0xff8899aa.toInt())
        ImageIO.write(image, "png", art.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                val layers = workspace.createArtwork(buildJsonObject {
                    put("width", 96); put("height", 64); putJsonArray("layers") {
                        for (name in listOf("First", "Second")) add(buildJsonObject { put("path", art.toString()); put("name", name); put("role", "objects") })
                    }
                }).affectedLayerIds
                action(vm, workspace, layers)
            }
        }
    }

    @Test fun densityOfTheSelectionCommitsOnceAndUndoes() = fixture { vm, workspace, layers ->
        val before = assertNotNull(vm.textureSnapshot())
        val id = layers.first()
        vm.selectLayer(id)
        assertEquals(id, before.textureLayerId(vm.state.value.selectedLayerId!!, vm.state.value.previewModel))
        val tile = before.tilesByLayer.getValue(id)
        val head = vm.state.value.historySnapshot!!.headNodeId
        val nodes = workspace.history().nodes.size

        vm.setTextureDensity(before, listOf(id), 2f)
        settled(vm)
        assertNull(vm.state.value.textureWorkspace.error)
        assertEquals(nodes + 1, workspace.history().nodes.size)
        val after = assertNotNull(vm.textureSnapshot())
        assertNotEquals(before.state, after.state)
        assertEquals(2f, after.layer(id)!!.override.density)
        assertEquals(tile.width * 2, after.tilesByLayer.getValue(id).width)

        vm.undoHistory()
        withTimeout(60_000) { vm.state.first { it.historySnapshot?.headNodeId == head && !it.workspaceEditBusy } }
        val undone = assertNotNull(vm.textureSnapshot())
        assertNull(undone.layer(id)!!.override.density)
        assertEquals(tile.width, undone.tilesByLayer.getValue(id).width)
    }

    @Test fun draggingATileMovesItOnceWhereItIsDroppedAndKeepsTheOthers() = fixture { vm, workspace, layers ->
        val snapshot = assertNotNull(vm.textureSnapshot())
        val id = layers.last()
        val tile = snapshot.tilesByLayer.getValue(id)
        val page = snapshot.atlas.pages[tile.page]
        val nodes = workspace.history().nodes.size
        // A drag moves only the draft; the target lies past the page edge and is clamped inside.
        for (step in 1..5) vm.dragTextureTile(snapshot, id, tile.x + step * 30f, page.height - tile.height + 50f, snap = 0f)
        val draft = assertNotNull(vm.state.value.textureWorkspace.dragDraft)
        assertEquals(page.height - tile.height, draft.y)
        assertEquals(nodes, workspace.history().nodes.size)
        vm.endTextureTileDrag(snapshot)
        settled(vm)
        assertNull(vm.state.value.textureWorkspace.dragDraft)
        assertEquals(nodes + 1, workspace.history().nodes.size)
        val other = assertNotNull(vm.textureSnapshot())
        val moved = other.tilesByLayer.getValue(id)
        assertEquals(draft.x to draft.y, moved.x to moved.y)
        assertFalse(other.atlas.auto, "A new project keeps its layout")
        val second = layers.first()
        assertEquals(snapshot.tilesByLayer.getValue(second).let { it.x to it.y }, other.tilesByLayer.getValue(second).let { it.x to it.y })

        // A drop over any other tile collides and commits nothing.
        vm.dragTextureTile(other, second, moved.x.toFloat(), moved.y.toFloat(), snap = 0f)
        assertTrue(vm.state.value.textureWorkspace.dragDraft!!.collides)
        vm.endTextureTileDrag(other)
        assertNotNull(vm.state.value.textureWorkspace.error)
        assertEquals(nodes + 1, workspace.history().nodes.size)
    }

    @Test fun budgetLockAndRejectedMovesReportThroughTheWorkspaceState() = fixture { vm, workspace, layers ->
        val snapshot = assertNotNull(vm.textureSnapshot())
        val nodes = workspace.history().nodes.size
        vm.setAtlasBudget(snapshot, padding = snapshot.atlas.budget.padding)
        assertFalse(vm.state.value.textureWorkspace.busy, "An unchanged budget sends no command")
        vm.setAtlasBudget(snapshot, padding = 6, maxPages = 2)
        settled(vm)
        val budget = vm.textureSnapshot()!!.atlas.budget
        assertEquals(6, budget.padding); assertEquals(2, budget.maxPages)
        assertEquals(nodes + 1, workspace.history().nodes.size)

        vm.setTextureLock(vm.textureSnapshot()!!, layers, true)
        settled(vm)
        assertTrue(layers.all { vm.textureSnapshot()!!.layer(it)!!.override.lock })

        // Moving a layer keeps its pixels; a refused move shows why and adds nothing.
        val id = layers.first()
        val rect = vm.textureSnapshot()!!.layer(id)!!.canvasRect
        vm.setLayerCanvasRect(vm.textureSnapshot()!!, id, LayerCanvasRect(rect.left + 4f, rect.top, rect.width * 2f, rect.height))
        settled(vm)
        val moved = vm.textureSnapshot()!!.layer(id)!!
        assertEquals(rect.width * 2f, moved.canvasRect.width)
        val count = workspace.history().nodes.size
        vm.setLayerCanvasRect(vm.textureSnapshot()!!, id, LayerCanvasRect(0f, 0f, 0f, 4f))
        settled(vm)
        assertNotNull(vm.state.value.textureWorkspace.error)
        assertEquals(count, workspace.history().nodes.size)

        vm.setTextureReplaceOptions(WorkspaceImageFit.CONTAIN, true)
        assertEquals(WorkspaceImageFit.CONTAIN, vm.state.value.textureWorkspace.replaceFit)
    }

    @Test fun cornerScalingKeepsEachLayersRatioAndArrangingIsOneAction() = fixture { vm, workspace, layers ->
        val (first, second) = layers
        vm.setTextureDensity(vm.textureSnapshot()!!, listOf(second), 0.5f)
        settled(vm)
        val nodes = workspace.history().nodes.size
        vm.selectLayers(layers)
        assertEquals(layers.toSet(), vm.state.value.selectedLayerIds)
        // Twice the density for both: the second keeps half the first's, and 1x stores no override.
        vm.scaleTextureDensity(vm.textureSnapshot()!!, layers, 2f)
        settled(vm)
        val scaled = vm.textureSnapshot()!!
        assertEquals(2f, scaled.layer(first)!!.override.density)
        assertNull(scaled.layer(second)!!.override.density)
        assertEquals(nodes + 2, workspace.history().nodes.size, "One command per resulting density")

        // Arranging is one command; arranging the same atlas again changes nothing.
        val count = workspace.history().nodes.size
        vm.arrangeAtlas(vm.textureSnapshot()!!)
        settled(vm)
        assertNull(vm.state.value.textureWorkspace.error)
        assertEquals(count + 1, workspace.history().nodes.size)
        val arranged = vm.textureSnapshot()!!
        assertFalse(arranged.atlas.auto)
        assertTrue(arranged.atlas.tiles.all { it.shaped }, "Tiles with meshes are arranged by their footprint")
        vm.arrangeAtlas(arranged)
        settled(vm)
        assertEquals(count + 1, workspace.history().nodes.size)

        // The automatic arrangement is a mode: on, the stored layout goes; off again, the current one is kept.
        vm.setAtlasAuto(vm.textureSnapshot()!!, true)
        settled(vm)
        val auto = vm.textureSnapshot()!!
        assertTrue(auto.atlas.auto)
        assertTrue(auto.atlas.tiles.none { it.shaped })
        vm.setAtlasAuto(auto, false)
        settled(vm)
        val kept = vm.textureSnapshot()!!
        assertFalse(kept.atlas.auto)
        assertEquals(auto.atlas.tiles.map { Triple(it.layerId, it.x, it.y) }, kept.atlas.tiles.map { Triple(it.layerId, it.x, it.y) })
        assertEquals(count + 3, workspace.history().nodes.size)

        vm.selectLayers(listOf(second), additive = true)
        assertEquals(layers.toSet(), vm.state.value.selectedLayerIds)
        vm.selectLayers(emptyList())
        assertTrue(vm.state.value.selectedLayerIds.isEmpty())
    }
}
