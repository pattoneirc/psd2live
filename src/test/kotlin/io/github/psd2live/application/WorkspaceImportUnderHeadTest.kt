package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.Deformer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/** An image imported under a head deformer keeps its place although it is no head layer, with the head rolled. */
class WorkspaceImportUnderHeadTest {
    @TempDir lateinit var temporary: Path

    private fun ellipse(id: String, name: String, order: Int, cx: Int, cy: Int, rx: Int, ry: Int): WorkspaceSourceLayer {
        val left = cx - rx; val top = cy - ry; val width = rx * 2; val height = ry * 2
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val dx = (x + 0.5f - rx) / rx; val dy = (y + 0.5f - ry) / ry
            if (dx * dx + dy * dy > 1f) continue
            val i = (y * width + x) * 4
            rgba[i] = (40 + order * 20).toByte(); rgba[i + 1] = 90; rgba[i + 2] = 120; rgba[i + 3] = -1
        }
        return WorkspaceSourceLayer(LayerId(id), name, "", SourceLayerKind.Raster, true, order, LayerBounds(left, top, width, height),
            1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
    }

    @Test fun anImportUnderTheHeadChainLandsWhereItWasPlaced() = runBlocking<Unit> {
        // The eyes sit on a line rolled about 9 degrees, so the head deformers are laid out in a rolled space.
        val layers = listOf(
            ellipse("body", "body", 0, 100, 230, 50, 60),
            ellipse("face", "face", 1, 100, 110, 50, 60),
            ellipse("eye_l", "eye white L", 2, 120, 102, 12, 7),
            ellipse("eye_r", "eye white R", 3, 80, 96, 12, 7),
            ellipse("mouth", "mouth", 4, 100, 140, 10, 4),
        )
        val config = PipelineConfig(atlasSize = 512, meshSpacing = 16, generatePhysics = false, exportMoc3 = false)
        val document = WorkspaceDocument(WorkspaceSourceArt(200, 300, layers, emptyList()), emptyMap(), emptySet(), emptyMap(),
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "head-project", document, builder.build(document))
        val rotation = runtime.capture().model.rig.puppet.deformers.single { it.id.raw == "DeformHeadRotation" } as Deformer.Rotation
        assertTrue(abs(rotation.baseAngle) > 1f, "the head is rolled: ${rotation.baseAngle}")

        val file = temporary.resolve("patch.png").also { Files.write(it, PngCodec.write(RasterImage(20, 20, ByteArray(20 * 20 * 4) { -1 }))) }
        for (parent in listOf("DeformHeadRotation", "DeformHeadContainer", "DeformFaceNinePose")) {
            val before = runtime.capture()
            // The commit checks the neutral mesh against where the image was placed and refuses any drift.
            val id = WorkspaceImageLayerCommands(runtime).importImages(before.projectId, before.state, listOf(file), parent, "Import", MutationAuthor.USER)
                .mutation.affectedLayerIds.single()
            val model = runtime.capture().model
            assertEquals(parent, model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == id }.parentDeformerId?.raw)
        }
    }
}
