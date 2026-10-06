package io.github.psd2live.targets.web

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.runtime.P2lrt

/**
 * A folder that plays the rig in a browser: `index.html`, the player script `p2l.js`, the PSD2Live runtime
 * as WebAssembly and the compiled `.p2lrt`. Served over http, it draws with WebGL, plays clips, blinks,
 * breathes, follows the pointer and opens the mouth with a slider. Setting `title` names the page.
 */
public object WebTarget : ExportTarget {
	override val id: String = "web"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "Web player (HTML, WebAssembly runtime, WebGL)"
	override val capabilities: CapabilityProfile = CapabilityProfile(
		warpLattice = true, parameterGrid = 8, blendShapes = true, timeline = true, physics = PhysicsSupport.PARAMETER_PENDULUM,
		blendModes = setOf(ColorBlend.NORMAL, ColorBlend.ADD_PREMULTIPLIED, ColorBlend.MULTIPLY_PREMULTIPLIED, ColorBlend.ADD, ColorBlend.ADD_GLOW, ColorBlend.MULTIPLY),
		masks = MaskSupport.TEXTURE_ALPHA, keyedDrawOrder = true, glue = true,
	)

	private fun resource(name: String): ByteArray =
		checkNotNull(WebTarget::class.java.getResourceAsStream(name)) { "The web player is missing $name" }.use { it.readBytes() }

	private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val rig = P2lrt.write(ir)
		val rigName = "${options.baseName}.p2lrt"
		val page = resource("index.html").decodeToString()
			.replace("{{TITLE}}", html(options.settings["title"] ?: options.baseName))
			.replace("{{RIG}}", html(rigName))
		return object : LoweredExport {
			override val losses: List<LossEntry> = CapabilityScan.scan(ir, capabilities, options)
			override fun write(sink: OutputSink) {
				sink.write("index.html", page.encodeToByteArray())
				sink.write("p2l.js", resource("p2l.js"))
				sink.write("p2l_runtime.wasm", resource("p2l_runtime.wasm"))
				sink.write(rigName, rig)
			}
		}
	}
}
