package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.format.art.*
import kotlin.test.*

/**
 * A skeleton edit rebakes only what it touches: the skins of limbs it leaves alone, their refined meshes and
 * the generated deformers come back from their caches, and the rig equals a bake from empty caches.
 */
class SkeletonIncrementalBakeTest {
	private val width = 420
	private val height = 420

	private fun layer(id: String, order: Int, box: IntArray): WorkspaceSourceLayer {
		val rgba = ByteArray(width * height * 4)
		for (y in box[1] until box[3]) for (x in box[0] until box[2]) {
			val offset = (y * width + x) * 4
			rgba[offset] = 120; rgba[offset + 1] = 90; rgba[offset + 2] = 60; rgba[offset + 3] = 255.toByte()
		}
		return WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order, LayerBounds(0, 0, width, height), 1f, false,
			LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
	}

	private val source = WorkspaceSourceArt(width, height, listOf(
		layer("face", 5, intArrayOf(170, 30, 250, 110)),
		layer("top", 3, intArrayOf(160, 115, 260, 240)),
		layer("legs", 2, intArrayOf(175, 235, 245, 410)),
		layer("sleeveL", 4, intArrayOf(258, 120, 400, 148)),
		layer("sleeveR", 6, intArrayOf(20, 120, 162, 148)),
	), emptyList())
	private val config = PipelineConfig(atlasSize = 1024, generatePhysics = false, exportMoc3 = false, layerOverrides = mapOf(
		"face" to LayerClassificationOverride(tag = SemanticTag.FACE),
		"top" to LayerClassificationOverride(tag = SemanticTag.TOPWEAR),
		"legs" to LayerClassificationOverride(tag = SemanticTag.LEGWEAR),
		"sleeveL" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.LEFT),
		"sleeveR" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.RIGHT),
	))
	private val plain by lazy { PSD2LivePipeline().buildPreview(source, config) }
	private fun drawableOf(layer: String) = plain.rig.layerIdByDrawableId.entries.single { it.value == layer }.key

	/** Two arms of two bones each, every arm skinning its sleeve across the elbow. */
	private val arms by lazy {
		SkeletonSpec(bones = listOf(
			SkeletonBone("armL", "Upper arm L", null, BoneRole.UPPER_ARM, Side.LEFT, 262f, 134f, 330f, 134f, listOf(drawableOf("sleeveL"))),
			SkeletonBone("foreL", "Forearm L", "armL", BoneRole.FOREARM, Side.LEFT, 330f, 134f, 398f, 134f),
			SkeletonBone("armR", "Upper arm R", null, BoneRole.UPPER_ARM, Side.RIGHT, 158f, 134f, 90f, 134f, listOf(drawableOf("sleeveR")), direction = -1f),
			SkeletonBone("foreR", "Forearm R", "armR", BoneRole.FOREARM, Side.RIGHT, 90f, 134f, 22f, 134f, direction = -1f),
		))
	}

	private fun configOf(spec: SkeletonSpec) = config.copy(rigEdits = RigEditOverlay(skeleton = spec))

	private fun digest(model: RigPreviewModel) = listOf(ContentHash.of(PuppetIr.toIr(model.rig.puppet)), ContentHash.of(PuppetIr.toIr(model.baseRig.puppet)))

	/** [spec] built from empty skeleton caches with the rig builder's stage caches off. */
	private fun cold(spec: SkeletonSpec): RigPreviewModel {
		val was = RigStageCache.enabled
		RigStageCache.enabled = false
		SkeletonRig.clearCache()
		try { return PSD2LivePipeline().buildPreview(source, configOf(spec)) } finally { RigStageCache.enabled = was }
	}

	private fun <T> profiled(block: () -> T): Pair<T, Map<String, Pair<Double, Long>>> {
		RigBuildProfile.reset(); RigBuildProfile.recording = true
		try { return block() to RigBuildProfile.snapshot() } finally { RigBuildProfile.recording = false; RigBuildProfile.reset() }
	}

	@Test fun aBoneElsewhereKeepsTheOtherSkinsAndTheDeformersAndEqualsAColdBake() {
		val pipeline = PSD2LivePipeline()
		SkeletonRig.clearCache()
		pipeline.buildPreview(source, configOf(arms))
		// A bone of its own for the legs: a new limb tree, which skins only its mesh.
		val withBone = arms.withBone(SkeletonBone("tail", "Tail", null, BoneRole.CUSTOM, Side.NONE, 210f, 240f, 210f, 330f, listOf(drawableOf("legs"))))
		val hits = SkeletonRig.skinCacheHits; val misses = SkeletonRig.skinCacheMisses
		val (model, stages) = profiled { pipeline.buildPreview(source, configOf(withBone)) }
		assertEquals(1, SkeletonRig.skinCacheMisses - misses, "only the legs are skinned")
		assertEquals(2, SkeletonRig.skinCacheHits - hits, "both sleeves take their skins back")
		assertNull(stages["scaffold: deformers (built)"], "the deformers do not read a custom bone")
		assertNotNull(model.baseRig.puppet.deformers.firstOrNull { it.id.raw == "DeformSkel_tail" }, "the new bone is baked")
		assertEquals(digest(cold(withBone)), digest(model))
	}

	@Test fun aMovedJointRebakesOnlyItsLimbAndEqualsAColdBake() {
		val pipeline = PSD2LivePipeline()
		SkeletonRig.clearCache()
		pipeline.buildPreview(source, configOf(arms))
		val moved = arms.withJointMoved("foreL", BoneEnd.HEAD, 320f, 136f)
		val hits = SkeletonRig.skinCacheHits; val misses = SkeletonRig.skinCacheMisses
		val model = pipeline.buildPreview(source, configOf(moved))
		assertEquals(1, SkeletonRig.skinCacheMisses - misses, "only the left sleeve is skinned again")
		assertEquals(1, SkeletonRig.skinCacheHits - hits, "the right sleeve takes its skin back")
		assertEquals(digest(cold(moved)), digest(model))
		// Back to the first skeleton: every skin is cached, and the rig is the first one's.
		val back = pipeline.buildPreview(source, configOf(arms))
		assertEquals(digest(cold(arms)), digest(back))
	}

	@Test fun aSavedPoseKeepsTheBakeInputsButAColdBakeStillAgrees() {
		val pipeline = PSD2LivePipeline()
		SkeletonRig.clearCache()
		pipeline.buildPreview(source, configOf(arms))
		val posed = arms.withSavedPose("wave", mapOf("ParamArmLA" to 30f))
		val misses = SkeletonRig.skinCacheMisses
		val model = pipeline.buildPreview(source, configOf(posed))
		assertEquals(0, SkeletonRig.skinCacheMisses - misses)
		assertEquals(digest(cold(posed)), digest(model))
	}
}
