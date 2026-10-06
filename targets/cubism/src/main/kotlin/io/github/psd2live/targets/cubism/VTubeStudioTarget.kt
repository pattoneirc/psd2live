package io.github.psd2live.targets.cubism

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * A model folder VTube Studio loads: the Cubism runtime files plus `<name>.vtube.json`, which maps face
 * tracking to the rig's standard parameters (head, body, eyes, gaze, brows, mouth, breathing) and adds a
 * hotkey for each clip. VTube Studio fills every setting the file leaves out with its defaults. Takes
 * the moc3 settings.
 */
public object VTubeStudioTarget : ExportTarget {
	override val id: String = "vtube-studio"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "VTube Studio model (moc3 + vtube.json)"
	override val capabilities: CapabilityProfile = Moc3Target.capabilities
	override val settings: List<TargetSetting> get() = Moc3Target.settings

	/** One tracking input driving a parameter over its whole range. */
	private class Mapping(
		val name: String, val input: String, val parameter: String, val inputLower: Float, val inputUpper: Float,
		val blinking: Boolean = false, val breathing: Boolean = false,
	)

	private val mappings = listOf(
		Mapping("Face Left/Right Rotation", "FaceAngleX", "ParamAngleX", -30f, 30f),
		Mapping("Face Up/Down Rotation", "FaceAngleY", "ParamAngleY", -30f, 30f),
		Mapping("Face Lean Rotation", "FaceAngleZ", "ParamAngleZ", -30f, 30f),
		Mapping("Body Rotation X", "FaceAngleX", "ParamBodyAngleX", -30f, 30f),
		Mapping("Body Rotation Y", "FaceAngleY", "ParamBodyAngleY", -30f, 30f),
		Mapping("Body Rotation Z", "FaceAngleZ", "ParamBodyAngleZ", -30f, 30f),
		Mapping("Eye Open Left", "EyeOpenLeft", "ParamEyeLOpen", 0f, 1f, blinking = true),
		Mapping("Eye Open Right", "EyeOpenRight", "ParamEyeROpen", 0f, 1f, blinking = true),
		Mapping("Eye X", "EyeRightX", "ParamEyeBallX", -1f, 1f),
		Mapping("Eye Y", "EyeRightY", "ParamEyeBallY", -1f, 1f),
		Mapping("Brow Height Left", "BrowLeftY", "ParamBrowLY", 0f, 1f),
		Mapping("Brow Height Right", "BrowRightY", "ParamBrowRY", 0f, 1f),
		Mapping("Mouth Smile", "MouthSmile", "ParamMouthForm", 0f, 1f),
		Mapping("Mouth Open", "MouthOpen", "ParamMouthOpenY", 0f, 1f),
		Mapping("Breath", "", "ParamBreath", 0f, 1f, breathing = true),
	)

	/** A stable 32-digit hex id derived from [seed], so exports stay byte for byte reproducible. */
	private fun id(seed: String): String = MessageDigest.getInstance("SHA-256").digest(seed.encodeToByteArray())
		.take(16).joinToString("") { "%02x".format(it) }

	public fun config(ir: RigIR, baseName: String, motionFiles: List<String>): String {
		val parameters = ir.parameters.associateBy { it.id }
		val settings = mappings.mapNotNull { m ->
			val p = parameters[m.parameter] ?: return@mapNotNull null
			buildJsonObject {
				put("Folder", ""); put("Name", m.name); put("Input", m.input)
				put("InputRangeLower", m.inputLower); put("InputRangeUpper", m.inputUpper)
				put("OutputRangeLower", p.min); put("OutputRangeUpper", p.max)
				put("ClampInput", false); put("ClampOutput", false)
				put("UseBlinking", m.blinking); put("UseBreathing", m.breathing)
				put("OutputLive2D", m.parameter); put("Smoothing", if (m.blinking) 10 else 15); put("Minimized", false)
			}
		}
		val hotkeys = motionFiles.map { file ->
			buildJsonObject {
				put("HotkeyID", id("$baseName/hotkey/$file")); put("Name", file.removePrefix("$baseName.").removeSuffix(".motion3.json"))
				put("Action", "TriggerAnimation"); put("File", file); put("Folder", "")
				putJsonObject("Triggers") { put("Trigger1", ""); put("Trigger2", ""); put("Trigger3", ""); put("ScreenButton", 0) }
				put("IsGlobal", false); put("IsActive", true); put("Minimized", false)
				put("StopsOnLastFrame", false); put("DeactivateAfterKeyTypedAfterMillis", -1)
			}
		}
		val json = buildJsonObject {
			put("Version", 1)
			put("Name", baseName)
			put("ModelID", id("$baseName/${ir.canvas.width}x${ir.canvas.height}/${ir.parameters.joinToString(",") { it.id }}"))
			putJsonObject("FileReferences") {
				put("Icon", ""); put("Model", "$baseName.model3.json"); put("IdleAnimation", ""); put("IdleAnimationWhenTrackingLost", "")
			}
			put("ParameterSettings", JsonArray(settings))
			put("Hotkeys", JsonArray(hotkeys))
		}
		return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json)
	}

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val bundle = Moc3Target.bundle(ir, options)
		val motions = bundle.files.map { it.name }.filter { it.endsWith(".motion3.json") }
		val config = config(ir, options.baseName, motions)
		val mapped = mappings.map { it.parameter }.toSet()
		val losses = CapabilityScan.scan(ir, capabilities, options) + bundle.report.notices.map(Moc3Target::loss) +
			listOf(LossEntry("*", Feature.STRUCTURE, Handling.APPROXIMATED,
				note = "Face tracking drives the standard parameters only: ${ir.parameters.count { it.id in mapped }} of ${ir.parameters.size}"))
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) {
				bundle.files.forEach { sink.write(it.name, it.bytes) }
				sink.write("${options.baseName}.vtube.json", config.encodeToByteArray())
			}
		}
	}
}
