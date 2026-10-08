package io.github.psd2live.core

import io.github.psd2live.core.SkeletonCharacterFixture.cold
import io.github.psd2live.core.SkeletonCharacterFixture.digest
import io.github.psd2live.core.SkeletonCharacterFixture.drawableOf
import io.github.psd2live.core.SkeletonCharacterFixture.layer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * A skeleton edit rebakes only what it touches: the skins of limbs it leaves alone, their refined meshes and
 * the generated deformers come back from their caches, and the rig equals a bake from empty caches.
 *
 * The skeleton bakes the base rig before the journal replays, yet reads some journal records: hand-edited
 * topology keeps a mesh unrefined. A journal-only update that changes such a record cannot replay onto the
 * cached base; it must equal a rebuild from empty caches.
 */
class SkeletonIncrementalBakeTest {
	private companion object {
		val source = SkeletonCharacterFixture.source(
			layer("face", 5, intArrayOf(170, 30, 250, 110)),
			layer("top", 3, intArrayOf(160, 115, 260, 240)),
			layer("legs", 2, intArrayOf(175, 235, 245, 410)),
			layer("sleeveL", 4, intArrayOf(258, 120, 400, 148)),
			layer("sleeveR", 6, intArrayOf(20, 120, 162, 148)),
		)
		val config = SkeletonCharacterFixture.config(mapOf(
			"face" to LayerClassificationOverride(tag = SemanticTag.FACE),
			"top" to LayerClassificationOverride(tag = SemanticTag.TOPWEAR),
			"legs" to LayerClassificationOverride(tag = SemanticTag.LEGWEAR),
			"sleeveL" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.LEFT),
			"sleeveR" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.RIGHT),
		))
		val plain by lazy { PSD2LivePipeline().buildPreview(source, config) }

		/** Two arms of two bones each, every arm skinning its sleeve across the elbow. */
		val arms by lazy {
			SkeletonSpec(bones = listOf(
				SkeletonBone("armL", "Upper arm L", null, BoneRole.UPPER_ARM, Side.LEFT, 262f, 134f, 330f, 134f, listOf(plain.drawableOf("sleeveL"))),
				SkeletonBone("foreL", "Forearm L", "armL", BoneRole.FOREARM, Side.LEFT, 330f, 134f, 398f, 134f),
				SkeletonBone("armR", "Upper arm R", null, BoneRole.UPPER_ARM, Side.RIGHT, 158f, 134f, 90f, 134f, listOf(plain.drawableOf("sleeveR")), direction = -1f),
				SkeletonBone("foreR", "Forearm R", "armR", BoneRole.FOREARM, Side.RIGHT, 90f, 134f, 22f, 134f, direction = -1f),
			))
		}
	}

	private fun configOf(spec: SkeletonSpec, journal: List<JsonObject> = emptyList()) =
		config.copy(rigEdits = RigEditOverlay(skeleton = spec, authoringJournal = journal))

	private fun <T> profiled(block: () -> T): Pair<T, Map<String, Pair<Double, Long>>> {
		RigBuildProfile.reset(); RigBuildProfile.recording = true
		try { return block() to RigBuildProfile.snapshot() } finally { RigBuildProfile.recording = false; RigBuildProfile.reset() }
	}

	/** A subdivision of the left sleeve's first triangle; the skeleton leaves a hand-edited mesh unrefined, as [plain] has it. */
	private fun topology(): JsonObject {
		val sleeve = plain.drawableOf("sleeveL")
		val mesh = plain.baseRig.puppet.drawables.single { it.id.raw == sleeve }.mesh!!
		return buildJsonObject {
			put("op", "canvas_topology"); put("id", sleeve); put("action", "subdivide")
			put("vertices", JsonArray((0..2).map { JsonPrimitive(mesh.indices[it].toInt()) }))
		}
	}

	/** What a workspace commit builds: the fast path when the pipeline allows it, else a rebuild. */
	private fun commit(pipeline: PSD2LivePipeline, current: RigPreviewModel, next: PipelineConfig): RigPreviewModel =
		if (pipeline.canFastUpdateRig(current, source, next)) pipeline.updateRigEdits(current, next)
		else pipeline.rebuildPreview(current, next)

	@Test fun aBoneElsewhereKeepsTheOtherSkinsAndTheDeformersAndEqualsAColdBake() {
		val pipeline = PSD2LivePipeline()
		SkeletonRig.clearCache()
		pipeline.buildPreview(source, configOf(arms))
		// A bone of its own for the legs: a new limb tree, which skins only its mesh.
		val withBone = arms.withBone(SkeletonBone("tail", "Tail", null, BoneRole.CUSTOM, Side.NONE, 210f, 240f, 210f, 330f, listOf(plain.drawableOf("legs"))))
		val hits = SkeletonRig.skinCacheHits; val misses = SkeletonRig.skinCacheMisses
		val (model, stages) = profiled { pipeline.buildPreview(source, configOf(withBone)) }
		assertEquals(1, SkeletonRig.skinCacheMisses - misses, "only the legs are skinned")
		assertEquals(2, SkeletonRig.skinCacheHits - hits, "both sleeves take their skins back")
		assertNull(stages["scaffold: deformers (built)"], "the deformers do not read a custom bone")
		assertNotNull(model.baseRig.puppet.deformers.firstOrNull { it.id.raw == "DeformSkel_tail" }, "the new bone is baked")
		assertEquals(digest(cold(source, configOf(withBone))), digest(model))
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
		assertEquals(digest(cold(source, configOf(moved))), digest(model))
		// Back to the first skeleton: every skin is cached, and the rig is the first one's.
		val back = pipeline.buildPreview(source, configOf(arms))
		assertEquals(digest(cold(source, configOf(arms))), digest(back))
	}

	@Test fun aSavedPoseKeepsTheBakeInputsButAColdBakeStillAgrees() {
		val pipeline = PSD2LivePipeline()
		SkeletonRig.clearCache()
		pipeline.buildPreview(source, configOf(arms))
		val posed = arms.withSavedPose("wave", mapOf("ParamArmLA" to 30f))
		val misses = SkeletonRig.skinCacheMisses
		val model = pipeline.buildPreview(source, configOf(posed))
		assertEquals(0, SkeletonRig.skinCacheMisses - misses)
		assertEquals(digest(cold(source, configOf(posed))), digest(model))
	}

	@Test fun parallelSamplesBakeTheSameRigAsSequentialOnes() {
		val was = SkeletonRig.parallelSamples
		try {
			SkeletonRig.parallelSamples = false
			val sequential = cold(source, configOf(arms))
			SkeletonRig.parallelSamples = true
			assertEquals(digest(sequential), digest(cold(source, configOf(arms))))
		} finally { SkeletonRig.parallelSamples = was }
	}

	@Test fun addingHandEditedTopologyEqualsAColdBuild() {
		val pipeline = PSD2LivePipeline()
		val current = pipeline.buildPreview(source, configOf(arms))
		val next = configOf(arms, listOf(topology()))
		assertEquals(digest(cold(source, next)), digest(commit(pipeline, current, next)))
	}

	@Test fun undoingHandEditedTopologyEqualsAColdBuild() {
		val pipeline = PSD2LivePipeline()
		val current = pipeline.buildPreview(source, configOf(arms, listOf(topology())))
		val undone = configOf(arms)
		assertEquals(digest(cold(source, undone)), digest(commit(pipeline, current, undone)))
	}
}
