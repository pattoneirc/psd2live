package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The VTube Studio folder: the Cubism files, and a vtube.json whose references all resolve. */
@Tag("slow")
class VTubeStudioExportTest {
	@Test fun theConfigurationMapsTrackingToParametersTheModelHas() {
		val preview = PSD2LivePipeline().buildPreview(File("examples/tml/psd-input/tml.psd").toPath())
		val target = ExportService.registry(preview.config)["vtube-studio"]
		val dir = Files.createTempDirectory("vts").toFile()
		try {
			ExportService.export(preview, target, ExportService.options(target, "tml", preview.config), dir.toPath())
			val config = Json.parseToJsonElement(File(dir, "tml.vtube.json").readText()).jsonObject
			val model = config.getValue("FileReferences").jsonObject.getValue("Model").jsonPrimitive.content
			assertTrue(File(dir, model).isFile, "the model3.json is there")
			assertTrue(File(dir, "tml.moc3").isFile)
			val parameters = RigIrCompiler.compile(preview).parameters.associateBy { it.id }
			val settings = config.getValue("ParameterSettings").jsonArray.map { it.jsonObject }
			assertTrue(settings.size >= 8, "head, body, eyes and mouth are mapped")
			for (s in settings) {
				val p = parameters.getValue(s.getValue("OutputLive2D").jsonPrimitive.content)
				assertEquals(p.min, s.getValue("OutputRangeLower").jsonPrimitive.float)
				assertEquals(p.max, s.getValue("OutputRangeUpper").jsonPrimitive.float)
			}
			val hotkeys = config.getValue("Hotkeys").jsonArray.map { it.jsonObject }
			assertTrue(hotkeys.isNotEmpty())
			for (h in hotkeys) assertTrue(File(dir, h.getValue("File").jsonPrimitive.content).isFile)
			// Ids are stable, so exports stay reproducible.
			assertEquals(32, config.getValue("ModelID").jsonPrimitive.content.length)
		} finally {
			dir.deleteRecursively()
		}
	}
}
