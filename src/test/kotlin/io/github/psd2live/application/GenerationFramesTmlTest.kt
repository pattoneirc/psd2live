package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.umamo.render.eval.CpuDeformationEvaluator
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generation transitions on the public tml example move meshes under the body warp chain into new automatic frames.
 * The engine's warp inverse alone lands about a hundredth of a pixel off there; the transition must still place every
 * mesh where it was.
 */
@Tag("slow")
class GenerationFramesTmlTest {
	private val builder = WorkspacePreviewBuilder()

	@Test fun classifyingTmlLayersMigratesTheBodyChain() = runBlocking<Unit> {
		val imported = run {
			val runtime = WorkspaceRuntime<RigPreviewModel>({ WorkspacePreviewBuilder().build(it) })
			WorkspaceSourceImporter(runtime).importPsd(Path.of("examples/tml/psd-input/tml.psd").toAbsolutePath(), null, runtime.state.value.state)
			runtime.capture().document
		}
		// Legs, an eyelash and the mouth: each transition moves the clothes under the body chain into new frames.
		for (layer in listOf("lyid:4", "lyid:19", "lyid:29")) {
			lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
			runtime = WorkspaceRuntime({ next -> builder.build(next, runtime.capture().model) })
			runtime.install(runtime.state.value.state, "frames", imported, builder.build(imported))
			val before = runtime.capture()
			val after = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Classify", listOf(
				WorkspaceDocumentOperation("layer_classify", buildJsonObject { put("layer_id", layer); put("type", "preset"); put("role", "objects") })
			), MutationAuthor.USER).capture
			assertEquals(ContentHash.of(RigIrCompiler.compile(after.model)), ContentHash.of(RigIrCompiler.compile(builder.build(after.document))),
				"$layer: the committed rig is a cold build of its document")
			// Meshes the classification leaves alone keep their rest positions through the new frames.
			val reclassified = after.model.rig.layerIdByDrawableId.filterValues { it == layer }.keys
			val rest = CpuDeformationEvaluator().evaluate(before.model.rig.puppet, emptyMap()).worldPositions
			val moved = CpuDeformationEvaluator().evaluate(after.model.rig.puppet, emptyMap()).worldPositions
			for ((id, positions) in rest) {
				if (id.raw in reclassified || id.raw.startsWith("ArtMeshMouth")) continue
				val now = moved[id] ?: continue
				if (now.size != positions.size) continue
				val worst = positions.indices.maxOfOrNull { abs(positions[it] - now[it]) } ?: 0f
				assertTrue(worst < 0.05f, "$layer: ${id.raw} moved $worst px at rest")
			}
		}
	}
}
