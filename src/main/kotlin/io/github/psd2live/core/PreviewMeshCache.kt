package io.github.psd2live.core

/** Per-pipeline, bounded cache of raster-local geometry; never caches parent coordinates. */
class PreviewMeshCache(private val capacity: Int = 128) {
    init { require(capacity > 0) }

    /** The rig builder's stage outputs for this pipeline ([RigStageCache]). */
    internal val stages = RigStageCache()

    private data class Key(
        val width: Int, val height: Int, val digest: String,
        val threshold: Int, val settings: MeshSettings, val unitScale: Float,
    )

    private val entries = LinkedHashMap<Key, AdaptiveMeshGenerator.Result?>(16, 0.75f, true)

    @Synchronized
    internal fun generate(
        width: Int, height: Int, rgba: ByteArray, alphaThreshold: Int, settings: MeshSettings,
        unitScale: Float = 1f,
    ): AdaptiveMeshGenerator.Result? {
        // Content addressed; rasters are never edited in place (every producer fills a new array), so each
        // array is hashed once ([RasterDigest]).
        val digest = RigBuildProfile.stage("mesh cache: raster digest") {
            if (RigStageCache.enabled) RasterDigest.of(rgba) else RasterDigest.compute(rgba)
        }
        val key = Key(width, height, digest, alphaThreshold, settings, unitScale)
        if (entries.containsKey(key)) return entries[key]?.detached()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val result = RigBuildProfile.stage("mesh cache: adaptive mesh (miss)") { AdaptiveMeshGenerator.generate(width, height, rgba, alphaThreshold, settings, unitScale) }
        entries[key] = result
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return result?.detached()
    }

    private fun AdaptiveMeshGenerator.Result.detached() = copy(
        positions = positions.copyOf(), indices = indices.copyOf(),
        boundaryLoops = boundaryLoops.map { it.copyOf() },
        middleLoops = middleLoops.map { it.copyOf() },
        innerLoops = innerLoops.map { it.copyOf() },
        spinePaths = spinePaths.map { it.copyOf() },
    )
}
