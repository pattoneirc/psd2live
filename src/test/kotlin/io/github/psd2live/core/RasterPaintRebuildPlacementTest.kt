package io.github.psd2live.core

import io.github.psd2live.project.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.*

/**
 * A paint mesh rebuild puts every vertex where its texel lies on the canvas at rest, whatever the drawable
 * hangs under: a rotation (a skeleton bone) or a warp whose lattice no generated frame describes.
 */
class RasterPaintRebuildPlacementTest {
	private fun layer(id: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(
		LayerId(id), id, "", SourceLayerKind.Raster, true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) -1 else 90 }),
		null, null, false,
	)

	private fun image(bounds: LayerBounds) = BufferedImage(96, 80, BufferedImage.TYPE_INT_ARGB).also { result ->
		result.createGraphics().let { graphics ->
			try { graphics.color = Color(30, 120, 220, 255); graphics.fillRect(bounds.left, bounds.top, bounds.width, bounds.height) }
			finally { graphics.dispose() }
		}
	}

	private fun drawable(preview: RigPreviewModel, layer: String) =
		preview.rig.puppet.drawables.single { preview.rig.layerIdByDrawableId[it.id.raw] == layer }

	/** The two meshes re-homed under a rotation and a sheared warp, each still on its pixels at rest. */
	private fun rehomed(preview: RigPreviewModel): RigPreviewModel {
		val puppet = preview.rig.puppet
		val rest = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions
		val rotation = Deformer.Rotation(DeformerId("bone"), "Bone", null, null, 0f,
			KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(30f, -20f, 30f, 1f)))))
		val warp = Deformer.Warp(DeformerId("sheared"), "Sheared", null, null, 1, 1, true,
			KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(floatArrayOf(30f, -30f, 100f, -40f, 40f, -90f, 110f, -85f))))))
		val parents = mapOf(drawable(preview, "art0").id to rotation.id, drawable(preview, "art1").id to warp.id)
		var model = puppet.copy(deformers = puppet.deformers + rotation + warp)
		for ((id, parent) in parents) {
			val moved = model.drawables.single { it.id == id }.copy(parentDeformerId = parent)
			val canvas = rest.getValue(id).let { world -> FloatArray(world.size) { if (it % 2 == 1) -world[it] else world[it] } }
			val mesh = requireNotNull(moved.mesh)
			val local = requireNotNull(RasterMeshPlacement.underParent(model, moved, canvas))
			model = model.copy(drawables = model.drawables.map {
				if (it.id == id) moved.copy(mesh = DrawableMesh(local, mesh.uvs, mesh.indices)) else it
			})
		}
		return preview.copy(rig = preview.rig.copy(puppet = model))
	}

	private fun assertOnTexels(preview: RigPreviewModel, layer: String) {
		val drawable = drawable(preview, layer)
		val texels = RasterMeshJournal.TextureCoordinates(preview.rig.puppet, drawable).toCanvas(drawable.mesh!!.uvs)
		val world = CpuDeformationEvaluator().evaluate(preview.rig.puppet, emptyMap()).worldPositions.getValue(drawable.id)
		for (i in texels.indices) {
			val actual = if (i % 2 == 1) -world[i] else world[i]
			assertTrue(abs(actual - texels[i]) < 0.05f, "$layer vertex ${i / 2}: ${texels[i]} drawn at $actual")
		}
	}

	@Test fun aRebuiltMeshStaysOnItsTexelsUnderAnyParent() {
		val pipeline = PSD2LivePipeline()
		val before = rehomed(pipeline.buildPreview(WorkspaceSourceArt(96, 80, listOf(
			layer("art0", 0, LayerBounds(8, 8, 32, 24)), layer("art1", 1, LayerBounds(42, 36, 24, 24))), emptyList()),
			PipelineConfig(atlasSize = 256, meshSpacing = 8, meshOnly = true, exportMoc3 = false)))
		assertOnTexels(before, "art0"); assertOnTexels(before, "art1")
		for ((layer, bounds) in listOf("art0" to LayerBounds(4, 4, 40, 30), "art1" to LayerBounds(40, 30, 30, 34))) {
			val after = RasterPaintCommit.prepare(pipeline, before, layer, image(bounds), true)
			val prior = drawable(before, layer); val next = drawable(after, layer)
			assertEquals(prior.parentDeformerId, next.parentDeformerId)
			assertFalse(prior.mesh!!.positions.contentEquals(next.mesh!!.positions), "$layer was rebuilt")
			assertOnTexels(after, layer)
		}
	}
}
