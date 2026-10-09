package io.github.psd2live.tools

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.PrimitiveSkins
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.VertexGroupJournal
import io.github.psd2live.core.sim.ModelPresets
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimAuthoring
import io.github.psd2live.core.sim.SimKind
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind

/** One simulation of a sample, set up as the app's presets set it up, ready to bake and play. */
internal class SimBenchPart(val name: String, val overlay: RigEditOverlay, val base: PuppetModel, val skins: PrimitiveSkins, val edit: RigSimEdit) {
	/** The rig without this simulation's bake: what the bake and the reference simulation read. */
	val model: PuppetModel by lazy { SimAuthoring.unbakedModel(overlay, base, edit.id, skins) }

	/** This part with [edit] in place of its simulation. */
	fun with(edit: RigSimEdit) = SimBenchPart(name, overlay.copy(simEdits = overlay.simEdits.map { if (it.id == edit.id) edit else it }), base, skins, edit)
}

/**
 * The parts the bake is judged on: the presets' back hair, front hair (bangs) and each loose garment, and the back
 * hair pinned along its top tenth as the earlier benchmark set it up, for numbers comparable with SIMULATION.md.
 */
internal object SimBenchParts {
	fun load(sample: Sample): List<SimBenchPart> {
		val preview = PSD2LivePipeline().buildPreview(sample.path, PipelineConfig(hairSimulationFront = true, hairSimulationBack = true))
		val base = preview.baseRig
		val parts = ArrayList<SimBenchPart>()
		var overlay = preview.config.rigEdits
		val named = ArrayList<Pair<String, String>>()
		for (preset in listOf(ModelPresets.Preset.BACK_HAIR, ModelPresets.Preset.FRONT_HAIR, ModelPresets.Preset.CLOTHING)) {
			val applied = runCatching {
				ModelPresets.apply(overlay, overlay.applyTo(base.puppet, base.primitiveSkins), preview.analysis, preview.rig.layerIdByDrawableId, preset)
			}.getOrElse { println("sim-bench: ${preset.jsonName} skipped: ${it.message}"); continue }
			overlay = applied.overlay
			for (id in applied.simulationIds) named += when (id) {
				ModelPresets.BACK_HAIR_SIM -> "back_hair" to id
				ModelPresets.FRONT_HAIR_SIM -> "front_hair" to id
				else -> id.removePrefix("preset_") to id
			}
		}
		for ((name, id) in named) parts += SimBenchPart(name, overlay, base.puppet, base.primitiveSkins, overlay.simEdits.single { it.id == id })

		// The back hair pinned along its top tenth, alone on the plain rig.
		val puppet = preview.rig.puppet
		val layers = preview.analysis.layers.associateBy { it.source.id.raw }
		val back = puppet.drawables.firstOrNull { d ->
			d.mesh != null && layers[preview.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == SemanticTag.BACK_HAIR
		}
		if (back != null) {
			val world = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(back.id)
			val ys = (1 until world.size step 2).map { world[it] }
			val top = ys.max(); val bottom = ys.min()
			val pin = VertexGroup("pin", back.id, VertexGroupKind.PIN, FloatArray(world.size / 2) { if (world[it * 2 + 1] > top - (top - bottom) * 0.1f) 1f else 0f })
			val plain = preview.config.rigEdits.let { it.copy(authoringJournal = it.authoringJournal + VertexGroupJournal.encode(pin)) }
			val grouped = plain.applyTo(base.puppet, base.primitiveSkins)
			val available = grouped.parameters.mapTo(HashSet()) { it.id.raw }
			val put = SimAuthoring.put(plain, grouped, RigSimEdit("back10", "Back hair", SimKind.HAIR, listOf(back.id.raw), modes = 2, keys = 5,
				inputs = RigSimEdit.defaultInputs(available, SimKind.HAIR)))
			parts += SimBenchPart("back_hair_top10", put, base.puppet, base.primitiveSkins, put.simEdits.single { it.id == "back10" })
		}
		return parts
	}
}
