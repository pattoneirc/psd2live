package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import java.awt.image.BufferedImage
import java.lang.ref.SoftReference
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import javax.imageio.ImageIO

/**
 * Where layer rasters go on the atlas pages, and the pages themselves.
 *
 * The layout is always the canonical one - a deterministic multi-page shelf pack of every textured layer,
 * tallest first - so a document's atlas is a function of the document alone: a fresh build, a reopen, an
 * undo, a paint commit and an export all put the same tiles in the same spots, and exports stay byte for
 * byte what they were. What is incremental is the pages. Given the atlas a commit starts from (`previous`),
 * a page none of whose tiles changed - same pixels, same spot, none removed - is that atlas's page itself; a
 * changed page is composed again and remembers which rows differ from the page it replaces, so its preview
 * PNG re-encodes only those strips ([AtlasPagePng]). A paint that keeps its layer's size thus redraws one
 * tile in place; one that resizes it reflows the shelf, which moves the tiles after it and costs a page
 * composition plus the strips they cross, but never a layout that depends on the editing history.
 *
 * Pages are also cached by recipe (page size and each tile's rectangle and pixel digest), so a rebuild that
 * lands on the same tiles - a reopen, a history checkout back to earlier pixels - gets the very same
 * [AtlasPage], whose lazily encoded PNGs keep their identity too: the preview's bundle fingerprint skips
 * re-hashing them and the native preview uploads only the pages that really changed.
 */
internal object AtlasLayout {
    private data class Item(val layer: ClassifiedLayer, val width: Int, val height: Int) {
        val id: String get() = layer.source.id.raw
    }

    /**
     * The canonical layout of [layers] and its pages. [previous] - the atlas a commit starts from - lends every
     * page whose recipe did not change, and the strips of the rows that did not, to the new pages.
     */
    fun pack(
        layers: List<ClassifiedLayer>,
        requestedSize: Int,
        padding: Int,
        upscale: TextureUpscaleConfig = TextureUpscaleConfig(),
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
    ): PackedAtlas = packWithTextures(layers, requestedSize, padding, upscale, progress, previous) { l, c -> TextureUpscale.prepare(l, c, progress) }

    internal fun packWithTextures(
        layers: List<ClassifiedLayer>, requestedSize: Int, padding: Int,
        upscale: TextureUpscaleConfig,
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
        prepareTextures: (List<ClassifiedLayer>, TextureUpscaleConfig) -> Map<String, Path>,
    ): PackedAtlas {
        val safePadding = padding.coerceIn(0, 32)
        val items = items(layers, upscale.scale)
        val pageSize = pageSize(items, requestedSize, safePadding)
        val placements = linkedMapOf<String, AtlasPlacement>()
        var x = safePadding; var y = safePadding; var rowHeight = 0; var pageIndex = 0
        for (item in items) {
            if (x + item.width + safePadding > pageSize) { x = safePadding; y += rowHeight + safePadding; rowHeight = 0 }
            if (y + item.height + safePadding > pageSize) { pageIndex++; x = safePadding; y = safePadding; rowHeight = 0 }
            placements[item.id] = AtlasPlacement(pageIndex, x, y, item.width, item.height, upscale.scale.toFloat(), upscale.scale.toFloat())
            x += item.width + safePadding * 2
            rowHeight = maxOf(rowHeight, item.height)
        }
        // Fail before inference or large allocations; encoded PNGs and render copies cost extra memory.
        require(upscale.scale == 1 || (pageIndex + 1L) * pageSize * pageSize * 4 <= 512L * 1024 * 1024) {
            "Upscaled atlas exceeds 512 MiB of raw pixels. Reduce texture scale or atlas size."
        }
        progress.update(tr("progress.atlas"), 0.96)
        if (upscale.scale != 1) {
            return PackedAtlas(upscaledPages(items, placements, pageSize, pageIndex + 1, padding, prepareTextures(items.map { it.layer }, upscale)), placements)
        }
        val byPage = items.groupBy { placements.getValue(it.id).page }
        val pages = (0..pageIndex).map { index ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val tiles = byPage[index].orEmpty().map { it.layer to placements.getValue(it.id) }
            page(pageSize, tiles, previous?.pages?.getOrNull(index))
        }
        return PackedAtlas(pages, placements)
    }

