package io.github.psd2live.core

import io.github.psd2live.format.model.ExpressionBlend
import io.github.psd2live.format.model.ExpressionIR
import io.github.psd2live.format.model.ExpressionParameter
import io.github.psd2live.format.model.HitAreaIR
import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.PuppetModel

/**
 * What a runtime host uses to make a generated rig interactive, derived from the rig itself: a set of facial
 * expressions over the standard face parameters, and hit areas on the head and the body.
 */
internal object RuntimeExtras {
	private class Preset(val id: String, val nameKey: String, val parameters: List<ExpressionParameter>)

	private val presets = listOf(
		Preset("smile", "model.expression.smile", listOf(
			ExpressionParameter(StandardParameters.MOUTH_FORM.raw, ExpressionBlend.OVERWRITE, 1f),
			ExpressionParameter(StandardParameters.EYE_L_OPEN.raw, ExpressionBlend.MULTIPLY, 0.75f),
			ExpressionParameter(StandardParameters.EYE_R_OPEN.raw, ExpressionBlend.MULTIPLY, 0.75f),
			ExpressionParameter(StandardParameters.BROW_L_Y.raw, ExpressionBlend.ADD, 0.3f),
			ExpressionParameter(StandardParameters.BROW_R_Y.raw, ExpressionBlend.ADD, 0.3f),
		)),
		Preset("sad", "model.expression.sad", listOf(
			ExpressionParameter(StandardParameters.MOUTH_FORM.raw, ExpressionBlend.OVERWRITE, -0.8f),
			ExpressionParameter(StandardParameters.EYE_L_OPEN.raw, ExpressionBlend.MULTIPLY, 0.85f),
			ExpressionParameter(StandardParameters.EYE_R_OPEN.raw, ExpressionBlend.MULTIPLY, 0.85f),
			ExpressionParameter(StandardParameters.BROW_L_Y.raw, ExpressionBlend.ADD, -0.5f),
			ExpressionParameter(StandardParameters.BROW_R_Y.raw, ExpressionBlend.ADD, -0.5f),
		)),
		Preset("angry", "model.expression.angry", listOf(
			ExpressionParameter(StandardParameters.MOUTH_FORM.raw, ExpressionBlend.OVERWRITE, -0.5f),
			ExpressionParameter(StandardParameters.EYE_L_OPEN.raw, ExpressionBlend.MULTIPLY, 0.9f),
			ExpressionParameter(StandardParameters.EYE_R_OPEN.raw, ExpressionBlend.MULTIPLY, 0.9f),
			ExpressionParameter(StandardParameters.BROW_L_Y.raw, ExpressionBlend.ADD, -0.8f),
			ExpressionParameter(StandardParameters.BROW_R_Y.raw, ExpressionBlend.ADD, -0.8f),
		)),
		Preset("surprised", "model.expression.surprised", listOf(
			ExpressionParameter(StandardParameters.EYE_L_OPEN.raw, ExpressionBlend.OVERWRITE, 1f),
			ExpressionParameter(StandardParameters.EYE_R_OPEN.raw, ExpressionBlend.OVERWRITE, 1f),
			ExpressionParameter(StandardParameters.BROW_L_Y.raw, ExpressionBlend.ADD, 0.8f),
			ExpressionParameter(StandardParameters.BROW_R_Y.raw, ExpressionBlend.ADD, 0.8f),
			ExpressionParameter(StandardParameters.MOUTH_OPEN.raw, ExpressionBlend.OVERWRITE, 0.6f),
		)),
	)

	/** The standard expressions over the parameters [model] has; one with none of them is left out. Half-second fades, as Cubism's default. */
	fun expressions(model: PuppetModel): List<ExpressionIR> {
		val present = model.parameters.mapTo(HashSet()) { it.id.raw }
		return presets.mapNotNull { preset ->
			preset.parameters.filter { it.parameter in present }.takeIf { it.isNotEmpty() }?.let { ExpressionIR(preset.id, tr(preset.nameKey), 0.5f, 0.5f, it) }
		}
	}

	/** Head: the face. Body: the top, else the neck or the bottom. Each only where the rig draws such a mesh. */
	fun hitAreas(model: PuppetModel, analysis: PipelineAnalysis, layerIdByDrawable: Map<String, String>): List<HitAreaIR> {
		val layers = analysis.layers.associateBy { it.source.id.raw }
		fun meshes(vararg tags: SemanticTag): List<String> {
			for (tag in tags) {
				val found = model.drawables.filter { d ->
					d.mesh != null && d.isVisible && layers[layerIdByDrawable[d.id.raw] ?: d.id.raw]?.semantic?.tag == tag
				}.map { it.id.raw }
				if (found.isNotEmpty()) return found
			}
			return emptyList()
		}
		return listOfNotNull(
			meshes(SemanticTag.FACE).takeIf { it.isNotEmpty() }?.let { HitAreaIR("HitAreaHead", tr("model.hitArea.head"), it) },
			meshes(SemanticTag.TOPWEAR, SemanticTag.NECK, SemanticTag.BOTTOMWEAR).takeIf { it.isNotEmpty() }?.let { HitAreaIR("HitAreaBody", tr("model.hitArea.body"), it) },
		)
	}
}
