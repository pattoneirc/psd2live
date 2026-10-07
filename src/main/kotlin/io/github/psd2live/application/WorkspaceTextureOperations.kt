package io.github.psd2live.application

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Strict request and result contracts of the texture operations. */
internal object WorkspaceTextureSchemas {
    private val s = WorkspaceResultSchema
    /** Operations that commit through a process job and join atomic batches. */
    val edits = WorkspaceTextureEdits.supported
    const val RENDER = "atlas_render_page"

    private fun rect(positive: Boolean) = s.obj(linkedMapOf("left" to s.number(), "top" to s.number()) +
        if (positive) mapOf("width" to JsonObject(s.number() + ("exclusiveMinimum" to JsonPrimitive(0))),
            "height" to JsonObject(s.number() + ("exclusiveMinimum" to JsonPrimitive(0))))
        else mapOf("width" to s.number(0), "height" to s.number(0)))
    private val pin = s.obj(mapOf("page" to s.integer(0, 63), "x" to s.integer(0, 16383), "y" to s.integer(0, 16383)))
    private fun nullable(value: JsonObject) = JsonObject(value + ("type" to JsonArray(listOf(value.getValue("type"), JsonPrimitive("null")))))

    fun request(id: String): JsonObject {
        val fields = linkedMapOf<String, JsonObject>("state" to s.handle())
        val required = mutableSetOf("state")
        when (id) {
            "layer_set_canvas_rect" -> { fields["layer_id"] = s.handle(); fields["rect"] = rect(true); required += setOf("layer_id", "rect") }
            "layer_replace_image" -> {
                fields["layer_id"] = s.handle(); fields["path"] = s.handle(); fields["png_base64"] = s.handle()
                fields["fit"] = s.choices("stretch", "contain"); fields["rebuild_mesh"] = s.boolean(); required += "layer_id"
            }
            "layer_set_pixel_density" -> {
                fields["layer_ids"] = JsonObject(s.array(s.handle(), 1, 128) + ("uniqueItems" to JsonPrimitive(true)))
                fields["density"] = nullable(s.number(WorkspaceTextureEdits.MIN_DENSITY, WorkspaceTextureEdits.MAX_DENSITY))
                fields["lock"] = s.boolean(); required += setOf("layer_ids", "density")
            }
            "atlas_set_tile" -> {
                fields["layer_id"] = s.handle()
                fields["pin"] = buildJsonObject { put("oneOf", JsonArray(listOf(pin, buildJsonObject { put("type", "null") }))) }
                required += setOf("layer_id", "pin")
            }
            "atlas_set_budget" -> {
                fields["page_size"] = buildJsonObject { put("type", "integer"); put("enum", JsonArray((8..14).map { JsonPrimitive(1 shl it) })) }
                fields["max_pages"] = s.integer(1, 64); fields["padding"] = s.integer(0, 32); fields["auto"] = s.boolean()
            }
            "atlas_pack" -> {
                fields["shape"] = s.choices("mesh", "rect")
                fields["layer_ids"] = JsonObject(s.array(s.handle(), 1, 4096) + ("uniqueItems" to JsonPrimitive(true)))
            }
            else -> error("Unknown texture operation: $id")
        }
        return s.obj(fields, required)
    }

    private val identity = s.identity + ("revision" to s.handle())
    private val editFields = identity + mapOf("applied" to s.boolean(), "layers" to s.array(s.handle()),
        "atlas_fit" to s.number(0, 1), "notices" to s.array(s.string()), "geometry_diagnostics" to WorkspaceGeometrySafetySchemas.report)
    val edit = s.obj(editFields, editFields.keys - "geometry_diagnostics")

    private val tileFields = linkedMapOf("page" to s.integer(0), "x" to s.integer(0), "y" to s.integer(0),
        "width" to s.integer(1), "height" to s.integer(1), "scale_x" to s.number(0), "scale_y" to s.number(0),
        "density" to s.number(0), "locked" to s.boolean(), "pinned" to s.boolean(), "shaped" to s.boolean())
    private val tile = s.obj(tileFields)
    private val listedTile = s.obj(linkedMapOf("layer_id" to s.handle()) + tileFields)
    private val read = linkedMapOf("project_id" to s.handle(), "state" to s.handle(), "revision" to s.handle())

