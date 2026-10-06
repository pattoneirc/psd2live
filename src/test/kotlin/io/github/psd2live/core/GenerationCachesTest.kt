package io.github.psd2live.core

import io.github.psd2live.targets.cubism.PuppetIr
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.test.*

/** Generators that cost far more than hashing their inputs reuse their output for the same inputs. */
class GenerationCachesTest {
	@Test fun generatedMotionsAreReusedForTheSameInputs() {
		val first = MotionPresets.tracks("Nod", null)
		assertSame(first, MotionPresets.tracks("Nod", null), "the same inputs reuse the generated tracks")
		val stronger = MotionPresets.tracks("Nod", null, MotionPresetSettings(mapOf(MotionPresets.AMPLITUDE to 0.5f)))
		assertNotSame(first, stronger)
		assertNotEquals(first, stronger, "other settings generate again")
		assertEquals(first, MotionPresets.tracks("nod", null), "the name is matched without case, as the generator does")
	}

	@Tag("slow")
	@Test fun anUnchangedSkeletonIsNotSkinnedAgain() {
		val plain = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val config = plain.config.copy(rigEdits = plain.config.rigEdits.copy(skeleton = SkeletonAutoBuilder.build(plain.analysis, plain.rig)))
		// The cache is process-wide; another test in this JVM may already have skinned the same figure.
		SkeletonRig.clearCache()
		val misses = SkeletonRig.cacheMisses
		val first = PSD2LivePipeline().buildPreview(plain.analysis, config)
		assertEquals(misses + 1, SkeletonRig.cacheMisses, "the first build skins the figure")
		val hits = SkeletonRig.cacheHits
		// Another pipeline, as an export or a reopened project would use: only the skeleton stage is shared.
		val second = PSD2LivePipeline().buildPreview(plain.analysis, config)
		assertEquals(hits + 1, SkeletonRig.cacheHits, "the same base rig and skeleton reuse the bake")
		assertEquals(misses + 1, SkeletonRig.cacheMisses)
		assertEquals(PuppetIr.toIr(first.rig.puppet), PuppetIr.toIr(second.rig.puppet))
		// A changed skeleton skins again.
		val bone = config.rigEdits.skeleton!!.bones.first { !it.role.body && it.parentId != null }
		val moved = config.rigEdits.skeleton!!.withBone(bone.copy(tailX = bone.tailX + 4f))
		PSD2LivePipeline().buildPreview(plain.analysis, config.copy(rigEdits = config.rigEdits.copy(skeleton = moved)))
		assertEquals(misses + 2, SkeletonRig.cacheMisses)
	}
}
