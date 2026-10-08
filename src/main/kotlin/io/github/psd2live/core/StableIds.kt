package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ContentHash
import kotlinx.serialization.json.JsonObject

/**
 * IDs for the objects a request creates without naming them, made from the request itself rather than drawn at
 * random: the same request on the same document gets the same IDs, so a retry, a dry run and its commit agree and a
 * caller can work them out in advance. [of] digests what the object is made from under a readable prefix; only when
 * that ID is already taken does a counter follow it. IDs already recorded in a journal or document stay as they are.
 */
internal object StableIds {
	/** Request fields that say how or when it was sent, not what it makes. */
	private val envelope = setOf("state", "project_id", "request_id", "task_id", "expected_state")

	/** [prefix] and a digest of [inputs]: the same inputs give the same stem. */
	fun stem(prefix: String, vararg inputs: Any?): String = prefix + ContentHash.of(*inputs).take(12)

	/** [stem], or [stem] with the first counter `_2`, `_3`, ... that [taken] does not hold. */
	fun fresh(stem: String, taken: (String) -> Boolean): String =
		generateSequence(1) { it + 1 }.map { if (it == 1) stem else "${stem}_$it" }.first { !taken(it) }

	/** A fresh ID under [prefix] for the object [role] that [request] makes, from the request without its envelope and [ignore]d fields. */
	fun of(prefix: String, request: JsonObject, role: String = "", ignore: Set<String> = emptySet(), taken: (String) -> Boolean): String =
		fresh(stem(prefix, role, JsonObject(request.filterKeys { it !in envelope && it !in ignore })), taken)
}
