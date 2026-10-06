package io.github.psd2live.core

import io.github.psd2live.format.compile.TargetSetting
import io.github.psd2live.ui.components.experimentalExportTargets
import io.github.psd2live.ui.components.exportMenuGroups
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * File > Export as lists the neutral targets by group and each dialog builds its rows from the target's declared
 * settings, so every target needs a place in the menu and every row a label in every language.
 */
class ExportTargetSettingsTest {
	private val targets = ExportService.registry(PipelineConfig()).targets
	private val offered = targets.filter { it.id != "moc3" && it.id != "cmo3" }

	@Test fun everyOfferedTargetIsInExactlyOneMenuGroupOrExperimental() {
		val listed = exportMenuGroups.flatMap { it.second } + experimentalExportTargets
		assertEquals(listed.distinct(), listed)
		assertEquals(offered.map { it.id }.sorted(), listed.sorted())
	}

	@Test fun everyOfferedTargetAndSettingHasALabelInEveryLanguage() {
		val keys = offered.filter { it.id !in experimentalExportTargets }.flatMap { target ->
			listOf("export.target.${target.id}", "export.menu.${target.id}") + target.settings.map { "export.setting.${it.key}" }
		}.toSet() + exportMenuGroups.map { it.first } + listOf("baked", "approximated", "dropped").map { "export.loss.$it" }
		for (suffix in listOf("", "_zh_CN", "_ja")) {
			val messages = Properties().apply {
				ExportTargetSettingsTest::class.java.getResourceAsStream("/i18n/Messages$suffix.properties")!!.reader(Charsets.UTF_8).use(::load)
			}
			assertEquals(emptyList(), keys.filter { messages.getProperty(it).isNullOrBlank() }, "Messages$suffix")
		}
	}

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
