package io.github.psd2live.ui.state

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class ExportSuccessPromptTest {
    @TempDir lateinit var temp: Path

    /** A finished export closes its dialog and reports in the success dialog, unless the prompt is muted. */
    @Test
    fun exportClosesItsDialogAndReportsUnlessMuted() = runBlocking {
        val original = AppSettings.promptEnabled(AppPrompt.EXPORT_SUCCESS)
        val png = temp.resolve("art.png")
        val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        for (y in 8..39) for (x in 8..39) image.setRGB(x, y, 0xff3366ff.toInt())
        ImageIO.write(image, "png", png.toFile())
        try {
            PSD2LiveViewModel().use { vm ->
                DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                    vm.attachWorkspace(workspace)
                    workspace.createArtwork(buildJsonObject {
                        put("width", 48); put("height", 48)
                        putJsonArray("layers") { add(buildJsonObject { put("path", png.toString()); put("name", "body"); put("role", "objects") }) }
                    })
                    vm.setPromptEnabled(AppPrompt.EXPORT_SUCCESS, true)
                    val target = temp.resolve("out").resolve("art.psd")
                    Files.createDirectories(target.parent)

                    vm.openExportPsdDialog()
                    vm.exportPsd(target)
                    waitUntil { vm.state.value.exportSuccess != null }
                    val success = vm.state.value.exportSuccess!!
                    assertEquals(target.parent.toString(), success.folder)
                    assertFalse(vm.state.value.showExportPsdDialog)
                    assertTrue(Files.isRegularFile(target))
                    vm.clearExportSuccess()

                    vm.setPromptEnabled(AppPrompt.EXPORT_SUCCESS, false)
                    Files.delete(target)
                    vm.exportPsd(target)
                    waitUntil { Files.isRegularFile(target) && !vm.state.value.isExportingPsd }
                    assertNull(vm.state.value.exportSuccess)
                }
            }
        } finally {
            AppSettings.setPromptEnabled(AppPrompt.EXPORT_SUCCESS, original)
        }
    }

    private fun waitUntil(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the export" }
            Thread.sleep(20)
        }
    }
}
