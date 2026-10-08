package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.simulationDrivingEdits
import io.github.psd2live.application.simulationPut
import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A bake runs in the background: other edits commit while it computes, and the bake lands afterwards as its own
 * history node on the version current then, keeping those edits.
 */
class SimulationBackgroundBakeTest {
    @TempDir lateinit var temp: Path

    @Test fun editsCommitWhileBakingAndTheBakeLandsOnTop() = runBlocking<Unit> {
        val strip = temp.resolve("strip.png").also { path ->
            val image = BufferedImage(12, 72, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 72) for (x in 0 until 12) image.setRGB(x, y, 0xff506e8c.toInt())
            javax.imageio.ImageIO.write(image, "png", path.toFile())
        }
        PSD2LiveViewModel().use { vm ->
            DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 64); put("height", 96)
                    put("layers", buildJsonArray {
                        add(buildJsonObject { put("path", JsonPrimitive(strip.toString())); put("name", "strip"); put("role", "objects") })
                    })
                })
                val puppet = requireNotNull(vm.state.value.previewModel).rig.puppet
                workspace.applyDocumentEdits(workspace.snapshot().state, "Sway", simulationDrivingEdits(puppet) + simulationPut(puppet), MutationAuthor.USER)
                assertNull(vm.state.value.rigEdits.simEdits.single { it.id == "sway" }.bake)

                withContext(Dispatchers.Main) { vm.bakeSimulation("sway") }
                withTimeout(30_000) { vm.simulationBaking.first { it != null } }
                // Not refused or held back by the bake: the workspace takes other edits meanwhile.
                val other = WorkspaceDocumentOperation("parameter_create", buildJsonObject {
                    put("parameter_id", "Other"); put("name", "Other"); put("min", -1); put("max", 1); put("default", 0)
                })
                workspace.applyDocumentEdits(workspace.snapshot().state, "Other", listOf(other), MutationAuthor.USER)
                assertNotNull(vm.simulationBaking.value, "the edit landed while the bake was still running")

                withTimeout(60_000) { vm.awaitSimulationBakes() }
                val state = vm.state.value
                assertNotNull(state.rigEdits.simEdits.single { it.id == "sway" }.bake, "the bake committed")
                assertTrue(state.previewModel!!.rig.puppet.parameters.any { it.id.raw == "Other" }, "and the edit made meanwhile stays")
                val summaries = requireNotNull(state.historySnapshot).nodes.map { it.summary }
                assertEquals(1, summaries.count { it.startsWith("Baked simulation") }, "the bake is one history node of its own: $summaries")
                assertTrue(summaries.indexOf("Other") < summaries.indexOfFirst { it.startsWith("Baked simulation") }, "the bake lands on top: $summaries")
                assertNull(vm.simulationBaking.value)
                assertTrue(vm.simulationStatus.value !is PSD2LiveViewModel.SimulationStatus.Failed, "${vm.simulationStatus.value}")
            }
        }
    }
}
