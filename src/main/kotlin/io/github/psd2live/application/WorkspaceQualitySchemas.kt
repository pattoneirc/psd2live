package io.github.psd2live.application

import io.github.psd2live.core.GeneratedOverrideIssueKind
import io.github.psd2live.core.GeneratedOverrideOrphanReason
import io.github.psd2live.core.quality.GeneratedOverrideQuality
import io.github.psd2live.core.quality.GeneratedOverrideRule

/** Strict transport contract of the observation quality reports read through workspace_inspect. */
internal object WorkspaceQualitySchemas {
    private val s = WorkspaceResultSchema
    private val evidence = s.obj(mapOf(
        "kind" to s.choices(*GeneratedOverrideIssueKind.entries.map { it.wire }.toTypedArray()),
        "generator" to s.string(), "key" to s.dictionary(s.number()),
        "points" to s.integer(0), "total" to s.integer(0),
        "reason" to s.choices(*GeneratedOverrideOrphanReason.entries.map { it.wire }.toTypedArray()),
    ), setOf("kind", "generator", "key", "points", "total"))
    private val finding = s.obj(mapOf(
        "code" to s.choices(*GeneratedOverrideRule.entries.map { it.name }.toTypedArray()),
        "severity" to s.choices("info", "warning", "error"), "category" to s.choices("quality", "validity", "coverage"),
        "domain" to s.constant(GeneratedOverrideQuality.DOMAIN), "target" to s.handle(), "evidence" to evidence,
    ))
    private val check = s.obj(mapOf("id" to s.handle(), "scope" to s.string(), "complete" to s.boolean()))

    /** [GeneratedOverrideQuality.report]. */
    val generatedOverrides = s.obj(mapOf(
        "version" to s.integer(GeneratedOverrideQuality.VERSION, GeneratedOverrideQuality.VERSION),
        "domain" to s.constant(GeneratedOverrideQuality.DOMAIN), "fence" to s.constant("observation"),
        "decision" to s.choices("accept", "accept_with_diagnostics"), "can_proceed" to s.constant(true),
        "complete" to s.boolean(), "scope" to s.string(), "checks" to s.array(check, 1, 1), "findings" to s.array(finding),
    ))
}