    private fun items(layers: List<ClassifiedLayer>, scale: Int): List<Item> =
        layers.filter { it.source.raster.width > 0 && it.source.raster.height > 0 && it.opaquePixels > 0 }
            .map { Item(it, Math.multiplyExact(it.source.raster.width, scale), Math.multiplyExact(it.source.raster.height, scale)) }
            .sortedWith(compareByDescending<Item> { it.height }.thenByDescending { it.width }.thenBy { it.id })

    private fun pageSize(items: List<Item>, requestedSize: Int, safePadding: Int): Int {
        val safeSize = requestedSize.coerceIn(256, 16384)
        val largest = items.maxOfOrNull { maxOf(it.width, it.height) + safePadding * 2 } ?: safeSize
        val pageSize = maxOf(safeSize, nextPowerOfTwo(largest)).coerceAtMost(16384)
        require(largest <= pageSize) { tr("error.layerTooLarge") }
        return pageSize
    }

    /** One tile of a 1:1 page: its rectangle and the digest of the raster drawn there. */
    internal data class TileKey(val x: Int, val y: Int, val width: Int, val height: Int, val digest: String)

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
     * One 1:1 page of [tiles]: [base] itself when it has the same recipe, else a cached page of that recipe,
     * else a newly composed one. A composed page differs from [base] - the page it replaces - only in the
     * rows of the tiles the two recipes do not share, so its preview encoding reuses [base]'s other strips.
     */
    private fun page(size: Int, tiles: List<Pair<ClassifiedLayer, AtlasPlacement>>, base: AtlasPage?): AtlasPage {
        val recipe = Recipe(size, tiles.map { (layer, at) -> TileKey(at.x, at.y, at.width, at.height, digest(layer.source.raster.rgba)) }.sortedWith(
            compareBy<TileKey> { it.y }.thenBy { it.x }))
        if (base?.recipe == recipe) return base
        synchronized(pageCache) { pageCache[recipe]?.get()?.let { return it } }
        val dirtyRows = base?.recipe?.takeIf { it.size == size }?.let { old ->
            val kept = old.tiles.toHashSet().apply { retainAll(recipe.tiles.toSet()) }
            java.util.BitSet().apply { for (tile in old.tiles + recipe.tiles) if (tile !in kept) set(tile.y, tile.y + tile.height) }
        }
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for ((layer, at) in tiles) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            // Direct pixel copy retains RGB even when alpha is zero (Graphics may discard it).
            image.setRGB(at.x, at.y, at.width, at.height, argb(layer.source.raster.rgba, at.width * at.height), 0, at.width)
        }
        val page = AtlasPage.composed(image, recipe, base, dirtyRows)
        synchronized(pageCache) { pageCache[recipe] = SoftReference(page) }
        return page
    }

    private fun argb(rgba: ByteArray, count: Int): IntArray = IntArray(count) { index ->
        val offset = index * 4
        ((rgba[offset + 3].toInt() and 0xff) shl 24) or ((rgba[offset].toInt() and 0xff) shl 16) or
            ((rgba[offset + 1].toInt() and 0xff) shl 8) or (rgba[offset + 2].toInt() and 0xff)
    }

    /** Upscaled pages read each tile from the inference output and extrude its edge into the padding. */
    private fun upscaledPages(items: List<Item>, placements: Map<String, AtlasPlacement>, pageSize: Int, pageCount: Int,
                              padding: Int, textures: Map<String, Path>): List<AtlasPage> = (0 until pageCount).map { index ->
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val page = BufferedImage(pageSize, pageSize, BufferedImage.TYPE_INT_ARGB)
        for (item in items) {
            val placement = placements.getValue(item.id)
            if (placement.page != index) continue
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val image = requireNotNull(ImageIO.read(textures.getValue(item.id).toFile())) { "Invalid upscaled PNG" }
            require(image.width == item.width && image.height == item.height && image.colorModel.hasAlpha()) { "Upscaled RGBA texture size mismatch" }
            val pixels = image.getRGB(0, 0, item.width, item.height, null, 0, item.width)
            page.setRGB(placement.x, placement.y, item.width, item.height, pixels, 0, item.width)
            if (padding > 0) {
                for (dy in -padding until item.height + padding) for (dx in -padding until item.width + padding) {
                    if (dx in 0 until item.width && dy in 0 until item.height) continue
                    val tx = placement.x + dx; val ty = placement.y + dy
                    if (tx in 0 until pageSize && ty in 0 until pageSize) {
                        page.setRGB(tx, ty, image.getRGB(dx.coerceIn(0, item.width - 1), dy.coerceIn(0, item.height - 1)) and 0x00ffffff)
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
