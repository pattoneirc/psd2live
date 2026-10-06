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

	@Tag("slow")
	@Test fun aDifferentAtlasLayoutKeepsTheBakeAndEqualsAColdBuild() {
		val plain = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val config = plain.config.copy(rigEdits = plain.config.rigEdits.copy(skeleton = SkeletonAutoBuilder.build(plain.analysis, plain.rig)))
		SkeletonRig.clearCache()
		val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, config)
		val bound = config.rigEdits.skeleton!!.bones.flatMapTo(HashSet()) { it.drawableIds }
		val layer = skeletal.rig.layerIdByDrawableId.getValue(bound.first())
		val placement = skeletal.atlas.placementByLayerId.getValue(layer)
		// A pinned tile, a denser layer and a larger padding: every bound mesh's uvs change, the bake does not.
		val moved = config.copy(textureOverrides = mapOf(layer to io.github.psd2live.project.TextureOverride(density = 2f,
			pin = io.github.psd2live.project.TexturePin(0, placement.x + 640, placement.y + 512))),
			atlasBudget = config.effectiveAtlasBudget().copy(padding = 6))
		val misses = SkeletonRig.cacheMisses
		val hits = SkeletonRig.cacheHits
		val warm = PSD2LivePipeline().buildPreview(plain.analysis, moved)
		assertEquals(misses, SkeletonRig.cacheMisses, "moving or scaling tiles does not bake the skeleton again")
		assertEquals(hits + 1, SkeletonRig.cacheHits)
		assertNotEquals(skeletal.atlas.placementByLayerId.getValue(layer), warm.atlas.placementByLayerId.getValue(layer))
		SkeletonRig.clearCache()
		val cold = PSD2LivePipeline().buildPreview(plain.analysis, moved)
		assertEquals(PuppetIr.toIr(cold.rig.puppet), PuppetIr.toIr(warm.rig.puppet), "the reused bake binds exactly as a cold one")
	}

	@Tag("slow")
	@Test fun editsToMeshesNoBoneBindsKeepTheBake() {
		val plain = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val spec = SkeletonAutoBuilder.build(plain.analysis, plain.rig)
		val config = plain.config.copy(rigEdits = plain.config.rigEdits.copy(skeleton = spec))
		val bound = spec.bones.flatMapTo(HashSet()) { it.drawableIds }
		val unbound = plain.rig.puppet.drawables.filter { it.mesh != null && it.id.raw !in bound }
		SkeletonRig.clearCache()
		val misses = SkeletonRig.cacheMisses
		val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, config)
		assertEquals(misses + 1, SkeletonRig.cacheMisses)
		// One mesh the bake moves under its torso warp, and one it never touches.
		fun reparented(drawable: org.umamo.runtime.model.Drawable) =
			skeletal.baseRig.puppet.drawables.single { it.id == drawable.id }.parentDeformerId == SkeletonRig.torsoWarpId
		val onBody = unbound.firstOrNull(::reparented)
		val elsewhere = unbound.first { !reparented(it) }

		// A hand-made topology edit locks that mesh's vertices, which only matters to a mesh the bake skins.
		val mesh = elsewhere.mesh!!
		val topology = kotlinx.serialization.json.buildJsonObject {
			put("op", kotlinx.serialization.json.JsonPrimitive("canvas_topology")); put("id", kotlinx.serialization.json.JsonPrimitive(elsewhere.id.raw))
			put("action", kotlinx.serialization.json.JsonPrimitive("subdivide"))
			put("vertices", kotlinx.serialization.json.JsonArray((0..2).map { kotlinx.serialization.json.JsonPrimitive(mesh.indices[it]) }))
		}
		val topologyConfig = config.copy(rigEdits = config.rigEdits.copy(authoringJournal = config.rigEdits.authoringJournal + topology))
		val hits = SkeletonRig.cacheHits
		val afterTopology = PSD2LivePipeline().buildPreview(plain.analysis, topologyConfig)
		assertEquals(hits + 1, SkeletonRig.cacheHits, "an unrelated topology edit reuses the bake")
		assertEquals(misses + 1, SkeletonRig.cacheMisses)

		// Other mesh settings on unbound layers inside the figure change their base meshes and fitted deformers but
		// not the body frame (an outline on the figure's edge would move it); the reused bake must equal a fresh one.
		val inside = unbound.filter { it.id.raw.contains("Irides") || it.id.raw.contains("Eyebrow") }
		assertTrue(inside.isNotEmpty())
		val layers = (listOfNotNull(onBody) + inside).map { plain.rig.layerIdByDrawableId[it.id.raw] ?: it.id.raw }
		val meshConfig = topologyConfig.copy(meshOverrides = layers.associateWith { MeshSettings(interiorDensity = 18f, edgeWidth = 6f) })
		val reused = PSD2LivePipeline().buildPreview(plain.analysis, meshConfig)
		assertEquals(hits + 2, SkeletonRig.cacheHits, "unbound mesh settings reuse the bake")
		assertEquals(misses + 1, SkeletonRig.cacheMisses)
		assertNotEquals(PuppetIr.toIr(afterTopology.rig.puppet), PuppetIr.toIr(reused.rig.puppet), "the settings changed the rig")
		SkeletonRig.clearCache()
		val fresh = PSD2LivePipeline().buildPreview(plain.analysis, meshConfig)
		assertEquals(misses + 2, SkeletonRig.cacheMisses)
		assertEquals(PuppetIr.toIr(fresh.rig.puppet), PuppetIr.toIr(reused.rig.puppet))
		assertEquals(PuppetIr.toIr(fresh.baseRig.puppet), PuppetIr.toIr(reused.baseRig.puppet))
	}
}
