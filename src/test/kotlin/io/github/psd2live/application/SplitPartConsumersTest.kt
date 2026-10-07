package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.config
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Consumers after the journal see the parts a materialized split (art_primitive) made, never its superseded original:
 * presets address parts by the replayed layer map and a preset that turns on a generation setting rebuilds through
 * the same base the preview uses.
 */
@org.junit.jupiter.api.Tag("slow")
class SplitPartConsumersTest {
	private class Split(val builder: WorkspacePreviewBuilder, val capture: WorkspaceCapture<RigPreviewModel>, val partLayers: List<String>)

	private fun splitLegs(): Split = runBlocking {
		val builder = WorkspacePreviewBuilder()
		lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
		runtime = WorkspaceRuntime({ document -> builder.build(document, runtime.capture().model) })
		WorkspaceSourceImporter(runtime).importPsd(Path.of("examples/tml/psd-input/tml.psd").toAbsolutePath(), null, runtime.state.value.state)
		val start = runtime.capture()
		val layer = start.model.analysis.layers.single { it.semantic.tag == SemanticTag.LEGWEAR }.source.id.raw
		val parted = WorkspacePartitionCommands(runtime).execute(start.projectId, start.state, listOf(
			WorkspaceDocumentOperation("source_split_components", buildJsonObject {
				put("layer_id", layer); put("names", JsonArray(listOf("$layer L", "$layer R").map(::JsonPrimitive)))
				put("sides", JsonArray(listOf("left", "right").map(::JsonPrimitive)))
			})), "Split", MutationAuthor.USER).commit.capture
		assertEquals(1, ArtPrimitiveJournal.commands(parted.document.rigEdits).size)
		val parts = ArtPrimitiveJournal.primitives(ArtPrimitiveJournal.commands(parted.document.rigEdits).single())
			.map { it.getValue("layer_id").jsonPrimitive.content }
		assertEquals(2, parts.size)
		Split(builder, parted, parts)
	}

