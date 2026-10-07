package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.SourceArt
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A mesh the skeleton re-homed under a bone lives in that bone's rotation space. Repainting it with a mesh
 * rebuild places the new mesh through the bone's transform, so it stays on its pixels at rest and in a pose
 * instead of collapsing onto the joint.
 */
@org.junit.jupiter.api.Tag("slow")
class SkeletonPaintRebuildTest {
	@Test fun aRebuiltMeshUnderABoneKeepsItsPlace() = runBlocking<Unit> {
		val builder = WorkspacePreviewBuilder()
		lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
		runtime = WorkspaceRuntime({ document -> builder.build(document, runtime.capture().model) })
		WorkspaceSourceImporter(runtime).importPsd(Path.of("examples/tml/psd-input/tml.psd").toAbsolutePath(), null, runtime.state.value.state)
		val start = runtime.capture()
		val document = WorkspaceDocumentEdits.skeleton(start.document, start.model, buildJsonObject { put("mode", "auto") })
		val model = builder.build(document, start.model)
		val puppet = model.rig.puppet
		val spec = document.rigEdits.skeleton!!
		val rotations = puppet.deformers.filterIsInstance<Deformer.Rotation>().mapTo(HashSet()) { it.id }
		val arms = spec.bones.flatMap { it.drawableIds }.distinct()
			.map { id -> puppet.drawables.single { it.id.raw == id } }
			.filter { it.parentDeformerId in rotations }
		assertTrue(arms.isNotEmpty(), "the skeleton homes a mesh under a bone")

		val evaluator = CpuDeformationEvaluator()
		for (arm in arms) {
			val layerId = model.rig.layerIdByDrawableId.getValue(arm.id.raw)
			val layer = model.analysis.source.layers.single { it.id.raw == layerId }
			val image = PreviewRenderer.composite(object : SourceArt by model.analysis.source { override val layers = listOf(layer) })
			val painted = WorkspaceRasterEdits.prepare(document, model, WorkspacePaintRaster.capture(layerId, image, rebuildMesh = true))
			assertTrue(painted.rigEdits.authoringJournal.any {
				it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP && it["id"]?.jsonPrimitive?.content == arm.id.raw
			}, "${arm.id.raw} records its rebuilt mesh")
			val rebuilt = builder.build(painted, model)
			val bones = spec.bones.filter { arm.id.raw in it.drawableIds }
			val poses = listOf(emptyMap<ParameterId, Float>()) + bones.map { bone ->
				val parameter = puppet.parameters.single { it.id.raw == bone.parameterId }
				mapOf(parameter.id to parameter.max * 0.5f)
			}
			for (pose in poses) {
				val before = bounds(evaluator.evaluate(puppet, pose).worldPositions.getValue(arm.id))
				val after = bounds(evaluator.evaluate(rebuilt.rig.puppet, pose).worldPositions.getValue(arm.id))
				for (i in before.indices) assertTrue(abs(before[i] - after[i]) < 3f,
					"${arm.id.raw} at $pose: ${before.toList()} -> ${after.toList()}")
			}
			// The record replays onto a build of the document from nothing.
			val fresh = WorkspacePreviewBuilder().build(painted)
			kotlin.test.assertEquals(ContentHash.of(RigIrCompiler.compile(fresh)), ContentHash.of(RigIrCompiler.compile(rebuilt)))
		}
	}

	private fun bounds(points: FloatArray): FloatArray {
		var left = Float.MAX_VALUE; var top = Float.MAX_VALUE; var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
		for (i in points.indices step 2) {
			left = minOf(left, points[i]); right = maxOf(right, points[i])
			top = minOf(top, points[i + 1]); bottom = maxOf(bottom, points[i + 1])
		}
		return floatArrayOf(left, top, right, bottom)
	}
}
