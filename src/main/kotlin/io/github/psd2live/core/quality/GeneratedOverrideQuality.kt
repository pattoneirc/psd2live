package io.github.psd2live.core.quality

import io.github.psd2live.core.GeneratedOverrideIssue
import io.github.psd2live.core.GeneratedOverrideIssueKind
import kotlinx.serialization.json.*

/**
 * Stable codes for generated-override findings. The rule alone decides severity and category; callers and the
 * UI read the code, never a localized message.
 */
enum class GeneratedOverrideRule(val severity: String, val category: String) {
	/** The generator and the user's override both moved some points; the user's values were kept. */
	GENERATED_OVERRIDE_CONFLICT("warning", "quality"),
	/** The generated keyform the override edits is gone or reshaped; the override has no effect. */
	GENERATED_OVERRIDE_ORPHANED("warning", "quality"),
	;

	companion object {
		fun of(issue: GeneratedOverrideIssue): GeneratedOverrideRule = when (issue.kind) {
			GeneratedOverrideIssueKind.CONFLICT -> GENERATED_OVERRIDE_CONFLICT
			GeneratedOverrideIssueKind.ORPHANED -> GENERATED_OVERRIDE_ORPHANED
		}
	}
}

/**
 * Observation report for generated overrides (report version 2): one check, one finding per override record that
 * did not apply as recorded, in journal order. Warnings never block, so `can_proceed` is always true; a project
 * without overrides yields a complete report with no findings.
 */
object GeneratedOverrideQuality {
	const val VERSION = 2
	const val DOMAIN = "overrides"
	const val CHECK_ID = "generated_overrides"
	const val SCOPE = "Edits of swing and baked-simulation keyforms, merged per control point or vertex with the current generation. " +
		"Only whether each override applied is checked, not how the merged shape looks."

	fun finding(issue: GeneratedOverrideIssue): JsonObject {
		val rule = GeneratedOverrideRule.of(issue)
		return buildJsonObject {
			put("code", rule.name)
			put("severity", rule.severity)
			put("category", rule.category)
			put("domain", DOMAIN)
			put("target", issue.target)
			putJsonObject("evidence") {
				put("kind", issue.kind.wire)
				put("generator", issue.generator)
				putJsonObject("key") { issue.key.toSortedMap().forEach { (id, value) -> put(id, value) } }
				put("points", issue.points)
				put("total", issue.total)
				issue.reason?.let { put("reason", it.wire) }
			}
		}
	}

	fun report(issues: List<GeneratedOverrideIssue>): JsonObject = buildJsonObject {
		put("version", VERSION)
		put("domain", DOMAIN)
		put("fence", "observation")
		put("decision", if (issues.isEmpty()) "accept" else "accept_with_diagnostics")
		put("can_proceed", true)
		put("complete", true)
		put("scope", SCOPE)
		putJsonArray("checks") { add(buildJsonObject { put("id", CHECK_ID); put("scope", SCOPE); put("complete", true) }) }
		put("findings", JsonArray(issues.map(::finding)))
	}
}
