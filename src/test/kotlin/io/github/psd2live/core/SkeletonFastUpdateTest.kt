package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.format.art.*
import kotlin.test.*

/**
 * The skeleton bakes the base rig before the journal replays, yet reads some journal records: hand-edited
 * topology keeps a mesh unrefined. A journal-only update that changes such a record cannot replay onto the
 * cached base; it must equal a rebuild from empty caches.
 */
class SkeletonFastUpdateTest {
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
		layer("sleeveL", 4, intArrayOf(258, 120, 400, 148)),
	), emptyList())
	private val config = PipelineConfig(atlasSize = 1024, generatePhysics = false, exportMoc3 = false, layerOverrides = mapOf(
		"face" to LayerClassificationOverride(tag = SemanticTag.FACE),
		"top" to LayerClassificationOverride(tag = SemanticTag.TOPWEAR),
		"sleeveL" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.LEFT),
	))
	private val plain by lazy { PSD2LivePipeline().buildPreview(source, config) }
	private val sleeve by lazy { plain.rig.layerIdByDrawableId.entries.single { it.value == "sleeveL" }.key }
	private val arm by lazy {
		SkeletonSpec(bones = listOf(
			SkeletonBone("armL", "Upper arm L", null, BoneRole.UPPER_ARM, Side.LEFT, 262f, 134f, 330f, 134f, listOf(sleeve)),
			SkeletonBone("foreL", "Forearm L", "armL", BoneRole.FOREARM, Side.LEFT, 330f, 134f, 398f, 134f),
		))
	}

	private fun configOf(journal: List<JsonObject>) = config.copy(rigEdits = RigEditOverlay(skeleton = arm, authoringJournal = journal))

	/** A subdivision of the sleeve's first triangle; the skeleton leaves a hand-edited mesh unrefined, as [plain] has it. */
	private fun topology(model: RigPreviewModel): JsonObject {
		val mesh = model.baseRig.puppet.drawables.single { it.id.raw == sleeve }.mesh!!
		return buildJsonObject {
			put("op", "canvas_topology"); put("id", sleeve); put("action", "subdivide")
			put("vertices", JsonArray((0..2).map { JsonPrimitive(mesh.indices[it].toInt()) }))
		}
	}

	private fun digest(model: RigPreviewModel) = listOf(ContentHash.of(PuppetIr.toIr(model.rig.puppet)), ContentHash.of(PuppetIr.toIr(model.baseRig.puppet)))

	private fun cold(config: PipelineConfig): RigPreviewModel {
		val was = RigStageCache.enabled
		RigStageCache.enabled = false
		SkeletonRig.clearCache()
		try { return PSD2LivePipeline().buildPreview(source, config) } finally { RigStageCache.enabled = was }
	}

	/** What a workspace commit builds: the fast path when the pipeline allows it, else a rebuild. */
	private fun commit(pipeline: PSD2LivePipeline, current: RigPreviewModel, next: PipelineConfig): RigPreviewModel =
		if (pipeline.canFastUpdateRig(current, source, next)) pipeline.updateRigEdits(current, next)
		else pipeline.rebuildPreview(current, next)

	@Test fun addingHandEditedTopologyEqualsAColdBuild() {
		val pipeline = PSD2LivePipeline()
		val current = pipeline.buildPreview(source, configOf(emptyList()))
		val next = configOf(listOf(topology(plain)))
		assertEquals(digest(cold(next)), digest(commit(pipeline, current, next)))
	}

	@Test fun undoingHandEditedTopologyEqualsAColdBuild() {
		val pipeline = PSD2LivePipeline()
		val edited = configOf(listOf(topology(plain)))
		val current = pipeline.buildPreview(source, edited)
		val undone = configOf(emptyList())
		assertEquals(digest(cold(undone)), digest(commit(pipeline, current, undone)))
	}
}
