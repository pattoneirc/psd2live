package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import kotlin.test.*

/** A preview built from a stored authored rig is the preview generation and replay build, without either. */
class MaterializedPreviewTest {
	private val width = 240
	private val height = 240

	private fun layer(id: String, order: Int, box: IntArray) = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order,
		LayerBounds(0, 0, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, ByteArray(width * height * 4).also { rgba ->
			for (y in box[1] until box[3]) for (x in box[0] until box[2]) {
				val offset = (y * width + x) * 4
				rgba[offset] = 120; rgba[offset + 1] = 90; rgba[offset + 2] = 60; rgba[offset + 3] = 255.toByte()
			}
		}), null, null, false)

	private val source = WorkspaceSourceArt(width, height, listOf(
		layer("back", 0, intArrayOf(60, 20, 180, 200)),
		layer("face", 1, intArrayOf(80, 30, 160, 110)),
		layer("front", 2, intArrayOf(70, 20, 170, 70)),
		layer("body", 3, intArrayOf(90, 110, 150, 230)),
	), emptyList())

	private val config = PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false, layerOverrides = mapOf(
		"back" to LayerClassificationOverride(tag = SemanticTag.BACK_HAIR),
		"face" to LayerClassificationOverride(tag = SemanticTag.FACE),
		"front" to LayerClassificationOverride(tag = SemanticTag.FRONT_HAIR),
		"body" to LayerClassificationOverride(tag = SemanticTag.TOPWEAR),
	))

	private fun hash(model: RigPreviewModel) = ContentHash.of(PuppetIr.toIr(model.rig.puppet))

	/** A journal and generators touching every stage: a structure edit, a user warp, a vertex group and a swing. */
	private fun edited(pipeline: PSD2LivePipeline): PipelineConfig {
		val plain = pipeline.buildPreview(source, config)
		val layers = plain.rig.layerIdByDrawableId.entries.associate { it.value to it.key }
		val front = layers.getValue("front"); val body = layers.getValue("body")
		val mesh = plain.rig.puppet.drawables.single { it.id.raw == body }.mesh!!
		val journal = listOf(
			buildJsonObject { put("op", "structure"); putJsonArray("edits") {
				add(buildJsonObject { put("action", "create"); put("kind", "parameter"); put("id", "ParamUser"); put("name", "User")
					put("min", 0); put("max", 1); put("default", 0) })
			} },
			buildJsonObject { put("op", "warp"); put("warp", RigWarpEdit("WarpUser", "User warp",
				plain.rig.puppet.drawables.single { it.id.raw == body }.parentDeformerId!!.raw, listOf(body), 2, 2).toJson()) },
			VertexGroupJournal.encode(VertexGroup("pin", DrawableId(body), VertexGroupKind.PIN, FloatArray(mesh.vertexCount) { 0.5f })),
		)
		var overlay = RigEditOverlay(authoringJournal = journal)
		overlay = SwingAuthoring.put(overlay, overlay.applyTo(plain.baseRig.puppet), RigSwingEdit.single("front", "Front", SwingKind.VERTICAL,
			listOf(front), listOf("ParamSwingFront")))
		return config.copy(rigEdits = overlay, layerVisibility = mapOf("face" to false))
	}

	@Test fun anAuthoredRigBuildsTheSamePreviewWithoutGeneratingTheBase() {
		val pipeline = PSD2LivePipeline()
		val edited = edited(pipeline)
		val full = pipeline.buildPreview(source, edited)
		val key = assertNotNull(full.sources.bindingKey)
		val materialized = assertNotNull(PSD2LivePipeline().materializedPreview(source, edited, full.authored, key))
		assertEquals(hash(full), hash(materialized))
		assertEquals(full.rig.layerIdByDrawableId, materialized.rig.layerIdByDrawableId)
		assertEquals(full.rig.sourceBoundsByDrawableId, materialized.rig.sourceBoundsByDrawableId)
		assertFalse(materialized.sources.baseKnown, "the base is generated only when asked for")
		assertSame(full.authored, materialized.authored)
		// Asked for, the base is the one generation builds.
		assertEquals(ContentHash.of(PuppetIr.toIr(full.baseRig.puppet)), ContentHash.of(PuppetIr.toIr(materialized.baseRig.puppet)))
	}

	@Test fun anotherAtlasRefusesTheStoredRig() {
		val pipeline = PSD2LivePipeline()
		val full = pipeline.buildPreview(source, config)
		assertNull(pipeline.materializedPreview(source, config.copy(atlasSize = 1024), full.authored, assertNotNull(full.sources.bindingKey)))
	}
}
