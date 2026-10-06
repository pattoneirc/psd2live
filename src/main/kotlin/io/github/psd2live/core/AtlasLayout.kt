package io.github.psd2live.core

import io.github.psd2live.format.compile.RasterResample
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.ArrangedTile
import io.github.psd2live.project.AtlasArrangement
import io.github.psd2live.project.TextureFootprint
import io.github.psd2live.project.TextureOverride
import io.github.psd2live.project.TexturePin
import org.umamo.format.art.SourceLayer
import java.awt.image.BufferedImage
import java.lang.ref.SoftReference
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import javax.imageio.ImageIO

/**
 * Where layer rasters go on the atlas pages, and the pages themselves.
 *
 * A document with a stored arrangement ([AtlasArrangement]) keeps it: every listed tile stays at its spot
 * and fit, a tile arranged by its meshes writes only the cells its meshes use, and only tiles without a free
 * stored spot are placed into free space ([AtlasArrange]). [arrange] finds such an arrangement once, on request.
 *
 * Without one the layout is the canonical one - a deterministic multi-page shelf pack of every textured layer,
 * tallest first - so a document's atlas is a function of the document alone: a fresh build, a reopen, an
 * undo, a paint commit and an export all put the same tiles in the same spots, and exports stay byte for
 * byte what they were. What is incremental is the pages. Given the atlas a commit starts from (`previous`),
 * a page none of whose tiles changed - same pixels, same spot, none removed - is that atlas's page itself; a
 * changed page is composed again and remembers which rows differ from the page it replaces, so its preview
 * PNG re-encodes only those strips ([AtlasPagePng]). A paint that keeps its layer's size thus redraws one
 * tile in place; one that resizes it reflows the shelf, which moves the tiles after it and costs a page
 * composition plus the strips they cross, but never a layout that depends on the editing history.
 *
 * Tile sizes follow the atlas budget ([AtlasBudget]) and the layers' texture overrides ([TextureOverride]):
 * a tile holds `texture raster pixels x upscale x density x fit` texture pixels, where the density is the
 * layer's override (1 by default) and fit (at most 1) is one factor for every unlocked layer, the largest
 * multiple of 1/[FIT_STEPS] at which the shelf fits the budget's page count. Locked layers keep fit 1 and
 * pinned layers keep their page position. With every override at its default and the layout fitting at
 * fit 1, tiles are exactly `raster x upscale` and the layout is the one pages always had.
 *
 * Pages are also cached by recipe (page size and each tile's rectangle and pixel digest), so a rebuild that
 * lands on the same tiles - a reopen, a history checkout back to earlier pixels - gets the very same
 * [AtlasPage], whose lazily encoded PNGs keep their identity too: the preview's bundle fingerprint skips
 * re-hashing them and the native preview uploads only the pages that really changed.
 */
internal object AtlasLayout {
    /**
     * One textured layer to place. [baseWidth] x [baseHeight] is its tile at fit 1 - texture raster pixels times
     * the upscale and the layer's density; [canvasWidth] x [canvasHeight] its canvas-resolution extent times the
     * upscale, which is what a page grows to hold.
     */
    private class Item(
        val layer: ClassifiedLayer,
        val texture: SourceLayer,
        val baseWidth: Double,
        val baseHeight: Double,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val locked: Boolean,
        val pin: TexturePin?,
    ) {
        val id: String get() = layer.source.id.raw
        val baseW: Int = sizeOf(baseWidth)
        val baseH: Int = sizeOf(baseHeight)

        /** The tile at [fit]; a locked tile keeps fit 1 while [honour]ing locks. */
        fun width(fit: Double, honour: Boolean): Int = if ((honour && locked) || fit == 1.0) baseW else sizeOf(baseWidth * fit)
        fun height(fit: Double, honour: Boolean): Int = if ((honour && locked) || fit == 1.0) baseH else sizeOf(baseHeight * fit)

        private fun sizeOf(value: Double): Int = Math.round(value).coerceIn(1L, 1L shl 20).toInt()
    }

    /** Fit is searched in steps of 1/[FIT_STEPS], so equal inputs always give the same fit. */
    private const val FIT_STEPS = 4096

