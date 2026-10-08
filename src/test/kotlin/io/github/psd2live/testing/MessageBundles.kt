package io.github.psd2live.testing

import java.util.Properties

/** The message bundles under `i18n/`, the base (English) one first. */
val MESSAGE_BUNDLES = listOf("Messages", "Messages_zh_CN", "Messages_ja", "Messages_ko")

/** One message bundle as its file holds it, without the fallback to the base bundle the app's lookup has. */
fun messageBundle(name: String = "Messages"): Properties = Properties().also { props ->
	MemoryPreferencesFactory::class.java.getResourceAsStream("/i18n/$name.properties")!!.reader(Charsets.UTF_8).use(props::load)
}

/**
 * The [keys] the base bundle lacks or leaves blank. MessageBundleParityTest holds every locale to the base bundle's
 * keys, each with a value, so a key the base bundle has is in all of them.
 */
fun missingMessages(keys: Iterable<String>): List<String> {
	val base = messageBundle()
	return keys.filter { base.getProperty(it).isNullOrBlank() }
}
