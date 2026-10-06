package io.github.psd2live.tools

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.targets.cubism.PuppetIr
import java.nio.file.Path
import kotlin.test.Test

/** How long the swing generator takes on a real rig, against hashing its inputs, to size a generation cache. */
class SwingCostTool {
	@Test fun measure() {
		requireTools()
		val initial = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val puppet = initial.rig.puppet
		val layers = initial.analysis.layers.associateBy { it.source.id.raw }
		fun meshOf(tag: SemanticTag) = puppet.drawables.first { d -> d.mesh != null && layers[initial.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == tag }
		var overlay = initial.config.rigEdits
		overlay = SwingAuthoring.put(overlay, puppet, RigSwingEdit.single("back", "Back", SwingKind.LATERAL, listOf(meshOf(SemanticTag.BACK_HAIR).id.raw),
			listOf("ParamSwingBack"), shape = SwingShape(magnitude = 0.25f, parallel = 0.7f)))
		overlay = SwingAuthoring.put(overlay, overlay.applyTo(puppet), RigSwingEdit("front", "Front", listOf(meshOf(SemanticTag.FRONT_HAIR).id.raw), listOf(
			SwingMotion(SwingKind.VERTICAL, listOf("ParamSwingFront_1", "ParamSwingFront_2"), SwingShape(magnitude = 0.1f)),
			SwingMotion(SwingKind.LATERAL, listOf("ParamSwingFrontX"), SwingShape(magnitude = 0.2f, parallel = 0.8f)))))
		val beforeSwing = overlay.copy(swingEdits = emptyList()).applyTo(puppet)
		fun time(block: () -> Unit): Double { repeat(3) { block() }; val t = System.nanoTime(); repeat(10) { block() }; return (System.nanoTime() - t) / 1e7 }
		val generate = time { SwingGenerator.apply(beforeSwing, overlay.swingEdits) }
		val hash = time {
			val ir = PuppetIr.toIr(beforeSwing)
			ContentHash.of(overlay.swingEdits, ir.deformers, ir.parameters, ir.parameterTree)
		}
		val replay = time { overlay.applyTo(puppet) }
		val report = "swing generation %.1f ms, input hash %.1f ms, whole replay %.1f ms, swings ${overlay.swingEdits.size}".format(generate, hash, replay)
		output("swing-cost").resolve("report.txt").writeText(report)
		println(report)
	}
}
