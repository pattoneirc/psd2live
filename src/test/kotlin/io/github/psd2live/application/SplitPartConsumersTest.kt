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
}