    /** No page-count limit, for callers that only name a page size. */
    const val UNLIMITED_PAGES: Int = Int.MAX_VALUE

    /** The canonical layout of [layers] under [config]'s atlas budget, texture overrides and upscale. */
    fun pack(
        layers: List<ClassifiedLayer>,
        config: PipelineConfig,
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
    ): PackedAtlas = pack(layers, config.effectiveAtlasBudget(), config.textureOverrides, config.textureUpscale, progress, previous,
        config.atlasArrangement)

    /**
     * The canonical layout of [layers] on pages of [requestedSize] with no page-count limit and no overrides.
     * [previous] - the atlas a commit starts from - lends every page whose recipe did not change, and the
     * strips of the rows that did not, to the new pages.
     */
    fun pack(
        layers: List<ClassifiedLayer>,
        requestedSize: Int,
        padding: Int,
        upscale: TextureUpscaleConfig = TextureUpscaleConfig(),
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
    ): PackedAtlas = pack(layers, unlimited(requestedSize, padding), emptyMap(), upscale, progress, previous)

    fun pack(
        layers: List<ClassifiedLayer>,
        budget: AtlasBudget,
        overrides: Map<String, TextureOverride>,
        upscale: TextureUpscaleConfig = TextureUpscaleConfig(),
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
        arrangement: AtlasArrangement? = null,
    ): PackedAtlas = packWithTextures(layers, budget, overrides, upscale, progress, previous, arrangement) { l, c -> TextureUpscale.prepare(l, c, progress) }

    internal fun packWithTextures(
        layers: List<ClassifiedLayer>, requestedSize: Int, padding: Int,
        upscale: TextureUpscaleConfig,
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
        prepareTextures: (List<ClassifiedLayer>, TextureUpscaleConfig) -> Map<String, Path>,
    ): PackedAtlas = packWithTextures(layers, unlimited(requestedSize, padding), emptyMap(), upscale, progress, previous, prepareTextures = prepareTextures)

    private fun unlimited(requestedSize: Int, padding: Int) = AtlasBudget(requestedSize.coerceAtLeast(1), UNLIMITED_PAGES, padding.coerceAtLeast(0))

    internal fun packWithTextures(
        layers: List<ClassifiedLayer>,
        budget: AtlasBudget,
        overrides: Map<String, TextureOverride>,
        upscale: TextureUpscaleConfig,
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
        arrangement: AtlasArrangement? = null,
        prepareTextures: (List<ClassifiedLayer>, TextureUpscaleConfig) -> Map<String, Path>,
    ): PackedAtlas {
        val safePadding = budget.padding.coerceIn(0, 32)
        val items = items(layers, upscale.scale, overrides)
        val pageSize = pageSize(items, budget.pageSize, safePadding)
        val solved = if (arrangement != null) kept(items, arrangement, pageSize, safePadding, budget.maxPages)
            else solve(items, pageSize, safePadding, budget.maxPages)
        val placements = solved.placements
        val masks = solved.masks
        val pageCount = solved.pageCount
        // Fail before inference or large allocations; encoded PNGs and render copies cost extra memory.
        require(upscale.scale == 1 || pageCount.toLong() * pageSize * pageSize * 4 <= 512L * 1024 * 1024) {
            "Upscaled atlas exceeds 512 MiB of raw pixels. Reduce texture scale or atlas size."
        }
        progress.update(tr("progress.atlas"), 0.96)
        if (upscale.scale != 1) {
            return PackedAtlas(upscaledPages(items, placements, masks, pageSize, pageCount, budget.padding, prepareTextures(items.map { it.layer }, upscale)),
                placements, solved.fit, solved.notices, solved.footprints, arrangement != null)
        }
        val byPage = items.groupBy { placements.getValue(it.id).page }
        val pages = (0 until pageCount).map { index ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val tiles = byPage[index].orEmpty().map { Tile(it.texture, placements.getValue(it.id), masks[it.id]) }
            page(pageSize, tiles, previous?.pages?.getOrNull(index))
        }
        return PackedAtlas(pages, placements, solved.fit, solved.notices, solved.footprints, arrangement != null)
    }