    val layer: JsonObject = s.obj(read + linkedMapOf(
        "layer_id" to s.handle(), "name" to s.string(), "canvas_rect" to rect(false), "bounds" to rect(false),
        "raster" to s.obj(mapOf("width" to s.integer(0), "height" to s.integer(0))),
        "native_density" to s.obj(mapOf("x" to s.number(0), "y" to s.number(0))),
        "override" to s.obj(mapOf("density" to nullable(s.number(0)), "lock" to s.boolean(), "pin" to s.nullable(pin))),
        "deleted" to s.boolean(), "tile" to s.nullable(tile), "atlas_fit" to s.number(0, 1)))

    val atlas: JsonObject = s.obj(read + linkedMapOf(
        "budget" to s.obj(mapOf("page_size" to s.integer(1), "max_pages" to s.integer(1), "padding" to s.integer(0))),
        "fit" to s.number(0, 1), "notices" to s.array(s.string()), "auto" to s.boolean(),
        "pages" to s.array(s.obj(mapOf("index" to s.integer(0), "width" to s.integer(1), "height" to s.integer(1),
            "tile_count" to s.integer(0), "occupancy" to s.number(0, 1)))),
        "tiles" to s.array(listedTile)))

    val render: JsonObject = s.obj(read + linkedMapOf("page" to s.integer(0), "width" to s.integer(1), "height" to s.integer(1),
        "rendered_width" to s.integer(1), "rendered_height" to s.integer(1), "mime_type" to s.constant("image/png"),
        "png_bytes" to s.integer(1), "sha256" to s.handle(), "layers" to s.array(s.handle())))

    /** Terminal job results by operation. */
    fun result(id: String): JsonObject = if (id == RENDER) render else { require(id in edits); edit }
    val jobs: Set<String> get() = edits + RENDER
}

internal fun WorkspaceAtlasTile.toJson(listed: Boolean = false) = buildJsonObject {
    if (listed) put("layer_id", layerId)
    put("page", page); put("x", x); put("y", y); put("width", width); put("height", height)
    put("scale_x", scaleX); put("scale_y", scaleY); put("density", density); put("locked", locked); put("pinned", pinned)
    put("shaped", shaped)
}

internal fun WorkspaceTextureView.layerJson(layerId: String): JsonObject {
    val layer = layer(layerId)
    return buildJsonObject {
        put("project_id", projectId); put("state", state); put("revision", revision)
        put("layer_id", layer.layerId); put("name", layer.name)
        putJsonObject("canvas_rect") { put("left", layer.canvasRect.left); put("top", layer.canvasRect.top)
            put("width", layer.canvasRect.width); put("height", layer.canvasRect.height) }
        putJsonObject("bounds") { put("left", layer.bounds.left); put("top", layer.bounds.top)
            put("width", layer.bounds.width); put("height", layer.bounds.height) }
        putJsonObject("raster") { put("width", layer.rasterWidth); put("height", layer.rasterHeight) }
        putJsonObject("native_density") { put("x", layer.nativeDensityX); put("y", layer.nativeDensityY) }
        putJsonObject("override") {
            put("density", layer.override.density?.let(::JsonPrimitive) ?: JsonNull); put("lock", layer.override.lock)
            put("pin", layer.override.pin?.let { buildJsonObject { put("page", it.page); put("x", it.x); put("y", it.y) } } ?: JsonNull)
        }
        put("deleted", layer.deleted)
        put("tile", layer.tile?.toJson() ?: JsonNull)
        put("atlas_fit", layer.fit)
    }
}

internal fun WorkspaceTextureView.atlasJson(page: Int?): JsonObject {
    val atlas = atlas()
    page?.let { require(it in atlas.pages.indices) { "Atlas page $it does not exist; the atlas has ${atlas.pages.size} page(s)" } }
    return buildJsonObject {
        put("project_id", projectId); put("state", state); put("revision", revision)
        putJsonObject("budget") { put("page_size", atlas.budget.pageSize); put("max_pages", atlas.budget.maxPages); put("padding", atlas.budget.padding) }
        put("fit", atlas.fit); put("notices", JsonArray(atlas.notices.map(::JsonPrimitive))); put("auto", atlas.auto)
        put("pages", JsonArray(atlas.pages.map { buildJsonObject {
            put("index", it.index); put("width", it.width); put("height", it.height); put("tile_count", it.tileCount); put("occupancy", it.occupancy)
        } }))
        put("tiles", JsonArray(atlas.tiles.filter { page == null || it.page == page }.map { it.toJson(listed = true) }))
    }
}

