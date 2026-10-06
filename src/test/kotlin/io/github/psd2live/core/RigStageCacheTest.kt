package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.art.SourceArt
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.*

/**
 * The rig builder's stage cache: a build through it equals a build whose stages all run, across a sequence of
 * classification and mesh-setting changes; a change reruns only the stages that read it; an unchanged build
 * returns the very same rig instance.
 */
class RigStageCacheTest {
	private val source: SourceArt by lazy { PSD2LivePipeline().inspect(Path.of("examples/tml/psd-input/tml.psd")).source }
	private val layers by lazy { RigGenerationSource.analyze(source, PipelineConfig()).layers.filter { it.opaquePixels > 0 } }

	/** Adaptive meshes are content-addressed and shared; only the stage outputs are what a cold build must not reuse. */
	private val meshes = PreviewMeshCache()
	private val cold = PreviewMeshCache()

	private fun build(config: PipelineConfig, cache: PreviewMeshCache?): BuiltRig {
		val analysis = RigGenerationSource.analyze(source, config)
		return RigBuilder.build(analysis, AtlasLayout.pack(analysis.layers, config), config, cache)
	}

	/** A build with every stage run: the cold cache's stages are emptied first, its adaptive meshes kept. */
	private fun coldBuild(config: PipelineConfig): BuiltRig {
		cold.stages.clear()
		return build(config, cold)
	}

	private fun fingerprint(rig: BuiltRig): List<Any?> = listOf(ContentHash.of(PuppetIr.toIr(rig.puppet)), rig.unbound?.let { ContentHash.of(PuppetIr.toIr(it)) },
		rig.pageByDrawableId, rig.sourceBoundsByDrawableId, rig.layerIdByDrawableId, rig.warnings,
		rig.faceCenterX, rig.faceCenterY, rig.faceRadiusX, rig.faceRadiusY, rig.initialHeadAngleZ)

	private val roles = listOf(SemanticTag.NECKWEAR, SemanticTag.BOTTOMWEAR, SemanticTag.OBJECTS, SemanticTag.HEADWEAR,
		SemanticTag.FRONT_HAIR, SemanticTag.EYEBROW, SemanticTag.UNKNOWN)

	private fun mutate(config: PipelineConfig, random: Random): PipelineConfig {
		val layer = layers[random.nextInt(layers.size)]
		val id = layer.source.id.raw
		return when (random.nextInt(6)) {
			0, 1 -> config.copy(layerOverrides = config.layerOverrides + (id to LayerClassificationOverride(
				LayerType.PRESET, roles[random.nextInt(roles.size)], layer.semantic.side)))
			2 -> config.copy(layerOverrides = config.layerOverrides + (id to LayerClassificationOverride(
				LayerType.TOGGLE, layer.semantic.tag, layer.semantic.side, "ParamToggle${random.nextInt(2)}")))
			3 -> config.copy(meshOverrides = config.meshOverrides + (id to config.defaultMeshSettings(layer.semantic.tag)
				.copy(maxEdgeDistance = 12f + random.nextInt(4) * 6f)))
			// Undo-like: drop a change, so later builds return to inputs seen before.
			4 -> if (config.layerOverrides.isEmpty()) config.copy(meshOverrides = config.meshOverrides - config.meshOverrides.keys.firstOrNull().orEmpty())
				else config.copy(layerOverrides = config.layerOverrides - config.layerOverrides.keys.random(random))
			else -> config.copy(meshMaxEdgeDistance = if (config.meshMaxEdgeDistance == 6f) 9f else 6f)
		}
	}

	@Test fun aStagedBuildEqualsAColdBuildAcrossClassificationAndMeshChanges() {
		val random = Random(20261007)
		var config = PipelineConfig()
		assertEquals(fingerprint(coldBuild(config)), fingerprint(build(config, meshes)))
		repeat(10) { step ->
			config = mutate(config, random)
			assertEquals(fingerprint(coldBuild(config)), fingerprint(build(config, meshes)), "step $step")
		}
		assertTrue(meshes.stages.hits(RigStageCache.MESH) > 0, "the sequence reused layer meshes")
	}

