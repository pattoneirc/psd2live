package io.github.psd2live.render

import io.github.psd2live.core.AtlasPage
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigCanvasSupport
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.umamo.format.art.*
import java.awt.image.BufferedImage
import kotlin.test.*

class GlPageTextureReleaseTest {
    private fun layer(id: String, order: Int, bounds: LayerBounds): WorkspaceSourceLayer {
        val rgba = ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) -1 else 90 }
        return WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order,
            bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(bounds.width, bounds.height, rgba), null, null, false)
    }

    /** [model] with its pages as new image instances, as an atlas rebuild makes them. */
    private fun rebuilt(model: RigPreviewModel): RigPreviewModel = model.copy(atlas = model.atlas.copy(pages = model.atlas.pages.map { page ->
        val image = BufferedImage(page.image.width, page.image.height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply { drawImage(page.image, 0, 0, null); dispose() }
        AtlasPage(image)
    }))

    private fun scene(model: RigPreviewModel): CanvasScene {
        val geometry = RigCanvasSupport.evaluateExact(model)
        return CanvasScene(64, 80, CanvasViewport(0.5, 0.0, 0.0, 128f, 160f), model, geometry,
            ArtworkDrawList.build(model, geometry, ArtworkOptions()))
    }

    /** The texture atlas view on [model]'s first page. */
    private fun atlasPage(model: RigPreviewModel): AtlasScene = AtlasScene(64, 64, CanvasViewport(0.25, 0.0, 0.0, 256f, 256f),
        OverlayScene(listOf(TextureQuad(ImageTexture(model.atlas.pages.first().image), 0f, 0f, 256f, -256f))))

    @Test fun pageTexturesAreFreedOnceNoViewShowsThem() {
        val host = GlHost.start()
        assumeTrue(host != null, "No OpenGL 3.3 context: ${GlHost.failure}")
        val first = PSD2LivePipeline().buildPreview(WorkspaceSourceArt(128, 160, listOf(
            layer("face", 1, LayerBounds(28, 10, 72, 70)),
            layer("body", 2, LayerBounds(30, 80, 68, 60)),
        ), emptyList()), PipelineConfig(atlasSize = 256, meshSpacing = 12))
        val second = rebuilt(first)
        val third = rebuilt(first)
        val firstScene = scene(first)
        val pages = firstScene.draws.map { it.page }.distinct().size
        assertTrue(pages > 0)
        try {
            host!!.submit {
                val renderer = GlCanvasRenderer()
                try {
                    renderer.render("edit", firstScene).close()
                    renderer.render("atlas", atlasPage(first)).close()
                    assertEquals(pages, renderer.pageTextureCount)

                    // The edit canvas moves on to a rebuilt atlas; the atlas view still shows an old page.
                    renderer.render("edit", scene(second)).close()
                    assertEquals(pages + 1, renderer.pageTextureCount)
                    renderer.render("atlas", atlasPage(second)).close()
                    assertEquals(pages, renderer.pageTextureCount, "old pages freed once no view shows them")

                    // Each commit of a session replaces the atlas again: the count stays put.
                    renderer.render("edit", scene(third)).close()
                    renderer.render("atlas", atlasPage(third)).close()
                    assertEquals(pages, renderer.pageTextureCount)

                    renderer.release("edit")
                    assertEquals(pages, renderer.pageTextureCount)
                    renderer.release("atlas")
                    assertEquals(0, renderer.pageTextureCount)
                } finally {
                    renderer.close()
                }
            }.get()
        } finally {
            host!!.close()
        }
    }
}