/** The page's PNG, scaled down to [maxSize] on its long edge when larger. */
internal fun WorkspaceTextureView.renderPage(page: Int, maxSize: Int, checkpoint: () -> Unit): Pair<JsonObject, ByteArray> {
    val canonical = pagePng(page)
    checkpoint()
    val source = ImageIO.read(canonical.inputStream()) ?: error("Atlas page $page could not be decoded")
    val scale = minOf(1.0, maxSize.toDouble() / maxOf(source.width, source.height))
    val png = if (scale >= 1.0) canonical else {
        val width = maxOf(1, Math.round(source.width * scale).toInt()); val height = maxOf(1, Math.round(source.height * scale).toInt())
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally { graphics.dispose() }
        checkpoint()
        ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
    }
    val rendered = if (png === canonical) source else ImageIO.read(png.inputStream())
    val digest = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return buildJsonObject {
        put("project_id", projectId); put("state", state); put("revision", revision); put("page", page)
        put("width", source.width); put("height", source.height)
        put("rendered_width", rendered.width); put("rendered_height", rendered.height)
        put("mime_type", "image/png"); put("png_bytes", png.size); put("sha256", digest)
        put("layers", JsonArray(pageLayers(page).map(::JsonPrimitive)))
    } to png
}

internal fun registerTextureOperations(registry: WorkspaceOperationRegistry, port: WorkspaceTexturePort,
                                       statePort: WorkspaceStatePort, jobs: WorkspaceJobs) {
    val s = WorkspaceResultSchema
    registry.register(WorkspaceOperationDefinition("layer_get_texture",
        "Read one layer's texture from one committed capture: canvas rectangle (canvas units, may be fractional) and integer bounds, raster pixels, native density (raster pixels per canvas unit), texture override (density, lock, pin), atlas tile (page, texture-pixel rectangle, texture pixels per raster pixel) and the atlas fit.",
        s.obj(mapOf("layer_id" to s.handle())), WorkspaceOperationKind.QUERY, resultSchema = WorkspaceTextureSchemas.layer)) { request, _ ->
        WorkspaceOperationOutput(port.captureTextures().layerJson(request.getValue("layer_id").jsonPrimitive.content))
    }
    registry.register(WorkspaceOperationDefinition("atlas_get",
        "Read the atlas from one committed capture: budget (page size, page count, padding), the common fit applied to unlocked tiles, notices when the budget forced a smaller fit or dropped locks/pins, pages with occupancy, and every tile (optionally one page's). A tile holds raster pixels x density x fit.",
        s.obj(mapOf("page" to s.integer(0, 63)), emptySet()), WorkspaceOperationKind.QUERY, resultSchema = WorkspaceTextureSchemas.atlas)) { request, _ ->
        WorkspaceOperationOutput(port.captureTextures().atlasJson(request["page"]?.jsonPrimitive?.int))
    }
    registry.register(WorkspaceOperationDefinition(WorkspaceTextureSchemas.RENDER,
        "Render one committed atlas page as PNG, scaled down to max_size (default 2048) on its long edge. Read-only job: the page, revision and tile list come from the version captured when the job started; job_get/job_wait return the image again.",
        s.obj(mapOf("page" to s.integer(0, 63), "max_size" to s.integer(64, 16384)), setOf("page")), WorkspaceOperationKind.QUERY,
        jobBacked = true, workspaceBound = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(WorkspaceTextureSchemas.RENDER)),
        jobResultSchema = WorkspaceTextureSchemas.render)) { request, _ ->
        val execution = requireNotNull(currentCoroutineContext()[WorkspaceExecution])
        val view = port.captureTextures()
        execution.check(view.projectId, view.state)
        val page = request.getValue("page").jsonPrimitive.int
        val maxSize = request["max_size"]?.jsonPrimitive?.int ?: 2048
        view.pagePng(page)
        val started = jobs.start(WorkspaceTextureSchemas.RENDER, view.projectId, view.state, WorkspaceTextureSchemas.render) {
            withContext(execution) {
                checkpoint()
                progress(0.1f, "Rendering atlas page $page")
                val context = currentCoroutineContext()
                val (metadata, png) = view.renderPage(page, maxSize) { context.ensureActive() }
                checkpoint()
                WorkspaceOperationOutput(metadata, listOf(png))
            }
        }
        WorkspaceOperationOutput(started.toJson())
    }
    val descriptions = mapOf(
        "layer_set_canvas_rect" to "Move or resize a source layer to a canvas rectangle in canvas units (fractional allowed); its raster stretches over it and its integer bounds become the enclosing box. The generator places the layer's mesh by the new rectangle. Rejects layers whose meshes carry authored edits, skeleton/swing/simulation bindings or materialized geometry (split, created or rebuilt meshes; use layer_set_bounds for file-imported layers) and imported CMO3 models. The same rectangle is a no-op.",
        "layer_replace_image" to "Replace a source layer's pixels with an image of any resolution, keeping its canvas rectangle: give an absolute path (PNG, WebP, TIFF or BMP; single operation only) or png_base64. fit=stretch (default) fills the rectangle; contain keeps the aspect ratio, centred on transparency. The rig keeps its generation input, so meshes, keyforms and bindings stay; rebuild_mesh=true instead regenerates the layer's mesh from the new pixels and is rejected for authored or materialized layers. At most 16 megapixels; identical pixels are a no-op.",
        "layer_set_pixel_density" to "Set the texture density of 1..128 layers: texture pixels per raster pixel (1/64..16; null resets to 1). lock=true keeps the density when the atlas budget forces unlocked tiles smaller; omitted lock keeps its value. Only the atlas and bound uvs change, never geometry.",
        "atlas_set_tile" to "Move a layer's atlas tile to texture pixel (x, y) on page, or pass pin=null to release it into free space. The spot is stored in the atlas arrangement; an automatically arranged atlas first keeps its current layout (auto becomes false), so no other tile moves. A tile whose spot no longer fits or overlaps is placed into free space with a notice in atlas_get.",
        "atlas_set_budget" to "Set the atlas budget: page_size (power of two 256..16384), max_pages (1..64) and padding (0..32 texture pixels); omitted values keep the current effective budget's. auto=true arranges the atlas automatically on every build again (rectangle MaxRects pack; the stored arrangement is dropped); auto=false keeps the current layout. When automatic textures do not fit, every unlocked tile is scaled by one common fit.",
        "atlas_pack" to "Arrange the atlas once and keep the result (auto becomes false): shape=mesh (default) places tiles by their meshes' footprint, so tiles nest wherever their meshes leave room and each writes only its own cells; shape=rect by tile rectangles. Largest first, at the largest fit that keeps the budget's pages. layer_ids moves only those tiles; the others keep their spots. Drops pins and texture overrides of layers that no longer exist. Later mesh edits that leave a footprint are reported in atlas_get notices.",
    )
    for (id in WorkspaceTextureSchemas.edits.sorted()) registry.register(WorkspaceOperationDefinition(id,
        descriptions.getValue(id) + " Returns a process-owned job handle; use job_wait/job_get for the committed state, changed layers, atlas fit and notices.",
        WorkspaceTextureSchemas.request(id), WorkspaceOperationKind.DOCUMENT, jobBacked = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(id)), jobResultSchema = WorkspaceTextureSchemas.edit)) { request, context ->
        val state = request.getValue("state").jsonPrimitive.content
        val edit = WorkspaceTextureEdits.parse(WorkspaceDocumentOperation(id, JsonObject(request - "state")))
        startWorkspaceOperationJob(statePort, jobs, id) {
            withContext(WorkspaceTextureJobExecution(id, requireNotNull(currentCoroutineContext()[WorkspaceJobCompletion]))) {
                WorkspaceOperationOutput(port.editTexture(state, edit, context.author).toJson())
            }
        }
    }
}
