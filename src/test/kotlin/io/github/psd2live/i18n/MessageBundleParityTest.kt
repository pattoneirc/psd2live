package io.github.psd2live.i18n

import io.github.psd2live.testing.MESSAGE_BUNDLES
import io.github.psd2live.testing.messageBundle
import io.github.psd2live.testing.missingMessages
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.state.ShortcutAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every locale has the base bundle's keys, each with a value; the other catalog tests check the base bundle only. */
class MessageBundleParityTest {
    private val bundles = MESSAGE_BUNDLES.associateWith(::messageBundle)

    @Test fun everyLocaleHasEveryKeyWithAValue() {
        val reference = bundles.getValue("Messages").stringPropertyNames()
        for ((name, props) in bundles) {
            val keys = props.stringPropertyNames()
            assertEquals(emptySet(), reference - keys, "$name is missing keys")
            assertEquals(emptySet(), keys - reference, "$name has keys the English bundle lacks")
            val blank = keys.filter { props.getProperty(it).isBlank() }
            assertTrue(blank.isEmpty(), "$name has blank values: $blank")
        }
    }

    // The tool options' names are ToolOptionCatalogTest's, which walks the catalog in every tool state.
    @Test fun toolsAndShortcutsAreNamed() {
        val keys = CanvasTool.entries.map { "editor.tool.${it.name.lowercase()}" } + ShortcutAction.entries.map { it.labelKey }
        assertEquals(emptyList(), missingMessages(keys))
    }
}