    /** One tile to draw on a page: its texture, its rectangle and, with a footprint, the only cells it may write. */
    private class Tile(val texture: SourceLayer, val at: AtlasPlacement, val mask: AtlasArrange.Shape?)

    private fun request(item: Item, fit: Double, stored: ArrangedTile?, footprint: TextureFootprint?): AtlasArrange.Request =
        AtlasArrange.Request(item.id, item.layer.source.name, item.width(fit, true), item.height(fit, true),
            item.texture.raster.width, item.texture.raster.height, stored, footprint)

    private fun placement(item: Item, request: AtlasArrange.Request, spot: AtlasArrange.Spot) = AtlasPlacement(spot.page, spot.x, spot.y,
        request.width, request.height, request.width.toFloat() / item.texture.raster.width, request.height.toFloat() / item.texture.raster.height)

    /**
     * A stored arrangement's layout: its fit for every unlocked tile, its spots for the tiles that still hold
     * there, free space for the rest ([AtlasArrange.keep]). A footprint holds only at the spot it was found for.
     */
    private fun kept(items: List<Item>, arrangement: AtlasArrangement, pageSize: Int, padding: Int, maxPages: Int): Solved {
        val fit = arrangement.fit
        val requests = items.map { item -> arrangement.tiles[item.id].let { request(item, fit, it, it?.footprint) } }
        val kept = AtlasArrange.keep(requests, pageSize, padding, maxPages)
        val placements = LinkedHashMap<String, AtlasPlacement>()
        val masks = HashMap<String, AtlasArrange.Shape>()
        val footprints = HashMap<String, TextureFootprint>()
        for ((item, request) in items.zip(requests)) {
            val spot = kept.spots.getValue(item.id)
            placements[item.id] = placement(item, request, spot)
            val stored = request.stored
            if (request.footprint != null && stored != null && stored.page == spot.page && stored.x == spot.x && stored.y == spot.y) {
                masks[item.id] = kept.shapes.getValue(item.id); footprints[item.id] = request.footprint
            }
        }
        val notices = ArrayList<String>()
        if (kept.moved.isNotEmpty()) notices += "Textures without a free stored spot were placed into free space (" + kept.moved.joinToString() +
            "); arrange the atlas to lay them out again."
        if (kept.pageCount > maxPages) notices += "Textures need ${kept.pageCount} atlas pages, more than the budget of $maxPages."
        if (arrangement.fitStep < AtlasArrangement.FIT_STEPS)
            notices += "Textures are scaled to ${String.format(Locale.ROOT, "%.1f", fit * 100)}% to fit the atlas budget of $maxPages page(s) of ${pageSize}px."
        return Solved(placements, kept.pageCount, fit.toFloat(), notices, masks, footprints)
    }

