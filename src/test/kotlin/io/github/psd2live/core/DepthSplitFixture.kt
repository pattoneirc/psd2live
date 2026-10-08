package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.sourceLayer
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.runtime.model.Drawable

/** A face, a neck and a collar, the collar split in depth around the neck. */
internal object DepthSplitFixture {
    private fun layer(id: String, name: String, order: Int, bounds: LayerBounds) = sourceLayer(id, order, bounds,
        LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) -1 else 90 }), name = name)

    fun original(pipeline: PSD2LivePipeline): RigPreviewModel = pipeline.buildPreview(
        WorkspaceSourceArt(128, 160, listOf(
            layer("face", "face", 1, LayerBounds(28, 10, 72, 70)),
            layer("neck", "neck", 2, LayerBounds(50, 70, 28, 30)),
            layer("collar", "neckwear", 3, LayerBounds(30, 86, 68, 30)),
        ), emptyList()), PipelineConfig(atlasSize = 256, meshSpacing = 12))

    fun mesh(preview: RigPreviewModel, layerId: String): Drawable = preview.rig.puppet.drawables.single {
        preview.rig.layerIdByDrawableId[it.id.raw] == layerId
    }

    /** The collar copied in front of the neck. */
    fun split(pipeline: PSD2LivePipeline, original: RigPreviewModel): DepthSplit.Result =
        DepthSplit.build(pipeline, original, original.config, mesh(original, "collar").id.raw, mesh(original, "neck").id.raw)
}