	@Test fun presetsAddressSplitPartsThroughTheReplayedLayerMap() {
		val split = splitLegs()
		val model = split.capture.model
		// The parts exist only after the journal: the base never maps them.
		val partIds = model.rig.layerIdByDrawableId.filterValues { it in split.partLayers }.keys
		assertEquals(2, partIds.size)
		assertTrue(partIds.none { it in model.baseRig.layerIdByDrawableId })
		val candidate = WorkspaceSimulationEdits.apply(WorkspaceDocumentOperation("model_apply_preset", buildJsonObject {
			put("preset", "auto_weights"); put("layers", JsonArray(split.partLayers.map(::JsonPrimitive)))
		}), split.capture.document, model)
		val weighted = candidate.document.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == VertexGroupJournal.PUT }
			.mapTo(HashSet()) { it.getValue("target").jsonPrimitive.content.removePrefix("mesh:") }
		assertEquals(partIds, weighted)
	}

	@Test fun aPresetThatTurnsOnHairSimulationRebuildsTheSplitDocument() = runBlocking<Unit> {
		val split = splitLegs()
		val document = split.capture.document
		assertTrue(!document.config().hairSimulationFront)
		val candidate = WorkspaceSimulationEdits.apply(WorkspaceDocumentOperation("model_apply_preset", buildJsonObject {
			put("preset", "front_hair")
		}), document, split.capture.model)
		assertTrue(candidate.document.config().hairSimulationFront)
		assertTrue(candidate.document.rigEdits.simEdits.any { it.id == io.github.psd2live.core.sim.ModelPresets.FRONT_HAIR_SIM })
		// The candidate builds, from the current model and from nothing alike.
		val rebuilt = split.builder.build(candidate.document, split.capture.model)
		val fresh = WorkspacePreviewBuilder().build(candidate.document)
		assertEquals(ContentHash.of(RigIrCompiler.compile(fresh)), ContentHash.of(RigIrCompiler.compile(rebuilt)))
	}

	/** A face or body setting goes through the generation transition; the split parts keep their records and the result rebuilds. */
	@Test fun aGenerationTransitionAfterASplitKeepsItsParts() {
		val split = splitLegs()
		val model = split.capture.model
		val pipeline = PSD2LivePipeline()
		val requested = model.config.copy(bodyStrength = 0.5f, headTurnStrength = 0.5f)
		assertTrue(RigGenerationMigration.changed(model, requested))
		val rebuilt = pipeline.rebuildPreview(model, requested)
		assertTrue(rebuilt.config.rigEdits.authoringJournal.any { it["op"]?.jsonPrimitive?.contentOrNull == RigGenerationJournal.OP })
		val parts = rebuilt.rig.layerIdByDrawableId.filterValues { it in split.partLayers }.keys
		assertEquals(model.rig.layerIdByDrawableId.filterValues { it in split.partLayers }.keys, parts)
		val fresh = PSD2LivePipeline().buildPreview(model.analysis.source, rebuilt.config)
		assertEquals(ContentHash.of(RigIrCompiler.compile(fresh)), ContentHash.of(RigIrCompiler.compile(rebuilt)))
	}

	/** Without version 2 records a migration build is the plain generation, whatever the journal holds. */
	@Test fun migrationBuildsIgnoreVersionOneRecords() {
		// Version 1 records, also when the suite runs with version 2 enabled.
		val previous = System.getProperty(ArtPrimitiveV2.FLAG_PROPERTY)
		System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "false")
		val split = try { splitLegs() } finally {
			if (previous == null) System.clearProperty(ArtPrimitiveV2.FLAG_PROPERTY) else System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, previous)
		}
		val model = split.capture.model
		assertTrue(ArtPrimitiveV2.resolve(model.config.rigEdits).isEmpty())
		val pipeline = PSD2LivePipeline()
		val stable = model.rig.layerIdByDrawableId.entries.associate { it.value to it.key }
		val generated = RigGenerationMigration.generatedRig(pipeline, model.analysis.source, model.config, stable) { _, _ -> }
		val plain = pipeline.buildPreview(model.analysis.source, model.config.copy(generationSource = null, meshSource = null,
			parentOverrides = emptyMap(), deletedLayerIds = emptySet(), rigEdits = RigEditOverlay.Empty.copy(splitDrawableIds = stable,
				splitBaselineLayerIds = model.config.rigEdits.splitBaselineLayerIds, calibrationLayerIds = model.config.rigEdits.calibrationLayerIds,
				skeleton = model.config.rigEdits.skeleton)))
		assertEquals(ContentHash.of(RigIrCompiler.compile(plain)),
			ContentHash.of(RigIrCompiler.compile(plain.analysis, plain.atlas, generated, plain.config)))
		assertEquals(plain.rig.layerIdByDrawableId, generated.layerIdByDrawableId)
	}

	/**
	 * With version 2 records the migration builds see the resolved layer set: parts on their recorded meshes, no
	 * superseded original, so a face or body change reaches the parts like any other mesh.
	 */
	@Test fun migrationBuildsResolveVersionTwoRecords() {
		val previous = System.getProperty(ArtPrimitiveV2.FLAG_PROPERTY)
		System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true")
		try {
			val split = splitLegs()
			val model = split.capture.model
			val record = ArtPrimitiveJournal.commands(model.config.rigEdits).single()
			assertTrue(ArtPrimitiveV2.isV2(record))
			val resolved = ArtPrimitiveV2.resolve(model.config.rigEdits)
			val pipeline = PSD2LivePipeline()
			val stable = model.rig.layerIdByDrawableId.entries.associate { it.value to it.key }
			val generated = RigGenerationMigration.generatedRig(pipeline, model.analysis.source, model.config, stable) { _, _ -> }
			val ids = generated.puppet.drawables.mapTo(HashSet()) { it.id }
			assertTrue(resolved.parts.all { it.drawableId in ids }, "every part is in the resolved migration build")
			assertTrue(resolved.stubDrawables.none { it in ids }, "no superseded original is")
			for (part in resolved.parts) assertEquals(part.layerId, generated.layerIdByDrawableId[part.drawableId.raw])

			val rebuilt = pipeline.rebuildPreview(model, model.config.copy(bodyStrength = 0.5f, headTurnStrength = 0.5f))
			val fresh = PSD2LivePipeline().buildPreview(model.analysis.source, rebuilt.config)
			assertEquals(ContentHash.of(RigIrCompiler.compile(fresh)), ContentHash.of(RigIrCompiler.compile(rebuilt)))
		} finally {
			if (previous == null) System.clearProperty(ArtPrimitiveV2.FLAG_PROPERTY) else System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, previous)
		}
	}
}
