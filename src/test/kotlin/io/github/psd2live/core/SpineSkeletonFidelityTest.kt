package io.github.psd2live.core

import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.ParameterBake
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.spine.MiniJson
import io.github.psd2live.targets.spine.MiniSkel
import io.github.psd2live.targets.spine.SpinePlayer
import io.github.psd2live.targets.spine.SpineTarget
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Spine export of the sample with an automatic skeleton, posed with Spine's bone math (rotation
 * deformers as bones, additive parameter tracks), against the editor's evaluator: every parameter animation
 * at every key it is sampled at, and the binary skeleton against the JSON one.
 */
@Tag("slow")
class SpineSkeletonFidelityTest {
	@Suppress("UNCHECKED_CAST")
	@Test fun parameterAnimationsMatchTheEditorAtTheirKeys() {
		val plain = PSD2LivePipeline().buildPreview(File("examples/tml/psd-input/tml.psd").toPath())
		val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
			rigEdits = plain.config.rigEdits.copy(skeleton = SkeletonAutoBuilder.build(plain.analysis, plain.rig))))
		val ir = RigIrCompiler.compile(skeletal)
		val files = LinkedHashMap<String, ByteArray>()
		val settings = mapOf("key_tolerance" to "0.0001", "clips" to "false")
		val report = Compiler.export(SpineTarget(IrGeometryEvaluator), ir, ExportOptions("tml", settings = settings)) { path, bytes -> files[path] = bytes }
		val root = MiniJson(files.getValue("tml.json").decodeToString()).value() as Map<String, Any?>
		val bones = root["bones"] as List<*>
		val rotations = ir.deformers.count { it is io.github.psd2live.format.model.Deformer.Rotation }
		println("Spine bones ${bones.size} for $rotations rotations: ${bones.map { (it as Map<*, *>)["name"] }}")
		assertTrue(bones.size > 1, "The skeleton's rotations become bones")
		val player = SpinePlayer(root)
		IrGeometryEvaluator.open(ir).use { session ->
			fun error(tracks: List<Pair<String, Double>>, values: Map<String, Float>): Pair<String, Double> {
				val want = session.evaluate(values)
				val got = player.pose(tracks)
				var worst = "" to 0.0
				for ((mesh, points) in want.positions) {
					val world = got[mesh] ?: continue
					for (i in points.indices step 2) {
						val e = maxOf(abs(world[i] + ir.canvas.width / 2 - points[i]), abs(ir.canvas.height - world[i + 1] - points[i + 1]))
						if (e > worst.second) worst = mesh to e
					}
				}
				return worst
			}
			var single = "" to 0.0
			for (axis in ParameterBake.axes(ir, linkedPairs = false)) {
				val p = axis.x
				for (key in axis.xKeys) {
					val e = error(listOf("param/${p.id}" to ((key - p.min) / (p.max - p.min)).toDouble()), mapOf(p.id to key))
					if (e.second > single.second) single = "${p.id}=$key ${e.first}" to e.second
				}
			}
			println("Spine single-parameter error at keys: ${single.second} px (${single.first})")
			assertTrue(single.second < 0.05, "Single parameters at their keys: $single")
			val random = Random(5)
			var combined = 0.0
			var summed = 0.0
			repeat(12) {
				val values = ir.parameters.associate { it.id to it.min + random.nextFloat() * (it.max - it.min) }
				val e = error(tracks(ir, values), values)
				combined = maxOf(combined, e.second)
				// Summing each parameter's own canvas offsets, as a skeleton without bones does.
				val rest = session.evaluate(emptyMap())
				val want = session.evaluate(values)
				val singles = values.map { (id, v) -> session.evaluate(mapOf(id to v)) }
				var flat = 0.0
				for ((mesh, points) in want.positions) {
					val r = rest.positions.getValue(mesh)
					for (i in points.indices) flat = maxOf(flat, abs(r[i] + singles.sumOf { (it.positions.getValue(mesh)[i] - r[i]).toDouble() } - points[i]))
				}
				summed = maxOf(summed, flat)
			}
			// Joints the skeleton builds from warps (forearms, hands, the upper body) still add up per parameter.
			println("Spine all-parameter error at random poses: $combined px; summing canvas offsets: $summed px")
			assertTrue(combined < summed, "Bones compose better than summed offsets")
			println(report.losses.filter { it.feature.name in setOf("BONES", "PARAMETER_GRID", "PHYSICS") }.joinToString("\n"))
		}
		// The binary skeleton poses like the JSON one.
		val binary = LinkedHashMap<String, ByteArray>()
		Compiler.export(SpineTarget(IrGeometryEvaluator), ir, ExportOptions("tml", settings = settings + ("binary" to "true"))) { path, bytes -> binary[path] = bytes }
		val fromBinary = SpinePlayer(MiniSkel(binary.getValue("tml.skel")).read())
		val random = Random(9)
		repeat(4) {
			val values = ir.parameters.associate { it.id to it.min + random.nextFloat() * (it.max - it.min) }
			val a = player.pose(tracks(ir, values)); val b = fromBinary.pose(tracks(ir, values))
			for ((slot, points) in a) points.indices.forEach { assertTrue(abs(points[it] - b.getValue(slot)[it]) < 1e-3, "$slot") }
		}
		println("Spine JSON ${files.getValue("tml.json").size} bytes, binary ${binary.getValue("tml.skel").size} bytes")
	}

	private fun tracks(ir: RigIR, values: Map<String, Float>) = values.mapNotNull { (id, v) ->
		val p = ir.parameters.first { it.id == id }
		if (p.max > p.min) "param/$id" to ((v - p.min) / (p.max - p.min)).toDouble() else null
	}
}
