package io.github.psd2live.core

import io.github.psd2live.format.model.ExpressionBlend
import org.umamo.runtime.model.PuppetModel
import kotlin.test.*

class RuntimeExtrasTest {
	private fun model(parameters: List<org.umamo.runtime.model.Parameter>) =
		PuppetModel(parameters, emptyList(), emptyList(), emptyList(), emptyList(), null)

	@Test fun expressionsUseTheStandardParametersTheRigHas() {
		val all = RuntimeExtras.expressions(model(StandardParameters.all))
		assertEquals(listOf("smile", "sad", "angry", "surprised"), all.map { it.id })
		assertTrue(all.all { it.fadeIn > 0f && it.fadeOut > 0f && it.name.isNotBlank() })
		val smile = all.first { it.id == "smile" }.parameters.associate { it.parameter to it.blend }
		assertEquals(ExpressionBlend.OVERWRITE, smile.getValue(StandardParameters.MOUTH_FORM.raw))
		assertEquals(ExpressionBlend.MULTIPLY, smile.getValue(StandardParameters.EYE_L_OPEN.raw))

		// Only the mouth: the expressions keep what they can set, and one that sets nothing there is left out.
		val mouth = RuntimeExtras.expressions(model(StandardParameters.all.filter { it.id == StandardParameters.MOUTH_FORM }))
		assertEquals(listOf("smile", "sad", "angry"), mouth.map { it.id })
		assertTrue(mouth.all { e -> e.parameters.all { it.parameter == StandardParameters.MOUTH_FORM.raw } })
		assertTrue(RuntimeExtras.expressions(model(emptyList())).isEmpty())
	}
}