	@Test fun aSingleLayerMeshOverrideRebuildsOnlyThatLayersMesh() {
		val config = PipelineConfig()
		build(config, meshes)
		// A layer's new mesh moves its footprint, which can move the anchors and the frames other meshes are
		// normalized in; those meshes rebuild, the rest - here every layer of the face - are reused.
		val faceLayers = layers.filter { it.semantic.tag in setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYEBROW) }
			.mapTo(HashSet()) { it.source.id.raw }
		for (target in layers.filter { it.semantic.tag in setOf(SemanticTag.BOTTOMWEAR, SemanticTag.HEADWEAR) }) {
			val before = build(config, meshes)
			val changed = config.copy(meshOverrides = mapOf(target.source.id.raw to config.defaultMeshSettings(target.semantic.tag).copy(maxEdgeDistance = 30f)))
			val misses = meshes.stages.misses(RigStageCache.MESH)
			val rig = build(changed, meshes)
			assertEquals(fingerprint(coldBuild(changed)), fingerprint(rig), target.source.id.raw)
			val built = meshes.stages.misses(RigStageCache.MESH) - misses
			assertTrue(built in 1..layers.size / 3, "${target.source.id.raw}: $built meshes built")
			for (drawable in before.unbound!!.drawables.filter { before.layerIdByDrawableId[it.id.raw] in faceLayers })
				assertSame(drawable.geometryGrid, rig.unbound!!.drawables.single { it.id == drawable.id }.geometryGrid, drawable.id.raw)
		}
	}

	@Test fun aBodyLayerRoleChangeKeepsTheFaceMeshes() {
		val config = PipelineConfig()
		val before = build(config, meshes)
		val target = layers.first { it.semantic.tag == SemanticTag.NECKWEAR || it.semantic.tag == SemanticTag.BOTTOMWEAR }
		val changed = config.copy(layerOverrides = mapOf(target.source.id.raw to
			LayerClassificationOverride(LayerType.PRESET, SemanticTag.OBJECTS, target.semantic.side)))
		val rig = build(changed, meshes)
		assertEquals(fingerprint(coldBuild(changed)), fingerprint(rig))
		val faceLayers = layers.filter { it.semantic.tag in setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYEBROW, SemanticTag.FACE) }
			.mapTo(HashSet()) { it.source.id.raw }
		val face = before.puppet.drawables.filter { before.layerIdByDrawableId[it.id.raw] in faceLayers }
		assertTrue(face.isNotEmpty())
		// Reused stage outputs are the same instances: the face meshes' keyforms were not rebuilt.
		for (drawable in face) assertSame(before.unbound!!.drawables.single { it.id == drawable.id }.geometryGrid,
			rig.unbound!!.drawables.single { it.id == drawable.id }.geometryGrid, drawable.id.raw)
	}

	@Test fun theGraphListsTheRigStagesPerLayer() {
		val graph = DocumentGenerators.graph(RigEditOverlay.Empty, listOf("a", "b"))
		assertEquals(listOf("rig.footprints", "rig.scaffold", "mesh:a", "mesh:b", "rig", "skeleton", "journal", "overrides", "physics", "motions"),
			graph.order.map { it.id })
		// One layer's pixels rerun its mesh and the assembly, not the other layer's mesh.
		assertEquals(listOf("mesh:a", "rig", "skeleton", "journal", "overrides", "physics", "motions"),
			graph.stale(setOf("document:source:a")).map { it.id })
	}

	@Test fun aBuildWhoseStagesAreAllReusedReturnsTheSameRig() {
		val config = PipelineConfig()
		val first = build(config, meshes)
		// A journal entry no generation stage reads, as a geometry commit appends.
		val journal = config.copy(rigEdits = config.rigEdits.copy(authoringJournal = listOf(JsonObject(mapOf("op" to JsonPrimitive("canvas_geometry"))))))
		assertSame(first, build(journal, meshes))
		val other = config.copy(drawOrderOverrides = mapOf(layers.first().source.id.raw to 500f))
		assertNotSame(first, build(other, meshes))
		assertSame(first, build(config, meshes), "undo returns to the kept instance")
	}
}
