package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.test.*

/** Painting a layer whose raster is denser than its canvas rectangle edits that raster at its own density. */
class WorkspaceDensePaintTest {
    private val builder = WorkspacePreviewBuilder()

    /** A disc touching all four edges of [size] pixels, coloured by position so any resampling shows. */
    private fun disc(size: Int): LayerRaster = LayerRaster(size, size, ByteArray(size * size * 4).also { rgba ->
        val r = size / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x + 0.5f - r; val dy = y + 0.5f - r
            if (dx * dx + dy * dy > r * r) continue
            val o = (y * size + x) * 4
            rgba[o] = (40 + 160 * x / size).toByte(); rgba[o + 1] = 60; rgba[o + 2] = (200 - 120 * y / size).toByte(); rgba[o + 3] = -1
        }
    })

    /** Every pixel of [raster] opaque, coloured by position. */
    private fun gradient(width: Int, height: Int) = LayerRaster(width, height, ByteArray(width * height * 4).also { rgba ->
        for (y in 0 until height) for (x in 0 until width) {
            val o = (y * width + x) * 4
            rgba[o] = (x * 3).toByte(); rgba[o + 1] = (y * 5).toByte(); rgba[o + 2] = 90; rgba[o + 3] = -1
        }
    })

    private fun layer(id: String, order: Int, bounds: LayerBounds, raster: LayerRaster) = WorkspaceSourceLayer(
        LayerId(id), id, "", SourceLayerKind.Raster, true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
        raster, null, null, false)

    private fun document(art: LayerRaster, bounds: LayerBounds = LayerBounds(70, 30, 32, 32)): WorkspaceDocument {
        val body = LayerRaster(40, 56, ByteArray(40 * 56 * 4) { if (it % 4 == 3) -1 else 120 })
        val source = WorkspaceSourceArt(128, 96, listOf(
            layer("body", 0, LayerBounds(10, 20, 40, 56), body),
            layer("art", 1, bounds, art),
        ), emptyList())
        val config = PipelineConfig(atlasSize = 2048, meshSpacing = 8, meshOnly = true, generatePhysics = false, exportMoc3 = false)
        return WorkspaceDocument(source, emptyMap(), emptySet(),
            mapOf("art" to LayerClassificationOverride(tag = SemanticTag.OBJECTS)), emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
    }

    private suspend fun runtime(document: WorkspaceDocument) = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }).also {
        it.install(it.state.value.state, "dense-paint", document, builder.build(document))
    }

    private fun art(document: WorkspaceDocument) = document.source.layers.single { it.id.raw == "art" }

    private fun brush(points: List<Pair<Float, Float>>, radius: Float, rgba: List<Int> = listOf(250, 20, 30, 255)) = buildJsonObject {
        put("mode", "brush"); put("radius", radius); put("hardness", 1)
        put("color", JsonArray(rgba.map(::JsonPrimitive)))
        putJsonArray("points") { for ((x, y) in points) add(buildJsonArray { add(x); add(y) }) }
    }

    @Test fun anUntouchedDenseSessionCapturesItsLayerPixelExactly() = runBlocking<Unit> {
        val document = document(gradient(64, 64), LayerBounds(12, 8, 32, 32))
        val runtime = runtime(document)
        val sessions = WorkspacePaintSessions(runtime)
        val session = sessions.begin(runtime.capture().state, "art")
        // The whole canvas at the layer's density of two raster pixels per canvas unit.
        assertEquals(256, session.width); assertEquals(192, session.height)
        assertEquals(0f, session.originX); assertEquals(2f, session.scaleX)
        val painting = document.layerPaintImage("art")
        val captured = WorkspacePaintRaster.capture("art", painting.image, painting.space, painting.space.layerArea(), rebuildMesh = false)
        assertEquals(64, captured.raster.width)
        assertContentEquals(art(document).raster.rgba, captured.raster.rgba)
        assertEquals(LayerCanvasRect(12f, 8f, 32f, 32f), captured.rect)
        assertSame(document, WorkspaceRasterEdits.prepare(document, runtime.capture().model, captured))
        sessions.clear()
    }

    @Test fun aStrokeOnADenseLayerPaintsAtItsDensityAndRoundTripsPixelExact() = runBlocking<Unit> {
        val document = document(gradient(64, 64), LayerBounds(12, 8, 32, 32))
        val runtime = runtime(document)
        val sessions = WorkspacePaintSessions(runtime)
        val session = sessions.begin(runtime.capture().state, "art")
        session.gesture(brush(listOf(20f to 16f, 26f to 16f), radius = 2f))
        val result = sessions.commit(session.id, session.sessionState, rebuildMesh = false, preserve = false, author = MutationAuthor.USER)
        assertTrue(result.mutation.applied)
        val after = art(result.commit.capture.document)
        // Same canvas rectangle, same raster size: nothing collapsed to canvas resolution.
        assertEquals(LayerBounds(12, 8, 32, 32), after.bounds)
        assertNull(after.storedCanvasRect)
        assertEquals(64, after.raster.width); assertEquals(64, after.raster.height)
        val before = art(document).raster.rgba
        var changed = 0
        for (y in 0 until 64) for (x in 0 until 64) {
            val o = (y * 64 + x) * 4
            // Raster pixel centre in canvas units, against the capsule from (20,16) to (26,16) of radius 2.
            val cx = 12f + (x + 0.5f) / 2f; val cy = 8f + (y + 0.5f) / 2f
            val along = (cx - 20f).coerceIn(0f, 6f)
            val distance = kotlin.math.hypot(cx - 20f - along, cy - 16f)
            val same = (0..3).all { before[o + it] == after.raster.rgba[o + it] }
            if (distance > 2.01f) assertTrue(same, "pixel $x,$y outside the stroke must be untouched")
            if (!same) changed++
            if (distance < 1.5f) assertEquals(listOf(250, 20, 30, 255), (0..3).map { after.raster.rgba[o + it].toInt() and 255 })
        }
        // A 2-unit brush covers about pi * 4^2 + 12 * 8 raster pixels at density 2, not a canvas-resolution quarter of that.
        assertTrue(changed > 120, "changed $changed")
        // The rebuild binds the same rectangle onto the dense tile: the tile keeps the raster's size.
        val tile = result.commit.capture.model.atlas.placementByLayerId.getValue("art")
        assertEquals(64, tile.width)
        sessions.clear()
    }

    @Test fun aStrokeOnAHighResolutionPupilStaysHighResolution() = runBlocking<Unit> {
        val document = document(disc(1024))
        val runtime = runtime(document)
        val before = runtime.capture()
        val result = WorkspaceRasterCommands(runtime).execute(before.projectId, before.state,
            WorkspaceDocumentOperation("source_paint_brush", JsonObject(buildJsonObject { put("layer_id", "art") } +
                brush(listOf(86f to 46f), radius = 2f).filterKeys { it != "mode" })), "Paint pupil", MutationAuthor.AGENT)
        assertTrue(result.mutation.applied)
        val after = art(result.commit.capture.document)
        assertEquals(1024, after.raster.width); assertEquals(1024, after.raster.height)
        assertEquals(LayerSpace.of(art(document)), LayerSpace.of(after))
        var red = 0
        for (i in 0 until 1024 * 1024) if ((after.raster.rgba[i * 4].toInt() and 255) == 250 && after.raster.rgba[i * 4 + 1].toInt() == 20) red++
        // Radius 2 canvas units is 64 raster pixels: a disc of about 12 900 pixels, painted with a sharp edge.
        assertTrue(red in 12_000..13_800, "painted $red pixels")
        val tile = result.commit.capture.model.atlas.placementByLayerId.getValue("art")
        assertEquals(1024, tile.width); assertEquals(1024, tile.height)
        // The pupil keeps its mesh: geometry does not follow a repaint that keeps meshes.
        val drawable = { model: RigPreviewModel -> model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == "art" } }
        assertContentEquals(drawable(before.model).mesh!!.positions, drawable(result.commit.capture.model).mesh!!.positions)
    }

    @Test fun paintingBeyondTheRectangleGrowsItAtTheLayersDensity() = runBlocking<Unit> {
        val document = document(gradient(64, 64), LayerBounds(12, 8, 32, 32))
        val runtime = runtime(document)
        val sessions = WorkspacePaintSessions(runtime)
        val session = sessions.begin(runtime.capture().state, "art")
        // A pen dot well right of the layer, at canvas (60.5, 20.5).
        session.gesture(brush(listOf(60.5f to 20.5f), radius = 1f))
        val result = sessions.commit(session.id, session.sessionState, rebuildMesh = false, preserve = false, author = MutationAuthor.USER)
        val after = art(result.commit.capture.document)
        val space = LayerSpace.of(after)
        assertEquals(2f, space.scaleX, 1e-4f); assertEquals(2f, space.scaleY, 1e-4f)
        assertEquals(12f, space.left); assertEquals(8f, space.top)
        assertTrue(space.right in 61.4f..61.6f, "right ${space.right}")
        assertEquals(12, after.bounds.left); assertEquals(62 - 12, after.bounds.width)
        // The original pixels sit unchanged at the same place in the grown raster.
        val old = art(document).raster
        for (y in 0 until 64) for (x in 0 until 64) for (c in 0..3)
            assertEquals(old.rgba[(y * 64 + x) * 4 + c], after.raster.rgba[(y * after.raster.width + x) * 4 + c])
        // Saving and reopening keeps the fractional rectangle exactly.
        val reopened = builder.build(result.commit.capture.document)
        assertEquals(after.raster.width, reopened.atlas.placementByLayerId.getValue("art").width)

        // Cropping it back shrinks the rectangle again at the same density.
        val again = sessions.begin(result.commit.capture.state, "art")
        again.gesture(buildJsonObject {
            put("mode", "eraser"); put("radius", 3); put("hardness", 1)
            putJsonArray("points") { add(buildJsonArray { add(60.5); add(20.5) }) }
        })
        val cropped = sessions.commit(again.id, again.sessionState, rebuildMesh = false, preserve = false, author = MutationAuthor.USER)
        val back = art(cropped.commit.capture.document)
        assertEquals(LayerBounds(12, 8, 32, 32), back.bounds)
        assertNull(back.storedCanvasRect)
        assertContentEquals(old.rgba, back.raster.rgba)
        sessions.clear()
    }

    @Test fun paintingADenseLayerOfAnImportedCmo3ModelKeepsItsDensityAndSamplesThePaintedTexels() = runBlocking<Unit> {
        val drawables = listOf("paint", "other").mapIndexed { index, name ->
            org.umamo.runtime.model.Drawable(org.umamo.runtime.model.DrawableId(name), name, null, org.umamo.runtime.model.BlendMode.Normal,
                emptyList(), org.umamo.runtime.model.DrawableMesh(floatArrayOf(2f + index, 2f, 12f + index, 2f, 2f + index, 12f),
                    floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)), null)
        }
        val puppet = org.umamo.runtime.model.PuppetModel(listOf(org.umamo.runtime.model.Parameter(
            org.umamo.runtime.model.ParameterId("ParamCustom"), "Custom", -1f, 1f, 0f)), emptyList(), emptyList(), drawables,
            drawables.map { org.umamo.runtime.model.OrgChild.Drawable(it.id) }, null, canvasWidth = 32f, canvasHeight = 32f)
        val page = ByteArray(8 * 8 * 4) { if (it % 4 == 3) -1 else if (it % 4 == 0) 0xcc.toByte() else 0x44 }
        val bytes = org.umamo.format.cmo3.Cmo3.write(org.umamo.interop.cmo3.Cmo3Conversion.freshCmo3(puppet,
            listOf(org.umamo.interop.cmo3.Cmo3Conversion.AtlasPage(org.umamo.format.png.PngCodec.write(
                org.umamo.format.raster.RasterImage(8, 8, page)), 8, 8)),
            drawables.associate { it.id.raw to 0 }, "test", 0L, 0x42).model)
        val (source, config) = Cmo3ModelImport.prepare(bytes, Cmo3ImportMode.NEW, null, PipelineConfig())
        val imported = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
        // The painted layer holds four raster pixels per canvas unit.
        val original = imported.source.layers.single { it.id.raw == "paint" }
        val dense = LayerRaster(original.raster.width * 4, original.raster.height * 4,
            ByteArray(original.raster.width * 4 * original.raster.height * 4 * 4) { if (it % 4 == 3) -1 else if (it % 4 == 1) 0x77 else 0x20 })
        val document = LayerImageReplace.replace(imported, "paint", dense, LayerImageReplace.Fit.STRETCH)
        val runtime = runtime(document)
        val before = runtime.capture()
        val result = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Paint dense imported mesh", listOf(
            WorkspaceDocumentOperation("source_paint_pencil", buildJsonObject {
                put("layer_id", "paint"); put("radius", 1)
                put("points", buildJsonArray { add(buildJsonArray { add(4.5); add(4.5) }) })
                put("color", buildJsonArray { add(20); add(90); add(220); add(255) })
            })), MutationAuthor.AGENT)
        assertTrue(result.applied)
        val after = result.capture.document.source.layers.single { it.id.raw == "paint" }
        val space = LayerSpace.of(after)
        assertEquals(4f, space.scaleX, 1e-4f); assertEquals(4f, space.scaleY, 1e-4f)
        assertEquals(listOf(20, 90, 220, 255), result.capture.document.sampleSourceColor("paint", 4, 4))
        // A one-unit pencil is eight raster pixels across, not two.
        val painted = (0 until after.raster.width * after.raster.height).count { (after.raster.rgba[it * 4 + 2].toInt() and 255) == 220 }
        assertTrue(painted in 40..64, "painted $painted")
        for (drawable in before.model.rig.puppet.drawables) {
            val now = result.capture.model.rig.puppet.drawables.single { it.id == drawable.id }
            assertContentEquals(drawable.mesh!!.positions, now.mesh!!.positions)
        }
        val frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 32f, 32f))
        val png = WorkspaceViewRenderer.modelComposite(result.capture.model, "dense-paint", emptyMap(), setOf("paint"), emptySet(),
            frame, WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(512)).png
        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
        assertEquals(0xff145adc.toInt(), image.getRGB((4.5f * 16).toInt(), (4.5f * 16).toInt()), "The preview must sample the painted texels")
    }

    @Test fun aLayerAtOnePixelPerUnitStillPaintsOnTheCanvasItself() = runBlocking<Unit> {
        val document = document(gradient(32, 32), LayerBounds(12, 8, 32, 32))
        val painting = document.layerPaintImage("art")
        assertTrue(painting.space.isCanvas)
        assertEquals(128, painting.image.width); assertEquals(96, painting.image.height)
        val canvas = document.sourceLayerImage("art")
        assertContentEquals(canvas.getRGB(0, 0, 128, 96, null, 0, 128), painting.image.getRGB(0, 0, 128, 96, null, 0, 128))
        // The session capture and the whole-canvas capture prepare the same document.
        val model = builder.build(document)
        val gesture = buildJsonObject { put("layer_id", "art") } + brush(listOf(14f to 10f, 50f to 12f), radius = 3f)
        val (painted, touched) = document.paintLayerImage(JsonObject(gesture))
        val region = painted.space.layerArea().union(touched!!)
        val sessionCapture = WorkspacePaintRaster.capture("art", painted.image, painted.space, region, rebuildMesh = false)
        val canvasCapture = WorkspacePaintRaster.capture("art", document.paintSourceImage(JsonObject(gesture)), rebuildMesh = false)
        val a = WorkspaceRasterEdits.prepare(document, model, sessionCapture)
        val b = WorkspaceRasterEdits.prepare(document, model, canvasCapture)
        assertEquals(WorkspaceRevisions.of(b), WorkspaceRevisions.of(a))
        assertNotEquals(WorkspaceRevisions.of(document), WorkspaceRevisions.of(a))
    }
}
