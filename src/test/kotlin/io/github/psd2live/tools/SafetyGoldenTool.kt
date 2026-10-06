package io.github.psd2live.tools

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * Full geometry-safety reports for seeded random edits on the skeletal sample, so a change to the evaluator can
 * be checked against the previous commit report for report. Coverage is left out.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*SafetyGoldenTool'
 * Writes build/tools/safety-golden/<PSD2LIVE_GOLDEN_LABEL>.txt.
 */
class SafetyGoldenTool {
	@Test fun dump() {
		requireTools()
		val preview = build(Sample.fromEnvironment()).skeletal
		val overlay = preview.config.rigEdits
		val before = preview.rig.puppet
		val targets = before.drawables.filter { it.mesh != null }.map { "mesh" to it.id.raw } +
			before.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>().map { "warp" to it.id.raw }
		val random = Random(7)
		val out = StringBuilder()
		repeat(24) { round ->
			val (kind, id) = targets[random.nextInt(targets.size)]
			val points = RigGeometryTools.geometry(before, kind, id, emptyMap()).points.copyOf()
			val amplitude = listOf(0.3f, 40f, 400f)[round % 3]
			for (i in points.indices) points[i] += (random.nextFloat() - 0.5f) * amplitude
			val command = buildJsonObject {
				put("op", "canvas_geometry"); put("kind", kind); put("id", id)
				put("key", JsonObject(emptyMap())); put("pose", JsonObject(emptyMap()))
				if (kind == "mesh") put("preserve_image", true)
				put("points", JsonArray(points.map(::JsonPrimitive)))
			}
			val journal = runCatching { RigAuthoringJournal.compile(before, JsonArray(listOf(command))).second }.getOrNull() ?: return@repeat
			val candidate = preview.baseRig.withRigEdits(overlay.copy(authoringJournal = overlay.authoringJournal + journal),
				preview.config.layerVisibility, preview.config.drawOrderOverrides).puppet
			for (block in listOf(false, true)) {
				val start = System.nanoTime()
				val report = GeometrySafetyEvaluator.evaluate(before, candidate, blockFoldovers = block)
				val ms = (System.nanoTime() - start) / 1e6
				out.appendLine("$round $kind:$id block=$block " + JsonObject(report.toJson().filterKeys { it != "coverage" }))
				println("safety-golden: $round $kind:$id block=$block %.1f ms safe=${report.safe} affected=${report.affectedTargets.size}".format(ms))
			}
		}
		File(output("safety-golden"), setting("PSD2LIVE_GOLDEN_LABEL", "current") + ".txt").writeText(out.toString())
	}
}
