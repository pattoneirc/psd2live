package io.github.psd2live.core

import io.github.psd2live.format.compile.TargetSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each export dialog builds its rows from the target's declared settings, so the declarations must be well formed. */
class ExportTargetSettingsTest {
	private val targets = ExportService.registry(PipelineConfig()).targets

	@Test fun declaredSettingsAreUniqueAndConsistent() {
		for (target in targets) {
			assertEquals(target.settings.map { it.key }.distinct(), target.settings.map { it.key }, target.id)
			for (setting in target.settings.filterIsInstance<TargetSetting.Number>()) {
				assertTrue(setting.min < setting.max && setting.step > 0, "${target.id}.${setting.key}")
				setting.default?.let { assertTrue(it in setting.min..setting.max, "${target.id}.${setting.key} default") }
			}
		}
	}
}
