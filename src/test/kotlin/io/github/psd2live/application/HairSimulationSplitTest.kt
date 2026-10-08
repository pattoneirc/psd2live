package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import kotlin.test.*

/** Split back hair under the legacy sway warp, then the back hair simulation preset drops that warp. */
class HairSimulationSplitTest {
	private val builder = WorkspacePreviewBuilder()

	private fun layer(id: String, name: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(LayerId(id), name, "",
		SourceLayerKind.Raster, true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) 255.toByte() else 120 }),
		null, null, false)

	private fun document(): WorkspaceDocument {
		val layers = listOf(layer("hair", "Back hair", 0, LayerBounds(16, 8, 32, 48)), layer("face", "Face", 1, LayerBounds(20, 12, 24, 24)))
		return WorkspaceDocument(WorkspaceSourceArt(64, 64, layers, emptyList()), emptyMap(), emptySet(), mapOf(
			"hair" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.BACK_HAIR),
			"face" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.FACE),
		), emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, exportMoc3 = false)))
	}

	@Test fun splitBackHairPartsLeaveTheLegacySwayWarpWithTheBase() = runBlocking<Unit> {
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
		val document = document()
		runtime.install(runtime.state.value.state, "project", document, builder.build(document))
		val start = runtime.capture()
		val split = WorkspacePartitionCommands(runtime).execute(start.projectId, start.state, listOf(
			WorkspaceDocumentOperation("source_split_polygon", buildJsonObject {
				put("layer_id", "hair"); putJsonArray("names") { add("Left"); add("Right") }; putJsonArray("piece_ids") { add("left"); add("right") }
				putJsonArray("polygon") { listOf(0 to 0, 32 to 0, 32 to 64, 0 to 64).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
			})), "Split", MutationAuthor.USER).commit.capture
		val parts = ArtPrimitiveJournal.commands(split.document.rigEdits).single().let(ArtPrimitiveJournal::primitives).map { DrawableId(it.getValue("id").jsonPrimitive.content) }
		val sway = split.model.rig.puppet.drawables.filter { it.id in parts }.map { it.parentDeformerId?.raw }
		assertEquals(listOf("DeformHairBackPhysics", "DeformHairBackPhysics"), sway)

		val simulated = builder.build(split.document.copy(settings = JsonObject(split.document.settings + ("hairSimulationBack" to JsonPrimitive(true)))))
		val puppet = simulated.rig.puppet
		assertTrue(puppet.deformers.none { it.id.raw == "DeformHairBackPhysics" })
		val before = CpuDeformationEvaluator().evaluate(split.model.rig.puppet, emptyMap()).worldPositions
		val after = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions
		for (id in parts) {
			assertTrue(puppet.deformers.any { it.id == puppet.drawables.single { d -> d.id == id }.parentDeformerId }, id.raw)
			val want = before.getValue(id); val got = after.getValue(id)
			want.indices.forEach { assertEquals(want[it], got[it], 1e-3f, "${id.raw}[$it]") }
		}
	}
}