    /**
     * A compact arrangement of [layers] under [config]'s budget, to store once. Tiles with a mesh footprint in
     * [footprints] are placed by their meshes, the others by their rectangles, largest first, at the largest fit
     * (a multiple of 64/[FIT_STEPS]) at which all fit within the budget's pages. With [only], every other tile
     * keeps its spot and footprint in [current] and the current fit holds. Null when nothing fits.
     */
    fun arrange(layers: List<ClassifiedLayer>, config: PipelineConfig, footprints: Map<String, TextureFootprint>,
                current: PackedAtlas?, only: Set<String>? = null): AtlasArrangement? {
        val budget = config.effectiveAtlasBudget()
        val padding = budget.padding.coerceIn(0, 32)
        val items = items(layers, config.textureUpscale.scale, config.textureOverrides)
        val pageSize = pageSize(items, budget.pageSize, padding)
        val fixedItems = if (only == null || current == null) emptyList() else items.filter { it.id !in only && it.id in current.placementByLayerId }
        val moving = items.filter { it !in fixedItems }
        fun attempt(step: Int): AtlasArrangement? {
            val fit = step.toDouble() / AtlasArrangement.FIT_STEPS
            val fixed = fixedItems.map { item ->
                val at = current!!.placementByLayerId.getValue(item.id)
                Triple(item, AtlasArrange.Spot(at.page, at.x, at.y),
                    AtlasArrange.shape(at.x, at.y, at.width, at.height, item.texture.raster.width, item.texture.raster.height, current.footprints[item.id]))
            }
            val requests = moving.map { request(it, fit, null, footprints[it.id]) }
            val spots = AtlasArrange.arrange(requests, fixed.map { it.second to it.third }, pageSize, padding, budget.maxPages) ?: return null
            val tiles = HashMap<String, ArrangedTile>()
            for ((item, spot, _) in fixed) tiles[item.id] = ArrangedTile(spot.page, spot.x, spot.y, current!!.footprints[item.id])
            for (request in requests) spots.getValue(request.id).let { tiles[request.id] = ArrangedTile(it.page, it.x, it.y, request.footprint) }
            return AtlasArrangement(step, tiles)
        }
        if (fixedItems.isNotEmpty()) {
            val step = Math.round(current!!.fit * AtlasArrangement.FIT_STEPS).coerceIn(1, AtlasArrangement.FIT_STEPS)
            return attempt(step)
        }
        attempt(AtlasArrangement.FIT_STEPS)?.let { return it }
        var low = 1; var high = AtlasArrangement.FIT_STEPS / 64 - 1
        var best: AtlasArrangement? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val tried = attempt(middle * 64)
            if (tried != null) { best = tried; low = middle + 1 } else high = middle - 1
        }
        return best
    }

    /** [atlas]'s layout as a stored arrangement, to keep it exactly as it is. */
    fun frozen(atlas: PackedAtlas): AtlasArrangement {
        val step = Math.round(atlas.fit * AtlasArrangement.FIT_STEPS).coerceIn(1, AtlasArrangement.FIT_STEPS)
        return AtlasArrangement(step, atlas.placementByLayerId.mapValues { (id, at) -> ArrangedTile(at.page, at.x, at.y, atlas.footprints[id]) })
    }

    private fun items(layers: List<ClassifiedLayer>, scale: Int, overrides: Map<String, TextureOverride>): List<Item> =
        layers.filter { it.source.raster.width > 0 && it.source.raster.height > 0 && it.opaquePixels > 0 }
            .map { layer ->
                val texture = layer.source.textureLayer
                val override = overrides[layer.source.id.raw]
                val density = override?.density?.toDouble() ?: 1.0
                // A dense texture's canvas extent is its bounds; any other raster is its own canvas extent, as always.
                val canvas = if (CanvasDensity.dense(texture)) texture.bounds.width to texture.bounds.height
                    else layer.source.raster.width to layer.source.raster.height
                Item(layer, texture,
                    Math.multiplyExact(texture.raster.width, scale) * density, Math.multiplyExact(texture.raster.height, scale) * density,
                    Math.multiplyExact(canvas.first, scale), Math.multiplyExact(canvas.second, scale),
                    override?.lock == true, override?.pin)
            }
            .sortedWith(compareByDescending<Item> { it.baseH }.thenByDescending { it.baseW }.thenBy { it.id })

    /**
     * The page side: the budget's, grown to the next power of two that holds the largest layer at canvas
     * resolution (as pages always have), at most 16384. Denser tiles that do not fit are scaled by the fit.
     */
    private fun pageSize(items: List<Item>, requestedSize: Int, safePadding: Int): Int {
        val safeSize = requestedSize.coerceIn(256, 16384)
        val largest = items.maxOfOrNull { maxOf(it.canvasWidth, it.canvasHeight) + safePadding * 2 } ?: safeSize
        return maxOf(safeSize, nextPowerOfTwo(largest)).coerceAtMost(16384)
    }

    private class Solved(val placements: Map<String, AtlasPlacement>, val pageCount: Int, val fit: Float, val notices: List<String>,
                         val masks: Map<String, AtlasArrange.Shape> = emptyMap(), val footprints: Map<String, TextureFootprint> = emptyMap())

    private class Layout(val placements: Map<String, AtlasPlacement>, val pageCount: Int)

    /**
     * Places [items] within [maxPages] pages: at fit 1 when they fit, else at the largest fit (a multiple of
     * 1/[FIT_STEPS]) that does, applied to every unlocked tile alike. When locked and pinned tiles alone do
     * not fit, they lose their lock and pin and a notice says so; when nothing fits, pages are added.
     */
    private fun solve(items: List<Item>, pageSize: Int, padding: Int, maxPages: Int): Solved {
        layout(items, 1.0, pageSize, padding, maxPages, honour = true)?.let { return Solved(it.placements, it.pageCount, 1f, emptyList()) }
        val notices = ArrayList<String>()
        var honour = true
        if (items.any { it.locked || it.pin != null } && layout(items, 1.0 / FIT_STEPS, pageSize, padding, maxPages, honour = true) == null) {
            notices += "Locked or pinned textures (" + items.filter { it.locked || it.pin != null }.joinToString { it.layer.source.name } +
                ") do not fit the atlas budget of $maxPages page(s) of ${pageSize}px; they are scaled and placed with the other textures."
            honour = false
        }
        // The largest step that fits; shelf packing is not strictly monotonic in the fit, but the search is deterministic.
        var low = 1; var high = FIT_STEPS - 1
        var best: Layout? = null
        var bestStep = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            val tried = layout(items, middle.toDouble() / FIT_STEPS, pageSize, padding, maxPages, honour)
            if (tried != null) { best = tried; bestStep = middle; low = middle + 1 } else high = middle - 1
        }
        if (best != null) {
            val fit = bestStep.toDouble() / FIT_STEPS
            notices += "Textures are scaled to ${String.format(Locale.ROOT, "%.1f", fit * 100)}% to fit the atlas budget of $maxPages page(s) of ${pageSize}px."
            return Solved(best.placements, best.pageCount, fit.toFloat(), notices)
        }
        val fallback = requireNotNull(layout(items, 1.0, pageSize, padding, UNLIMITED_PAGES, honour = false)) { tr("error.layerTooLarge") }
        notices += "Textures need ${fallback.pageCount} atlas pages, more than the budget of $maxPages."
        return Solved(fallback.placements, fallback.pageCount, 1f, notices)
    }

    /**
     * The shelf layout of [items] at [fit], or null when it needs more than [maxPages] pages, a tile is larger
     * than a page, or (while [honour]ing locks and pins) a pin lies outside the budget or on another pinned
     * tile. Pinned tiles take their spot first; the shelf skips past them.
     */
    private fun layout(items: List<Item>, fit: Double, pageSize: Int, padding: Int, maxPages: Int, honour: Boolean): Layout? {
        val pinned = HashMap<String, AtlasPlacement>()
        val obstacles = HashMap<Int, MutableList<AtlasPlacement>>()
        var lastPinnedPage = -1
        fun sized(item: Item, page: Int, x: Int, y: Int): AtlasPlacement {
            val width = item.width(fit, honour); val height = item.height(fit, honour)
            return AtlasPlacement(page, x, y, width, height, width.toFloat() / item.texture.raster.width, height.toFloat() / item.texture.raster.height)
        }
        fun clash(a: AtlasPlacement, x: Int, y: Int, width: Int, height: Int): Boolean =
            x < a.x + a.width + padding * 2 && a.x < x + width + padding * 2 && y < a.y + a.height + padding * 2 && a.y < y + height + padding * 2
        if (honour) for (item in items) {
            val pin = item.pin ?: continue
            val placed = sized(item, pin.page, pin.x, pin.y)
            if (pin.page >= maxPages || pin.x + placed.width + padding > pageSize || pin.y + placed.height + padding > pageSize) return null
            if (obstacles[pin.page].orEmpty().any { clash(it, pin.x, pin.y, placed.width, placed.height) }) return null
            obstacles.getOrPut(pin.page) { ArrayList() } += placed
            pinned[item.id] = placed
            lastPinnedPage = maxOf(lastPinnedPage, pin.page)
        }
        val placements = LinkedHashMap<String, AtlasPlacement>()
        var x = padding; var y = padding; var rowHeight = 0; var pageIndex = 0
        for (item in items) {
            pinned[item.id]?.let { placements[item.id] = it; continue }
            val tile = sized(item, 0, 0, 0)
            val width = tile.width; val height = tile.height
            if (width + padding * 2 > pageSize || height + padding * 2 > pageSize) return null
            while (true) {
                // A row that holds nothing yet (only after skipping a pin) moves down a pixel at a time.
                if (x + width + padding > pageSize) { x = padding; y += if (rowHeight == 0) 1 else rowHeight + padding; rowHeight = 0 }
                if (y + height + padding > pageSize) {
                    pageIndex++; x = padding; y = padding; rowHeight = 0
                    if (pageIndex >= maxPages) return null
                }
                val hit = obstacles[pageIndex]?.firstOrNull { clash(it, x, y, width, height) } ?: break
                x = hit.x + hit.width + padding * 2
            }
            placements[item.id] = tile.copy(page = pageIndex, x = x, y = y)
            x += width + padding * 2
            rowHeight = maxOf(rowHeight, height)
        }
        return Layout(placements, maxOf(pageIndex, lastPinnedPage) + 1)
    }

    /** One tile of a 1:1 page: its rectangle, the digest of the raster drawn there and the cells it may write ("" for all). */
    internal data class TileKey(val x: Int, val y: Int, val width: Int, val height: Int, val digest: String, val mask: String = "")

    /** All a 1:1 page's pixels depend on: its side and its tiles, top to bottom. */
    internal data class Recipe(val size: Int, val tiles: List<TileKey>)

    private val pageCache = object : LinkedHashMap<Recipe, SoftReference<AtlasPage>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Recipe, SoftReference<AtlasPage>>?) = size > PAGE_CACHE_CAPACITY
    }
    private const val PAGE_CACHE_CAPACITY = 8
    private val digests = Collections.synchronizedMap(WeakHashMap<ByteArray, String>())

    /** Layer rasters keep their identity across rebuilds, so each is hashed once. */
    private fun digest(rgba: ByteArray): String = digests.getOrPut(rgba) {
        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rgba))
    }

    /**
     * One page of [tiles] (texture layers and where they go): [base] itself when it has the same recipe, else a
     * cached page of that recipe, else a newly composed one. A composed page differs from [base] - the page it
     * replaces - only in the rows of the tiles the two recipes do not share, so its preview encoding reuses
     * [base]'s other strips. A tile whose size is not its raster's is resampled ([RasterResample]); its digest
     * and size together still determine its pixels.
     */
    private fun page(size: Int, tiles: List<Tile>, base: AtlasPage?): AtlasPage {
        val recipe = Recipe(size, tiles.map { tile ->
            TileKey(tile.at.x, tile.at.y, tile.at.width, tile.at.height, digest(tile.texture.raster.rgba), tile.mask?.key ?: "")
        }.sortedWith(compareBy<TileKey> { it.y }.thenBy { it.x }))
        if (base?.recipe == recipe) return base
        synchronized(pageCache) { pageCache[recipe]?.get()?.let { return it } }
        val dirtyRows = base?.recipe?.takeIf { it.size == size }?.let { old ->
            val kept = old.tiles.toHashSet().apply { retainAll(recipe.tiles.toSet()) }
            java.util.BitSet().apply { for (tile in old.tiles + recipe.tiles) if (tile !in kept) set(tile.y, tile.y + tile.height) }
        }
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (tile in tiles) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val raster = tile.texture.raster; val at = tile.at
            val rgba = RasterResample.resize(raster.rgba, raster.width, raster.height, at.width, at.height)
            // Direct pixel copy retains RGB even when alpha is zero (Graphics may discard it).
            val pixels = argb(rgba, at.width * at.height)
            val mask = tile.mask
            if (mask == null) image.setRGB(at.x, at.y, at.width, at.height, pixels, 0, at.width)
            else writeMasked(image, at, mask) { x, y, length, offset -> image.setRGB(x, y, length, 1, pixels, offset, at.width) }
        }
        val page = AtlasPage.composed(image, recipe, base, dirtyRows)
        synchronized(pageCache) { pageCache[recipe] = SoftReference(page) }
        return page
    }

    /**
     * The runs of [at] that [mask] owns, row by row, as (page x, page y, length, offset into the tile's pixels): a
     * tile arranged by its meshes writes only its own cells, so a neighbour reaching into its rectangle keeps its pixels.
     */
    private inline fun writeMasked(image: BufferedImage, at: AtlasPlacement, mask: AtlasArrange.Shape, write: (Int, Int, Int, Int) -> Unit) {
        for (y in at.y until minOf(at.y + at.height, image.height)) {
            val r = y / AtlasArrange.CELL - mask.row
            if (r !in 0 until mask.height) continue
            val run = mask.runs[r]
            for (k in run.indices step 2) {
                val from = maxOf(at.x, (mask.column + run[k]) * AtlasArrange.CELL)
                val to = minOf(at.x + at.width, (mask.column + run[k + 1]) * AtlasArrange.CELL, image.width)
                if (to > from) write(from, y, to - from, (y - at.y) * at.width + (from - at.x))
            }
        }
    }

    private fun argb(rgba: ByteArray, count: Int): IntArray = IntArray(count) { index ->
        val offset = index * 4
        ((rgba[offset + 3].toInt() and 0xff) shl 24) or ((rgba[offset].toInt() and 0xff) shl 16) or
            ((rgba[offset + 1].toInt() and 0xff) shl 8) or (rgba[offset + 2].toInt() and 0xff)
    }

    /**
     * Upscaled pages read each tile from the inference output - resampled when the density or fit make the tile
     * another size - and extrude its edge into the padding.
     */
    private fun upscaledPages(items: List<Item>, placements: Map<String, AtlasPlacement>, masks: Map<String, AtlasArrange.Shape>, pageSize: Int, pageCount: Int,
                              padding: Int, textures: Map<String, Path>): List<AtlasPage> = (0 until pageCount).map { index ->
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val page = BufferedImage(pageSize, pageSize, BufferedImage.TYPE_INT_ARGB)
        for (item in items) {
            val placement = placements.getValue(item.id)
            if (placement.page != index) continue
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val upscaled = requireNotNull(ImageIO.read(textures.getValue(item.id).toFile())) { "Invalid upscaled PNG" }
            val width = placement.width; val height = placement.height
            val image = if (upscaled.width == width && upscaled.height == height) upscaled else {
                val pixels = upscaled.getRGB(0, 0, upscaled.width, upscaled.height, null, 0, upscaled.width)
                val rgba = ByteArray(pixels.size * 4)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    rgba[i * 4] = (p ushr 16).toByte(); rgba[i * 4 + 1] = (p ushr 8).toByte(); rgba[i * 4 + 2] = p.toByte(); rgba[i * 4 + 3] = (p ushr 24).toByte()
                }
                PreviewRenderer.rasterImage(width, height, RasterResample.resize(rgba, upscaled.width, upscaled.height, width, height))
            }
            require(image.colorModel.hasAlpha()) { "Upscaled RGBA texture size mismatch" }
            val pixels = image.getRGB(0, 0, width, height, null, 0, width)
            val mask = masks[item.id]
            if (mask != null) {
                // A mesh-arranged tile writes only its own cells; its edge is not extruded over a neighbour.
                writeMasked(page, placement, mask) { x, y, length, offset -> page.setRGB(x, y, length, 1, pixels, offset, width) }
                continue
            }
            page.setRGB(placement.x, placement.y, width, height, pixels, 0, width)
            if (padding > 0) {
                for (dy in -padding until height + padding) for (dx in -padding until width + padding) {
                    if (dx in 0 until width && dy in 0 until height) continue
                    val tx = placement.x + dx; val ty = placement.y + dy
                    if (tx in 0 until pageSize && ty in 0 until pageSize) {
                        page.setRGB(tx, ty, image.getRGB(dx.coerceIn(0, width - 1), dy.coerceIn(0, height - 1)) and 0x00ffffff)
                    }
                }
            }
        }
        AtlasPage(page)
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value && result < 16384) result = result shl 1
        return result
    }
}
