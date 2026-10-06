package io.github.psd2live.targets.web

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.runtime.P2lrt
import kotlin.test.*

class WebTargetTest {
	private val rig = RigIR(
		canvas = Canvas(100f, 80f),
		parameters = listOf(Parameter("A", "A", 0f, 1f, 0f)),
		meshes = listOf(Mesh("M", "M", null, blend = ColorBlend.OVERLAY, geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)), offsets = null)),
	)

	private fun export(vararg settings: Pair<String, String>): Pair<Map<String, ByteArray>, ExportReport> {
		val files = LinkedHashMap<String, ByteArray>()
		val report = Compiler.export(WebTarget, rig, ExportOptions("hero", settings = mapOf(*settings))) { path, bytes -> files[path] = bytes }
		return files to report
	}

	@Test fun theFolderHoldsThePageThePlayerTheRuntimeAndTheRig() {
		val (files, report) = export("title" to "Hero <1>")
		assertEquals(listOf("index.html", "p2l.js", "p2l_runtime.wasm", "hero.p2lrt"), files.keys.toList())
		val page = files.getValue("index.html").decodeToString()
		assertTrue("<title>Hero &lt;1&gt;</title>" in page)
		assertTrue("\"hero.p2lrt\"" in page)
		// A WebAssembly module, and the rig the runtime reads.
		assertContentEquals(byteArrayOf(0, 'a'.code.toByte(), 's'.code.toByte(), 'm'.code.toByte()), files.getValue("p2l_runtime.wasm").copyOf(4))
		assertContentEquals(P2lrt.write(rig), files.getValue("hero.p2lrt"))
		// Overlay is one of the blend modes the player draws as normal.
		assertEquals(Feature.BLEND_MODE, report.losses.single().feature)
	}

	@Test fun thePlayerUsesEveryRuntimeFunctionItCalls() {
		val script = WebTarget::class.java.getResourceAsStream("p2l.js")!!.readBytes().decodeToString()
		val called = Regex("""p2l_[a-z_]+""").findAll(script).map { it.value }.toSet()
		val wasm = WebTarget::class.java.getResourceAsStream("p2l_runtime.wasm")!!.readBytes().toString(Charsets.ISO_8859_1)
		// Every function the player calls is exported by the bundled runtime.
		for (name in called) assertTrue(name in wasm, "The runtime does not export $name")
	}
}
