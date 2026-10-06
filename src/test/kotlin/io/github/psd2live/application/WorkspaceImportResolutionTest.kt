package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/** File imports, assets and registrations keep their pixels; placement only sets the canvas rectangle. */
class WorkspaceImportResolutionTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()

    private fun disc(size: Int): RasterImage = RasterImage(size, size, ByteArray(size * size * 4).also { rgba ->
        val r = size / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x + 0.5f - r; val dy = y + 0.5f - r
            if (dx * dx + dy * dy > r * r * 0.81f) continue
            val o = (y * size + x) * 4
            rgba[o] = (40 + 160 * x / size).toByte(); rgba[o + 1] = 60; rgba[o + 2] = (200 - 120 * y / size).toByte(); rgba[o + 3] = -1
        }
    })

    private fun pupil(model: RigPreviewModel, id: String) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == id }

    @Test fun aLargeFileImportKeepsItsPixelsAndRescalingOnlyMovesItsRectangle() = runBlocking<Unit> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val original = simulationFixture(runtime)
        val file = temporary.resolve("disc.png").also { Files.write(it, PngCodec.write(disc(1024))) }
        val added = WorkspaceImageLayerCommands(runtime).importImages(original.projectId, original.state, listOf(file), null, "Import", MutationAuthor.USER)
        val id = added.mutation.affectedLayerIds.single()
        val imported = runtime.capture().document.source.layers.single { it.id.raw == id }
        // Larger than the 64 x 96 canvas: fitted, every source pixel kept.
        assertTrue(imported.raster.width > 900 && imported.raster.width == imported.raster.height)
        assertTrue(abs(imported.canvasRect().width - 64f) < 1e-3f, "${imported.canvasRect()}")

        suspend fun place(left: Float, top: Float, width: Float, height: Float): WorkspaceCapture<RigPreviewModel> = runtime.capture().let {
            WorkspaceImagePlacementCommands(runtime).execute(it.projectId, it.state,
                WorkspaceImageBounds(id, left, top, width, height).operation(), "Place", MutationAuthor.USER).commit.capture
        }
        val first = place(20f, 12f, 32f, 32f)
        val placed = first.document.source.layers.single { it.id.raw == id }
        assertEquals(LayerCanvasRect(20f, 12f, 32f, 32f), placed.canvasRect())
        assertContentEquals(imported.raster.rgba, placed.raster.rgba)
        assertEquals(imported.raster.width, placed.raster.width)
        validateRegisteredNeutral(first.model, setOf(id))
        // Meshed at canvas density: a few dozen vertices, not thousands.
        assertTrue(pupil(first.model, id).mesh!!.vertexCount < 200, "${pupil(first.model, id).mesh!!.vertexCount} vertices")

        place(10.5f, 40.25f, 20f, 20f)
        val again = place(20f, 12f, 32f, 32f)
        val back = again.document.source.layers.single { it.id.raw == id }
        assertContentEquals(imported.raster.rgba, back.raster.rgba)
        assertContentEquals(pupil(first.model, id).mesh!!.positions, pupil(again.model, id).mesh!!.positions)
        assertContentEquals(pupil(first.model, id).mesh!!.indices, pupil(again.model, id).mesh!!.indices)

        // Squeezing it onto a speck is refused by name, and nothing is published.
        val before = runtime.capture()
        val refused = assertFailsWith<IllegalArgumentException> { place(0f, 0f, 1f, 1f) }
        assertTrue("density" in refused.message!!, refused.message)
        assertEquals(before.state, runtime.capture().state)
    }

    @Test fun legacyPlacementsReturnToTheirOriginalPixels() = runBlocking<Unit> {
        // A layer placed by an earlier build holds the original scaled to its bounds; a move restores the original.
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val original = simulationFixture(runtime)
        val file = temporary.resolve("disc.png").also { Files.write(it, PngCodec.write(disc(16))) }
        val id = WorkspaceImageLayerCommands(runtime).importImages(original.projectId, original.state, listOf(file), null, "Import", MutationAuthor.USER)
            .mutation.affectedLayerIds.single()
        val capture = runtime.capture()
        val layer = capture.document.source.layers.single { it.id.raw == id } as WorkspaceSourceLayer
        val scaled = org.umamo.format.art.LayerRaster(layer.raster.width * 2, layer.raster.height * 2, LayerImport.scaleRgba(
            RasterImage(layer.raster.width, layer.raster.height, layer.raster.rgba), layer.raster.width * 2, layer.raster.height * 2) {})
        val legacy = layer.copy(raster = scaled, bounds = org.umamo.format.art.LayerBounds(layer.bounds.left, layer.bounds.top, scaled.width, scaled.height))
        val document = capture.document.copy(source = WorkspaceSourceArt(capture.document.source.widthPx, capture.document.source.heightPx,
            capture.document.source.layers.map { if (it.id.raw == id) legacy else it }, capture.document.source.groups))
        val moved = WorkspaceImagePlacementEdits.apply(document, capture.model, WorkspaceImageBounds(id, 4f, 4f, 30f, 30f).operation()) {}
        val result = moved.source.layers.single { it.id.raw == id }
        assertContentEquals(layer.raster.rgba, result.raster.rgba)
        assertEquals(LayerCanvasRect(4f, 4f, 30f, 30f), result.canvasRect())
    }

    private fun asset(size: Int, rect: Bounds): WorkspacePngAsset {
        val image = disc(size)
        return WorkspacePngAsset(WorkspaceImportedPngAsset("asset-test", "sha", size, size,
            WorkspaceCanvasPlacement("canvas_top_left_y_down", rect, size, size, rect.width / size, rect.height / size, "view")), image.rgba)
    }

    @Test fun anAssetIsAddedAtItsOwnResolutionOverItsPlacement() {
        val document = runBlocking { simulationFixture(WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })) }.document
        val asset = asset(1024, Bounds(20f, 12f, 52f, 44f))
        val (added, id) = document.addLayer(asset, WorkspaceAddLayerRequest("asset-test", "", "Pupil", layerId = "pupil"))
        val layer = added.source.layers.single { it.id.raw == id }
        // Trimmed to its visible pixels, which keep their resolution; the rectangle scales with them.
        val trimmed = layer.raster.width
        assertTrue(trimmed in 900..1024 && layer.raster.height == trimmed)
        val rect = layer.canvasRect()
        assertTrue(abs(rect.width - trimmed * 32f / 1024) < 1e-3f, "$rect")
        assertTrue(abs(rect.left - (20f + (1024 - trimmed) / 2 * 32f / 1024)) < 0.05f, "$rect")
        val space = LayerSpace.of(layer)
        assertTrue(abs(space.scaleX - 32f) < 1e-2f)
        // Untrimmed, the raster is the asset itself over the full placement.
        val (whole, wholeId) = document.addLayer(asset, WorkspaceAddLayerRequest("asset-test", "", "Pupil", layerId = "whole", trimTransparent = false))
        val full = whole.source.layers.single { it.id.raw == wholeId }
        assertContentEquals(asset.rgba, full.raster.rgba)
        assertEquals(LayerCanvasRect(20f, 12f, 32f, 32f), full.canvasRect())
        assertNull(full.storedCanvasRect)
    }

    @Test fun aRegistrationPlacesByRectangleWithoutResampling() {
        val asset = asset(64, Bounds(0f, 0f, 64f, 64f))
        fun registration(transform: JsonObject) = buildJsonObject { put("asset_id", asset.public.id); put("transform", transform) }
        val placed = placedAsset(asset, registration(buildJsonObject { put("x", 10.25); put("y", 3); put("scale_x", 0.5) }))
        assertSame(asset.rgba, placed.rgba)
        assertEquals(64, placed.public.pixelWidth)
        assertEquals(Bounds(10.25f, 3f, 42.25f, 35f), placed.public.placement.canvasRect)
        assertEquals(0.5f, placed.public.placement.canvasUnitsPerPixelX)

        // Explicit reflection reverses columns exactly.
        val mirrored = placedAsset(asset, registration(buildJsonObject { put("x", 42.25); put("y", 3); put("scale_x", 0.5); put("mirror_x", true) }))
        assertEquals(Bounds(10.25f, 3f, 42.25f, 35f), mirrored.public.placement.canvasRect)
        for (y in listOf(10, 32, 50)) for (x in listOf(5, 20, 40)) for (c in 0..3)
            assertEquals(asset.rgba[(y * 64 + 63 - x) * 4 + c], mirrored.rgba[(y * 64 + x) * 4 + c])

        // A rotation is rasterized at the asset's own density into its bounding box.
        val rotated = placedAsset(asset, registration(buildJsonObject { put("x", 40); put("y", 0); put("scale_x", 0.5); put("rotation_degrees", 30) }))
        val box = rotated.public.placement.canvasRect
        assertTrue(abs(rotated.public.pixelWidth * 0.5f - box.width) <= 0.5f, "${rotated.public.pixelWidth} over $box")
        assertTrue(abs(rotated.public.placement.canvasUnitsPerPixelX - 0.5f) < 0.02f)
    }
}
