package io.github.psd2live.tools

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimAuthoring
import io.github.psd2live.core.sim.SimBaker
import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.core.sim.SimKind
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.targets.cubism.PuppetIr
import kotlin.test.Test

/**
 * What each generator costs on a real rig against hashing its inputs, to decide which ones a generation cache
 * pays for (as [SwingCostTool] did for swings). Writes build/tools/generator-cost/report.txt.
 */
class GeneratorCostTool {
	private fun time(runs: Int = 10, block: () -> Unit): Double {
		repeat(2) { block() }
		val t = System.nanoTime(); repeat(runs) { block() }
		return (System.nanoTime() - t) / 1e6 / runs
	}

	@Test fun measure() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val built = build(sample)
		val plain = built.plain
		val skeletal = built.skeletal
		val pipeline = PSD2LivePipeline()
		val lines = ArrayList<String>()
		fun report(name: String, ms: Double) { lines += "%-44s %8.1f ms".format(name, ms); println(lines.last()) }

		// The rig builder with and without the skeleton stage, meshes cached as in the editor.
		report("rig builder, no skeleton", time(5) { RigBuilder.build(plain.analysis, plain.atlas, plain.config, pipeline.meshCache) })
		report("rig builder, with skeleton", time(5) { RigBuilder.build(skeletal.analysis, skeletal.atlas, skeletal.config, pipeline.meshCache) })
		val beforeSkeleton = RigBuilder.build(plain.analysis, plain.atlas, plain.config, pipeline.meshCache).puppet
		report("skeleton input hash (puppet IR + spec)", time { ContentHash.of(PuppetIr.toIr(beforeSkeleton), built.spec.toJson()) })
		report("full preview build, with skeleton", time(3) { pipeline.buildPreview(plain.analysis, skeletal.config) })

		// Generators that rerun on every replay: physics catalog and generated motions.
		val parameterIds = skeletal.rig.puppet.parameters.mapTo(linkedSetOf()) { it.id.raw }
		report("physics catalog", time { PhysicsCatalog.active(skeletal.analysis, skeletal.config, parameterIds) })
		report("physics input hash", time { ContentHash.of(skeletal.config.rigEdits.physicsEdits.map { it.toJson() }, parameterIds, skeletal.config) })
		report("generated motions (all presets)", time {
			for (name in listOf("Idle", "Blink", "Nod", "Shake") + SkeletonMotions.presets.map { it.name })
				MotionPresets.tracks(name, skeletal.config.rigEdits.skeleton, MotionPresetSettings(), emptySet())
		})
		report("rig IR compile (physics + motions + IR)", time(5) { RigIrCompiler.compile(skeletal) })

		// A baked simulation writes its keyforms on every replay.
		val puppet = plain.rig.puppet
		val layers = plain.analysis.layers.associateBy { it.source.id.raw }
		val back = puppet.drawables.first { d -> d.mesh != null && layers[plain.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == SemanticTag.BACK_HAIR }
		val world = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(back.id)
		val ys = (1 until world.size step 2).map { world[it] }
		val top = ys.max(); val bottom = ys.min()
		val pin = org.umamo.runtime.model.VertexGroup("pin", back.id, org.umamo.runtime.model.VertexGroupKind.PIN,
			FloatArray(world.size / 2) { if (world[it * 2 + 1] > top - (top - bottom) * 0.1f) 1f else 0f })
		val grouped = plain.config.rigEdits.copy(authoringJournal = plain.config.rigEdits.authoringJournal + VertexGroupJournal.encode(pin))
		val overlay = SimAuthoring.put(grouped, grouped.applyTo(plain.baseRig.puppet), RigSimEdit("back", "Back hair", SimKind.HAIR, listOf(back.id.raw), modes = 1, keys = 3,
			inputs = RigSimEdit.defaultInputs(puppet.parameters.mapTo(HashSet()) { it.id.raw }, SimKind.HAIR)))
		val bakeStart = System.nanoTime()
		val bake = SimBaker.bake(SimAuthoring.unbakedModel(overlay, plain.baseRig.puppet, "back"), overlay.simEdits.single())
		report("simulation bake (explicit, not replayed)", (System.nanoTime() - bakeStart) / 1e6)
		val baked = SimAuthoring.withBake(overlay, "back", bake)
		val beforeSim = baked.copy(simEdits = emptyList()).applyTo(plain.baseRig.puppet)
		report("simulation generator", time { SimGenerator.apply(beforeSim, baked.simEdits) })
		report("simulation input hash (bake JSON only)", time { ContentHash.of(baked.simEdits.map { it.toJson() }) })
		report("whole overlay replay with the simulation", time { baked.applyTo(plain.baseRig.puppet) })

		val text = "sample ${sample.name}\n" + lines.joinToString("\n")
		output("generator-cost").resolve("report.txt").writeText(text)
		println(text)
	}
}
