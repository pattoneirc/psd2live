package io.github.psd2live.tools

import io.github.psd2live.core.ExportService
import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.SkeletonAutoBuilder
import io.github.psd2live.format.compile.ClipSampler
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exports the samples to DragonBones and plays them with the official DragonBones 5.7 runtime core
 * (tools/dragonbones-check, needs node and npm): every parameter animation at every frame it keys must
 * match the editor's evaluator at that parameter value, and clips at sampled times.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*DragonBonesFidelityTool'
 * Writes build/tools/dragonbones-fidelity/report.txt.
 */
class DragonBonesFidelityTool {
	@Test fun measure() {
		requireTools()
		val out = output("dragonbones-fidelity")
		val checker = File("tools/dragonbones-check")
		val windows = System.getProperty("os.name").startsWith("Windows")
		val npm = ProcessBuilder(if (windows) listOf("cmd", "/c", "npm", "install", "--no-audit", "--no-fund") else listOf("npm", "install", "--no-audit", "--no-fund"))
			.directory(checker).inheritIO().start()
		check(npm.waitFor() == 0) { "npm install failed" }
		val report = StringBuilder()
		for (sample in listOf("tml", "ds")) {
			val plain = PSD2LivePipeline().buildPreview(File("examples/$sample/psd-input/$sample.psd").toPath())
			val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
				rigEdits = plain.config.rigEdits.copy(skeleton = SkeletonAutoBuilder.build(plain.analysis, plain.rig))))
			for ((variant, preview) in listOf("plain" to plain, "skeleton" to skeletal)) {
				val dir = File(out, "$sample-$variant").apply { deleteRecursively(); mkdirs() }
				val target = ExportService.registry(preview.config)["dragonbones"]
				val exported = ExportService.export(preview, target, ExportService.options(target, sample, preview.config), dir.toPath())
				// Animations over DragonBones' data limit were reduced at a larger tolerance, reported per animation.
				val tolerances = exported.losses.filter { it.objectId.startsWith("param/") && it.error != null }.associate { it.objectId to it.error!! }
				val ir = RigIrCompiler.compile(preview)
				val frameRate = 60
				// Every parameter at the frames holding its keys (the key's value baked there), and clips at a few times.
				data class Probe(val animation: String, val progress: Float, val pose: Map<String, Float>)
				val probes = ArrayList<Probe>()
				for (axis in io.github.psd2live.format.compile.ParameterBake.axes(ir, linkedPairs = false)) {
					val p = axis.x
					for (key in axis.xKeys) {
						val frame = ((key - p.min) / (p.max - p.min) * frameRate).roundToInt()
						probes += Probe("param/${p.id}", frame / frameRate.toFloat(), mapOf(p.id to key))
					}
				}
				for (clip in ir.clips) for (fraction in listOf(0.25f, 0.5f)) {
					val frames = (clip.duration * frameRate).roundToInt().coerceAtLeast(1)
					val frame = (frames * fraction).roundToInt()
					probes += Probe("clip/${clip.name}", frame / frames.toFloat(), ClipSampler.valuesAt(clip, frame / frameRate.toFloat()))
				}
				val spec = File(dir, "spec.txt").apply { writeText(probes.joinToString("") { "${it.animation}\t${it.progress}\n" }) }
				val command = listOf("node", File(checker, "check.mjs").absolutePath, dir.absolutePath, sample, spec.absolutePath)
				val process = ProcessBuilder(if (windows) listOf("cmd", "/c") + command else command).redirectErrorStream(true).start()
				val lines = process.inputStream.bufferedReader().readLines()
				check(process.waitFor() == 0) { "The DragonBones runtime failed: ${lines.takeLast(8)}" }
				val cx = ir.canvas.width / 2f; val bottom = ir.canvas.height
				val parsed = ArrayList<Map<String, FloatArray>>()
				var current: MutableMap<String, FloatArray>? = null
				for (line in lines) {
					val f = line.split('\t')
					when (f[0]) {
						"sample" -> { current = LinkedHashMap(); parsed += current }
						"slot" -> current!![f[1]] = f[2].split(' ').map { it.toFloat() }.toFloatArray()
					}
				}
				check(parsed.size == probes.size) { "Expected ${probes.size} samples, got ${parsed.size}: ${lines.take(4)}" }
				val animations = lines.first { it.startsWith("animations\t") }.split('\t')[1]
				var worstParameter = 0f; var worstClip = 0f; var worstAt = ""; var violation = ""
				IrGeometryEvaluator.open(ir).use { editor ->
					for ((probe, slots) in probes.zip(parsed)) {
						val want = editor.evaluate(probe.pose)
						for ((mesh, points) in slots) {
							val expected = want.positions[mesh] ?: continue
							var error = 0f
							for (i in points.indices) error = max(error, abs(points[i] + (if (i % 2 == 0) cx else bottom) - expected[i]))
							if (probe.animation.startsWith("param/")) {
								if (error > worstParameter) { worstParameter = error; worstAt = "${probe.animation} @ ${probe.progress} $mesh" }
								if (error > max(0.25f, tolerances[probe.animation] ?: 0f) + 0.01f) violation = "$error px at ${probe.animation} @ ${probe.progress} $mesh"
							} else worstClip = max(worstClip, error)
						}
					}
				}
				report.appendLine("$sample-$variant: $animations animations, ${probes.size} samples; single parameter max ${"%.4f".format(worstParameter)} px ($worstAt); " +
					"${tolerances.size} animations reduced to fit; clips max ${"%.4f".format(worstClip)} px")
				// Single-parameter poses match at their key frames within the tolerance the export used for them.
				assertTrue(violation.isEmpty(), "$sample-$variant: $violation")
			}
		}
		File(out, "report.txt").writeText(report.toString())
		print(report)
	}
}
