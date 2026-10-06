package io.github.psd2live.targets.cubism

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.ColorBlend
import io.github.psd2live.format.model.RigIR
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.raster.RasterImage
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.ParameterId

/**
 * A Cubism Editor project (`.cmo3`): the baked rig with editable source layers and physics. Generator
 * intent is not kept; the editor opens the result as plain deformers and keyforms.
 *
 * A cmo3 carries editor GUIDs, which Cubism Editor expects to be unique, so the file is not byte-stable
 * across exports; its content is. The `timestamp` setting (epoch milliseconds) sets the recorded export
 * time, 0 by default. [decorate] lets the host add editor-only metadata it alone knows.
 */
public class Cmo3Target(
	private val decorate: (CModelSource) -> Unit = {},
) : ExportTarget {
	override val id: String = "cmo3"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "Cubism Editor project (.cmo3)"
	override val capabilities: CapabilityProfile = CapabilityProfile(
		warpLattice = true, parameterGrid = 3, blendShapes = true, timeline = false,
		physics = PhysicsSupport.PARAMETER_PENDULUM, blendModes = ColorBlend.entries.toSet(),
		masks = MaskSupport.TEXTURE_ALPHA, keyedDrawOrder = true, glue = true,
	)

	/** The converted graph and its lowering notices, before serialization. */
	public fun convert(ir: RigIR, options: ExportOptions): Cmo3Conversion.Result {
		val puppet = PuppetIr.toPuppet(ir)
		val exportPuppet = restMeshesToCanvasSpace(puppet, ir.restPose.mapKeys { ParameterId(it.key) })
		val pages = ir.textures.pages.mapIndexed { index, page ->
			require(page.png.size > 0) { "Texture page $index has no pixels" }
			Cmo3Conversion.AtlasPage(page.png.shared(), page.width, page.height)
		}
		val art = ir.textures.tileArt.associate { AtlasTileId(it.tile) to RasterImage(it.width, it.height, it.rgba.shared()) }
		val converted = Cmo3Conversion.freshCmo3(
			puppet = exportPuppet,
			pages = pages,
			pageIndexByDrawableId = ir.textures.bindings,
			modelName = options.baseName,
			nowMillis = options.setting("timestamp")?.toLongOrNull() ?: 0L,
			obfuscateKey = 0x42,
			tileRasters = { tile -> art[tile] },
		)
		val root = converted.model.root as CModelSource
		if (ir.physics.groups.isNotEmpty()) Cmo3Physics.inject(root, ir.physics.groups, ir.physics.fps?.toInt() ?: 0)
		decorate(root)
		return converted
	}

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val converted = convert(ir, options)
		val losses = CapabilityScan.scan(ir, capabilities, options) + converted.report.notices.map(Moc3Target::loss)
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) = sink.write("${options.baseName}.cmo3", Cmo3.write(converted.model))
		}
	}
}
