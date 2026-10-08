package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.islandLayer
import io.github.psd2live.targets.cubism.PuppetIr

/**
 * A synthetic 420² figure for skeleton tests: every part an opaque box on a full-canvas layer, classified by
 * override so that the skeleton finds its limbs, built without physics or a moc3 to keep a build quick.
 */
internal object SkeletonCharacterFixture {
	const val SIZE = 420

	fun layer(id: String, order: Int, box: IntArray): WorkspaceSourceLayer = islandLayer(id, order, SIZE, SIZE, box)

	fun source(vararg layers: WorkspaceSourceLayer) = WorkspaceSourceArt(SIZE, SIZE, layers.toList(), emptyList())

	fun config(overrides: Map<String, LayerClassificationOverride>) =
		PipelineConfig(atlasSize = 1024, generatePhysics = false, exportMoc3 = false, layerOverrides = overrides)

	fun RigPreviewModel.drawableOf(layer: String) = rig.layerIdByDrawableId.entries.single { it.value == layer }.key

	/** The shown rig and the base rig, as content hashes. */
	fun digest(model: RigPreviewModel) = listOf(ContentHash.of(PuppetIr.toIr(model.rig.puppet)), ContentHash.of(PuppetIr.toIr(model.baseRig.puppet)))

	/** [config] built from empty skeleton caches with the rig builder's stage caches off. */
	fun cold(source: WorkspaceSourceArt, config: PipelineConfig): RigPreviewModel {
		val was = RigStageCache.enabled
		RigStageCache.enabled = false
		SkeletonRig.clearCache()
		try { return PSD2LivePipeline().buildPreview(source, config) } finally { RigStageCache.enabled = was }
	}
}
