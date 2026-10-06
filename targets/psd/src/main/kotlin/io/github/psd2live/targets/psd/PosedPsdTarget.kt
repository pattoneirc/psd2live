package io.github.psd2live.targets.psd

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.RigIR
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.format.psd.PsdWriter

/**
 * A layered PSD of the rig at one pose: every visible mesh rendered deformed onto its own layer, bottom to
 * top in rest draw order, so a pose can go back to the art workflow.
 *
 * Settings: `clip` and `time` (seconds) pose the rig from a clip, or `pose` sets parameters directly
 * (`ParamAngleX=20,ParamEyeLOpen=0`; it overrides the clip); `scale` (0.25..2, default 1) sets the resolution
 * against the canvas.
 */
public class PosedPsdTarget(private val renderer: FrameRenderer) : ExportTarget {
	override val id: String = "psd-pose"
	override val family: TargetFamily = TargetFamily.TIMELINE
	override val description: String = "Layered PSD at a pose"
	override val capabilities: CapabilityProfile = CapabilityProfile(structure = false)
	override val settings: List<TargetSetting> = listOf(
		TargetSetting.ClipChoice("clip", rest = true), TargetSetting.Number("time", 0.0, 0.0, 3600.0, 0.1, 2),
		TargetSetting.Text("pose", "ParamAngleX=20,ParamEyeLOpen=0"), TargetSetting.Number("scale", 1.0, 0.25, 2.0, 0.25, 2),
	)

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val clip = options.setting("clip")?.let { wanted -> ir.clips.firstOrNull { it.id == wanted } ?: throw IllegalArgumentException("Unknown clip: $wanted") }
		val time = options.float("time", 0f)
		val pose = (clip?.let { ClipSampler.valuesAt(it, time) } ?: emptyMap()) + parsePose(options.setting("pose"))
		val known = ir.parameters.mapTo(HashSet()) { it.id }
		require(pose.keys.all(known::contains)) { "Unknown parameters in pose: ${(pose.keys - known).sorted()}" }
		val scale = options.float("scale", 1f)
		require(scale in 0.25f..2f) { "Scale must be within 0.25..2" }
		val width = (ir.canvas.width * scale).toInt().coerceAtLeast(1)
		val height = (ir.canvas.height * scale).toInt().coerceAtLeast(1)
		val frame = FrameSpec(0f, 0f, ir.canvas.width, ir.canvas.height, width, height)
		val meshes = ir.meshes.filter { it.visible && it.geometry != null }.sortedBy { it.drawOrder }
		val layers = renderer.open(ir, physics = false).use { session ->
			meshes.mapIndexedNotNull { index, mesh ->
				val image = session.render(pose, 0f, frame, setOf(mesh.id))
				crop(image)?.let { (bounds, raster) -> PosedLayer(mesh.id, mesh.name, meshes.size - index, bounds, raster) }
			}
		}
		val losses = buildList {
			add(LossEntry("*", Feature.STRUCTURE, Handling.BAKED, note = "Each mesh is rendered at the pose; deformers and parameters are not kept"))
			ir.meshes.filter { io.github.psd2live.format.model.Channel.DRAW_ORDER in it.channels }.forEach {
				add(LossEntry(it.id, Feature.KEYED_DRAW_ORDER, Handling.APPROXIMATED, note = "Layers follow the rest draw order"))
			}
			ir.meshes.filter { it.maskedBy.isNotEmpty() }.forEach {
				add(LossEntry(it.id, Feature.MASK, Handling.BAKED, note = "The mask is applied to the layer's pixels"))
			}
		}
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) = sink.write("${options.baseName}.psd", PsdWriter.write(width, height, layers))
		}
	}

	private class PosedLayer(id: String, override val name: String, override val order: Int,
	                         override val bounds: LayerBounds, override val raster: LayerRaster) : SourceLayer {
		override val id: LayerId = LayerId(id)
		override val groupPath: String = ""
		override val opacity: Float = 1f
		override val clipped: Boolean = false
		override val blend: LayerBlend = LayerBlend.Normal
		override val channelMask: ChannelMask = ChannelMask.ALL
	}

	private fun parsePose(text: String?): Map<String, Float> = text?.split(',')?.filter { it.isNotBlank() }?.associate { pair ->
		val (key, value) = pair.split('=').takeIf { it.size == 2 } ?: throw IllegalArgumentException("Pose entries are id=value: $pair")
		key.trim() to (value.trim().toFloatOrNull()?.takeIf(Float::isFinite) ?: throw IllegalArgumentException("Pose value is not a number: $pair"))
	}.orEmpty()

	/** The opaque bounding box of [image] and its pixels as straight RGBA, or null when nothing is drawn. */
	private fun crop(image: RasterImage): Pair<LayerBounds, LayerRaster>? {
		var left = image.width; var top = image.height; var right = -1; var bottom = -1
		for (y in 0 until image.height) for (x in 0 until image.width) if (image.argb[y * image.width + x] ushr 24 != 0) {
			if (x < left) left = x; if (x > right) right = x; if (y < top) top = y; if (y > bottom) bottom = y
		}
		if (right < 0) return null
		val w = right - left + 1; val h = bottom - top + 1
		val rgba = ByteArray(w * h * 4)
		for (y in 0 until h) for (x in 0 until w) {
			val argb = image.argb[(top + y) * image.width + left + x]
			val i = (y * w + x) * 4
			rgba[i] = (argb shr 16).toByte(); rgba[i + 1] = (argb shr 8).toByte(); rgba[i + 2] = argb.toByte(); rgba[i + 3] = (argb ushr 24).toByte()
		}
		return LayerBounds(left, top, w, h) to LayerRaster(w, h, rgba)
	}
}
