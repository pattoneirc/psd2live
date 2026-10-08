package io.github.psd2live

import io.github.psd2live.testing.MESSAGE_BUNDLES
import io.github.psd2live.testing.messageBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliOptionsTest {
	/** Options main handles before parsing. */
	private val handledFirst = setOf("--clear-user-data", "--help")

	@Test
	fun everyOptionTheUsageListsIsAccepted() {
		for (bundle in MESSAGE_BUNDLES) {
			val usage = messageBundle(bundle).getProperty("cli.usage")
			val listed = Regex("--[a-z0-9-]+").findAll(usage).map { it.value }.toSet() - handledFirst
			assertTrue(listed.isNotEmpty(), bundle)
			for (name in listed) assertTrue(name in CliOptions.flagNames || name in CliOptions.valueNames, "$bundle lists $name")
		}
	}

	@Test
	fun meshPixelsIsAFlag() {
		val options = CliOptions.parse(arrayOf("--input", "a.psd", "--mesh-pixels", "--mesh-spacing", "40"))
		assertEquals(setOf("--mesh-pixels"), options.flags)
		assertEquals(40, options.int("--mesh-spacing", 64))
	}
}
