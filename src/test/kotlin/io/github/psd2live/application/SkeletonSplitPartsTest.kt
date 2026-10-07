package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Limbs split per side become journal records (art_primitive) that replay after the skeleton bakes the base rig;
 * the bake must still skin the parts its bones bind, so every leg bone parameter moves a leg.
 */
@org.junit.jupiter.api.Tag("slow")
class SkeletonSplitPartsTest {
	private val legRoles = setOf(BoneRole.THIGH, BoneRole.SHIN, BoneRole.FOOT)

	@Test fun bonesSkinTheSplitPartsOfLegsDrawnOnOneLayer() = runBlocking<Unit> {
		val builder = WorkspacePreviewBuilder()
		lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
		runtime = WorkspaceRuntime({ document -> builder.build(document, runtime.capture().model) })
		WorkspaceSourceImporter(runtime).importPsd(Path.of("examples/tml/psd-input/tml.psd").toAbsolutePath(), null, runtime.state.value.state)
		val start = runtime.capture()
		// The legs and the shoes are each one layer across both sides: the start screen splits them per side.
		val layers = start.model.analysis.layers.filter { it.semantic.tag == SemanticTag.LEGWEAR || it.semantic.tag == SemanticTag.FOOTWEAR }
			.map { it.source.id.raw }
		assertEquals(2, layers.size)
		val parted = WorkspacePartitionCommands(runtime).execute(start.projectId, start.state, layers.map { layer ->
			WorkspaceDocumentOperation("source_split_components", buildJsonObject {
				put("layer_id", layer); put("names", JsonArray(listOf("$layer L", "$layer R").map(::JsonPrimitive)))
				put("sides", JsonArray(listOf("left", "right").map(::JsonPrimitive)))
			})
		}, "Split", MutationAuthor.USER).commit.capture
		assertTrue(ArtPrimitiveJournal.commands(parted.document.rigEdits).size == 2)

		val document = WorkspaceDocumentEdits.skeleton(parted.document, parted.model, buildJsonObject { put("mode", "auto") })
		val spec = document.rigEdits.skeleton!!
		val legs = spec.bones.filter { it.role in legRoles }
		val parts = legs.flatMap { it.drawableIds }.toSet()
		assertEquals(4, parts.size, "each leg and each shoe binds its own part")
		val model = builder.build(document, parted.model)
		val puppet = model.rig.puppet
		// The split parts are not in the base rig; the bake skinned them and their records placed them.
		assertTrue(model.baseRig.puppet.drawables.none { it.id.raw in parts })
		assertEquals(parts, model.baseRig.primitiveSkins.drawables.keys.mapTo(HashSet()) { it.raw })
		val bones = puppet.deformers.filterIsInstance<Deformer.Rotation>().map { it.id.raw }.toSet()
		for (id in parts) {
			val parent = puppet.drawables.single { it.id.raw == id }.parentDeformerId!!.raw
			assertTrue(parent in bones || parent.removeSuffix("Stance") in bones, "$id hangs under $parent, not a bone")
		}

		// At rest skinning leaves the parts where the same skeleton without leg bindings has them; every leg bone
		// moves its leg.
		val evaluator = CpuDeformationEvaluator()
		val unboundLegs = spec.copy(bones = spec.bones.map { if (it.role in legRoles) it.copy(drawableIds = emptyList()) else it })
		val plain = builder.build(document.copy(rigEdits = document.rigEdits.copy(skeleton = unboundLegs)), parted.model)
		val before = evaluator.evaluate(plain.rig.puppet, emptyMap()).worldPositions
		val rest = evaluator.evaluate(puppet, emptyMap()).worldPositions
		for (id in parts) {
			val drawable = DrawableId(id)
			val drawn = bounds(before.getValue(drawable))
			val now = bounds(rest.getValue(drawable))
			for (i in drawn.indices) assertTrue(abs(drawn[i] - now[i]) < 2f, "$id moved at rest: ${drawn.toList()} -> ${now.toList()}")
		}
		for (bone in legs) {
			val parameter = puppet.parameters.single { it.id.raw == bone.parameterId }
			val posed = evaluator.evaluate(puppet, mapOf(ParameterId(bone.parameterId) to parameter.max * 0.25f)).worldPositions
			val moved = parts.filter { id ->
				val a = rest.getValue(DrawableId(id)); val b = posed.getValue(DrawableId(id))
				a.indices.any { abs(a[it] - b[it]) > 2f }
			}
			assertTrue(moved.isNotEmpty(), "${bone.parameterId} moves no leg")
		}

		// A build of the committed document from nothing gives the same rig.
		val fresh = WorkspacePreviewBuilder().build(document)
		assertEquals(ContentHash.of(RigIrCompiler.compile(fresh)), ContentHash.of(RigIrCompiler.compile(model)))
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
