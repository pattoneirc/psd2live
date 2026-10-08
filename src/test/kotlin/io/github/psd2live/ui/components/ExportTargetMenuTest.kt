package io.github.psd2live.ui.components

import io.github.psd2live.core.ExportService
import io.github.psd2live.core.PipelineConfig
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * File > Export as lists the neutral targets by group and each dialog builds its rows from the target's declared
 * settings, so every target needs a place in the menu and every row a label in every language.
 */
class ExportTargetMenuTest {
	private val offered = ExportService.registry(PipelineConfig()).targets.filter { it.id != "moc3" && it.id != "cmo3" }

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
				ExportTargetMenuTest::class.java.getResourceAsStream("/i18n/Messages$suffix.properties")!!.reader(Charsets.UTF_8).use(::load)
			}
			assertEquals(emptyList(), keys.filter { messages.getProperty(it).isNullOrBlank() }, "Messages$suffix")
		}
	}
}
