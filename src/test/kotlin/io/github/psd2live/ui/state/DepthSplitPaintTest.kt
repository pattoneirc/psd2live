package io.github.psd2live.ui.state

import io.github.psd2live.core.DepthSplitFixture.mesh
import io.github.psd2live.core.DepthSplitFixture.original
import io.github.psd2live.core.DepthSplitFixture.split
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Painting the front copy of a depth split on the canvas commits pixels only. */
class DepthSplitPaintTest {
    @TempDir lateinit var temp: Path

    @Test fun erasingFrontKeepsTextureRectangleRigAndGlueAfterReopen() = runBlocking {
        val pipeline = PSD2LivePipeline()
        val result = split(pipeline, original(pipeline))
        val preview = result.preview
        val front = mesh(preview, result.frontLayerId)
        val originalSource = preview.analysis.layers.single { it.source.id.raw == "collar" }.source
        val frontSource = preview.analysis.layers.single { it.source.id.raw == result.frontLayerId }.source
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(WorkspaceStateCodec.decode(WorkspaceSettingsCodec.encode(preview.config),
                vm.state.value.copy(projectId = "depth-paint", analysis = preview.analysis, previewModel = preview,
                    rigEdits = preview.config.rigEdits, parentOverrides = preview.config.parentOverrides,
                    layerOverrides = preview.config.layerOverrides, drawOrderOverrides = preview.config.drawOrderOverrides)))
            DesktopWorkspace(vm, temp.resolve("paint-store")).use { workspace ->
                vm.attachWorkspace(workspace)
                val editor = vm.canvasEditor
                editor.selectLayer(result.frontLayerId)
                val session = assertNotNull(editor.startPaintSession(result.frontLayerId))
                editor.clearCurrentLayerPaint()
                assertTrue(session.isDirty)
                editor.promptCommitPaintSession()
                withTimeout(10000) { vm.state.first { !it.workspaceEditBusy } }
                assertNull(vm.state.value.errorMessage)
                assertFalse(editor.showRebuildMeshDialog, "Depth-copy paint should apply without offering mesh reconstruction")
                val painted = assertNotNull(vm.state.value.previewModel)
                val paintedSource = painted.analysis.layers.single { it.source.id.raw == result.frontLayerId }.source
                assertEquals(frontSource.bounds, paintedSource.bounds)
                assertTrue(paintedSource.raster.rgba.indices.filter { it % 4 == 3 }.all { paintedSource.raster.rgba[it] == 0.toByte() })
                assertContentEquals(originalSource.raster.rgba, painted.analysis.layers.single { it.source.id.raw == "collar" }.source.raster.rgba)
                assertContentEquals(front.mesh!!.positions, mesh(painted, result.frontLayerId).mesh!!.positions)
                assertEquals(preview.rig.puppet.glues.single().pairs.size, painted.rig.puppet.glues.single().pairs.size)
                val reopened = pipeline.buildPreview(painted.analysis.source, painted.config)
                val loadedFront = mesh(reopened, result.frontLayerId)
                assertContentEquals(front.mesh!!.indices, loadedFront.mesh!!.indices)
                assertEquals(frontSource.bounds.width, reopened.rig.puppet.atlas.tiles.single { it.id == loadedFront.atlasTileId }.width)
                assertEquals(preview.rig.puppet.glues.single().pairs.size, reopened.rig.puppet.glues.single().pairs.size)
                assertEquals(2, workspace.history().nodes.size)
            }
        }
    }
}
