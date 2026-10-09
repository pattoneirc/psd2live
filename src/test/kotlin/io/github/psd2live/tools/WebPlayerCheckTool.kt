package io.github.psd2live.tools

import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.web.WebTarget
import org.umamo.render.puppet.compositeReference
import org.umamo.render.puppet.packedAlphaModeOf
import org.umamo.render.puppet.packedColorModeOf
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * A synthetic rig for checking the web player against the editor's compositing: every color blend mode under
 * every alpha blend mode over opaque, half-transparent and empty backdrops, isolated groups (each group mode,
 * opacity, multiply and screen colors, masks by meshes and by a part, an inverted mask, a nested group,
 * extended modes inside a layer) and masked meshes with extended modes. Writes build/tools/web-player-check/:
 * the exported web folder plus check.html, which draws one frame and compares it with reference.rgba, the
 * frame drawn here with the editor's per-pixel compositing (BlendMath.compositeReference), premultiplied,
 * rows top down; regions.json names the cells, reference.png shows it.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*WebPlayerCheckTool'
 * then serve the folder over http and open check.html at a device pixel ratio of 1.
 */
class WebPlayerCheckTool {
	private class Look(
		val blend: ColorBlend = ColorBlend.NORMAL, val alpha: AlphaBlend = AlphaBlend.OVER, val opacity: Float = 1f,
		val multiply: Rgb = Rgb.White, val screen: Rgb = Rgb.Black, val masks: List<String> = emptyList(), val invert: Boolean = false,
	)

	private sealed interface Node
	private class Quad(val id: String, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val block: Int, val look: Look = Look(),
	                   val visible: Boolean = true) : Node
	private class Group(val id: String, val look: Look, val children: List<Node>, val maskParts: List<String> = emptyList()) : Node

	private val width = 800
	private val height = 600

	/** The atlas's 8 × 8 blocks of one color each, ARGB. */
	private val blocks = intArrayOf(
		0xffcc4d33.toInt(), 0x803380e6.toInt(), 0xff4db366.toInt(), 0x99e6cc1a.toInt(),
		0xffffffff.toInt(), 0xff404040.toInt(), 0xb3f0a0c0.toInt(), 0x60209090,
	)

	@Test fun check() {
		requireTools()
		val out = output("web-player-check")
		val nodes = ArrayList<Node>()
		val regions = ArrayList<String>()
		fun region(name: String, x0: Int, y0: Int, x1: Int, y1: Int) { regions += """{"name":"$name","x0":$x0,"y0":$y0,"x1":$x1,"y1":$y1}""" }
		fun backdrop(row: String, y: Int, band: Int, x0: Int = 10, x1: Int = 790) {
			nodes += Quad("$row-opaque", x0, y, x1, y + band, 0)
			nodes += Quad("$row-half", x0, y + band, x1, y + 2 * band, 1)
		}

		// Every color mode (columns) under every alpha mode (rows): within a 42-pixel cell the backdrop is opaque,
		// half transparent and empty from the top, the source opaque, 60% and empty from the left.
		for ((row, alpha) in AlphaBlend.entries.withIndex()) {
			val y = 10 + row * 43
			backdrop("grid-$row", y, 14, 10, 784)
			for ((col, blend) in ColorBlend.entries.withIndex()) {
				val x = 10 + col * 43
				val look = Look(blend, alpha)
				nodes += Quad("grid-$row-$col-opaque", x, y, x + 14, y + 42, 2, look)
				nodes += Quad("grid-$row-$col-part", x + 14, y, x + 28, y + 42, 3, look)
				region("grid ${blend.name} ${alpha.name}", x, y, x + 42, y + 42)
			}
		}

		// Isolated groups over the same banded backdrop, two overlapping children each.
		fun groupCell(k: Int, y: Int, id: String, look: Look, second: Look = Look(), extra: (Int) -> List<Node> = { emptyList() },
		              maskParts: List<String> = emptyList()): Node {
			val x = 10 + k * 97
			region("group $id", x, y, x + 90, y + 90)
			return Group(id, look, listOf(
				Quad("$id-a", x + 5, y + 5, x + 55, y + 85, 2),
				Quad("$id-b", x + 35, y + 10, x + 85, y + 80, 3, second),
			) + extra(x), maskParts)
		}
		val row1 = 240
		backdrop("groups-1", row1, 30)
		nodes += groupCell(0, row1, "normal", Look(opacity = 0.6f))
		nodes += groupCell(1, row1, "cubism-add", Look(ColorBlend.ADD_PREMULTIPLIED, opacity = 0.8f, multiply = Rgb(1f, 0.6f, 0.4f)))
		nodes += groupCell(2, row1, "cubism-multiply", Look(ColorBlend.MULTIPLY_PREMULTIPLIED, AlphaBlend.ATOP, screen = Rgb(0.1f, 0.2f, 0.3f)))
		nodes += groupCell(3, row1, "overlay-atop", Look(ColorBlend.OVERLAY, AlphaBlend.ATOP, opacity = 0.9f), Look(ColorBlend.SCREEN))
		nodes += groupCell(4, row1, "hue-conjoint", Look(ColorBlend.HUE, AlphaBlend.CONJOINT), Look(ColorBlend.DARKEN, AlphaBlend.DISJOINT))
		nodes += groupCell(5, row1, "screen-disjoint", Look(ColorBlend.SCREEN, AlphaBlend.DISJOINT, 0.7f, Rgb(0.8f, 0.9f, 1f), Rgb(0.2f, 0f, 0.1f)))
		nodes += groupCell(6, row1, "multiply-out", Look(ColorBlend.MULTIPLY, AlphaBlend.OUT))
		val x7 = 10 + 7 * 97
		nodes += Quad("dodge-mask", x7, row1 + 20, x7 + 60, row1 + 90, 4, visible = false)
		nodes += groupCell(7, row1, "dodge-masked", Look(ColorBlend.COLOR_DODGE, masks = listOf("dodge-mask")))

		val row2 = 345
		backdrop("groups-2", row2, 30)
		run {
			val x = 10
			region("group nested", x, row2, x + 90, row2 + 90)
			nodes += Group("nested", Look(opacity = 0.8f), listOf(
				Quad("nested-a", x + 5, row2 + 5, x + 55, row2 + 85, 2),
				Group("nested-inner", Look(ColorBlend.SOFT_LIGHT, multiply = Rgb(0.9f, 0.7f, 1f)), listOf(
					Quad("nested-inner-a", x + 30, row2 + 10, x + 85, row2 + 80, 3),
					Quad("nested-inner-b", x + 40, row2 + 30, x + 70, row2 + 60, 6),
				)),
			))
		}
		val x1 = 10 + 97
		nodes += Quad("color-mask", x1 + 20, row2 + 20, x1 + 60, row2 + 60, 4, visible = false)
		nodes += groupCell(1, row2, "color-inverted", Look(ColorBlend.COLOR, masks = listOf("color-mask"), invert = true))
		val x2 = 10 + 2 * 97
		nodes += Quad("part-mask-a", x2 + 5, row2 + 5, x2 + 40, row2 + 85, 4, visible = false)
		nodes += Quad("part-mask-b", x2 + 50, row2 + 40, x2 + 85, row2 + 85, 4, visible = false)
		nodes += groupCell(2, row2, "lighten-part-mask", Look(ColorBlend.LIGHTEN, AlphaBlend.ATOP), maskParts = listOf("mask-part"))
		nodes += groupCell(3, row2, "normal-out", Look(alpha = AlphaBlend.OUT, opacity = 0.8f))
		nodes += groupCell(4, row2, "linear-light-disjoint", Look(ColorBlend.LINEAR_LIGHT, AlphaBlend.DISJOINT, 0.9f, screen = Rgb(0.1f, 0.1f, 0.4f)),
			Look(ColorBlend.HARD_LIGHT, AlphaBlend.ATOP))
		run {
			val x = 10 + 5 * 97
			region("meshes masked", x, row2, x + 90, row2 + 90)
			nodes += Quad("burn-mask", x + 5, row2 + 20, x + 45, row2 + 70, 4, visible = false)
			nodes += Quad("linear-burn-masked", x + 5, row2 + 5, x + 45, row2 + 85, 3, Look(ColorBlend.LINEAR_BURN, AlphaBlend.ATOP, masks = listOf("burn-mask")))
			nodes += Quad("color-burn-colored", x + 45, row2 + 5, x + 85, row2 + 85, 6,
				Look(ColorBlend.COLOR_BURN, opacity = 0.75f, multiply = Rgb(1f, 0.5f, 0.8f), screen = Rgb(0.2f, 0.1f, 0f)))
		}
		run {
			val x = 10 + 6 * 97
			region("mesh inverted", x, row2, x + 90, row2 + 90)
			nodes += Quad("light-mask", x + 30, row2 + 30, x + 60, row2 + 60, 4, visible = false)
			nodes += Quad("linear-light-inverted", x + 5, row2 + 5, x + 85, row2 + 85, 3,
				Look(ColorBlend.LINEAR_LIGHT, masks = listOf("light-mask"), invert = true))
		}
		val x7b = 10 + 7 * 97
		nodes += Quad("cubism-mask", x7b + 5, row2 + 5, x7b + 50, row2 + 60, 4, visible = false)
		nodes += Group("cubism-inside", Look(), listOf(
			Quad("cubism-inside-a", x7b + 5, row2 + 5, x7b + 55, row2 + 85, 2, Look(masks = listOf("cubism-mask"))),
			Quad("cubism-inside-add", x7b + 35, row2 + 10, x7b + 85, row2 + 80, 7, Look(ColorBlend.ADD_PREMULTIPLIED)),
			Quad("cubism-inside-multiply", x7b + 20, row2 + 40, x7b + 70, row2 + 80, 6, Look(ColorBlend.MULTIPLY_PREMULTIPLIED)),
		))
		region("group cubism-inside", x7b, row2, x7b + 90, row2 + 90)

		val ir = rig(nodes)
		File(out, "rig.p2lrt").writeBytes(io.github.psd2live.targets.runtime.P2lrt.write(ir))
		Compiler.export(WebTarget, ir, ExportOptions("rig")) { path, bytes -> File(out, path).writeBytes(bytes) }
		val frame = reference(nodes)
		File(out, "reference.rgba").writeBytes(ByteArray(frame.size) { (frame[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() })
		File(out, "regions.json").writeText("[\n" + regions.joinToString(",\n") + "\n]\n")
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		for (i in 0 until width * height) {
			val a = frame[i * 4 + 3].coerceIn(0f, 1f)
			if (a <= 0f) continue
			fun c(v: Float) = (v / a).coerceIn(0f, 1f).times(255f).plus(0.5f).toInt()
			image.setRGB(i % width, i / width, ((a * 255f + 0.5f).toInt() shl 24) or (c(frame[i * 4]) shl 16) or (c(frame[i * 4 + 1]) shl 8) or c(frame[i * 4 + 2]))
		}
		ImageIO.write(image, "png", File(out, "reference.png"))
		File(out, "check.html").writeText(CHECK_PAGE)
	}

	private fun quads(nodes: List<Node>): List<Quad> = nodes.flatMap { if (it is Quad) listOf(it) else quads((it as Group).children) }

	private fun rig(nodes: List<Node>): RigIR {
		val atlas = BufferedImage(64, 8, BufferedImage.TYPE_INT_ARGB)
		for ((i, c) in blocks.withIndex()) for (x in i * 8 until i * 8 + 8) for (y in 0 until 8) atlas.setRGB(x, y, c)
		val png = Bytes.wrap(ByteArrayOutputStream().also { ImageIO.write(atlas, "png", it) }.toByteArray())
		val meshes = quads(nodes).map { q ->
			// Every vertex samples the middle of its block, so the quad is one color.
			val u = (q.block * 8 + 4) / 64f
			val x0 = q.x0.toFloat(); val y0 = q.y0.toFloat(); val x1 = q.x1.toFloat(); val y1 = q.y1.toFloat()
			Mesh(q.id, q.id, null, blend = q.look.blend, alphaBlend = q.look.alpha, maskedBy = q.look.masks, invertMask = q.look.invert,
				opacity = q.look.opacity, multiply = q.look.multiply, screen = q.look.screen, page = 0, visible = q.visible, offsets = null,
				geometry = MeshGeometry(Floats.values(x0, y0, x1, y0, x0, y1, x1, y1), Floats.values(u, 0.5f, u, 0.5f, u, 0.5f, u, 0.5f), Ints.values(0, 1, 2, 1, 3, 2)))
		}
		val parts = ArrayList<Part>()
		fun composite(g: Group) = Composite(g.look.blend, g.look.alpha, g.look.masks, g.maskParts, g.look.invert, g.look.opacity, g.look.multiply, g.look.screen)
		fun tree(node: Node): RenderNode = when (node) {
			is Quad -> RenderMesh(node.id)
			is Group -> {
				parts += Part(node.id, node.id, node.children.map { if (it is Quad) ChildRef.MeshRef(it.id) else ChildRef.PartRef((it as Group).id) },
					groupMode = GroupMode.ISOLATED, composite = composite(node))
				RenderGroup(node.id, RigIR.DEFAULT_DRAW_ORDER, node.children.map(::tree), composite = composite(node))
			}
		}
		val root = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, nodes.map(::tree))
		parts += Part("mask-part", "mask-part", listOf(ChildRef.MeshRef("part-mask-a"), ChildRef.MeshRef("part-mask-b")))
		return RigIR(Canvas(width.toFloat(), height.toFloat()), emptyList(), meshes = meshes, parts = parts,
			textures = Textures(pages = listOf(TexturePage(64, 8, png))), renderRoot = root)
	}

	/** The frame as the editor composites it, premultiplied RGBA floats, rows top down. */
	private fun reference(nodes: List<Node>): FloatArray {
		val byId = quads(nodes).associateBy { it.id }
		val maskParts = mapOf("mask-part" to listOf("part-mask-a", "part-mask-b"))
		// Mask coverage: the mask quads are opaque, so a pixel is covered or not.
		fun covered(masks: List<String>, invert: Boolean, x: Int, y: Int): Boolean {
			val inside = masks.any { id -> byId.getValue(id).let { x >= it.x0 && x < it.x1 && y >= it.y0 && y < it.y1 } }
			return inside != invert
		}
		fun draw(list: List<Node>, target: FloatArray) {
			for (node in list) when (node) {
				is Quad -> if (node.visible) {
					val c = blocks[node.block]
					val a = (c ushr 24) / 255f
					val l = node.look
					for (y in node.y0 until node.y1) for (x in node.x0 until node.x1) {
						if (l.masks.isNotEmpty() && !covered(l.masks, l.invert, x, y)) continue
						val s = FloatArray(4)
						s[3] = a
						for (k in 0..2) {
							val channel = ((c shr (16 - 8 * k)) and 0xff) / 255f * a
							val m = channel * l.multiply.at(k)
							s[k] = m + l.screen.at(k) * a - m * l.screen.at(k)
						}
						for (k in 0..3) s[k] *= l.opacity
						put(target, x, y, compositeReference(s, get(target, x, y), color(l.blend), alpha(l.alpha)))
					}
				}
				is Group -> {
					val layer = FloatArray(width * height * 4)
					draw(node.children, layer)
					val l = node.look
					val masks = l.masks + node.maskParts.flatMap { maskParts.getValue(it) }
					for (y in 0 until height) for (x in 0 until width) {
						val p = get(layer, x, y)
						if (p[3] <= 0f || (masks.isNotEmpty() && !covered(masks, l.invert, x, y))) continue
						val a = p[3] * l.opacity
						val s = FloatArray(4)
						s[3] = a
						for (k in 0..2) {
							val straight = (p[k] / p[3]).coerceIn(0f, 1f) * l.multiply.at(k)
							s[k] = (straight + l.screen.at(k) - straight * l.screen.at(k)) * a
						}
						put(target, x, y, compositeReference(s, get(target, x, y), color(l.blend), alpha(l.alpha)))
					}
				}
			}
		}
		val frame = FloatArray(width * height * 4)
		draw(nodes, frame)
		return frame
	}

	private fun Rgb.at(k: Int) = when (k) { 0 -> red; 1 -> green; else -> blue }
	private fun get(buffer: FloatArray, x: Int, y: Int) = buffer.copyOfRange((y * width + x) * 4, (y * width + x) * 4 + 4)
	private fun put(buffer: FloatArray, x: Int, y: Int, value: FloatArray) = value.copyInto(buffer, (y * width + x) * 4)
	private fun color(blend: ColorBlend) = BlendMode.entries.first { packedColorModeOf(it) == blend.ordinal }
	private fun alpha(blend: AlphaBlend) = AlphaBlendMode.entries.first { packedAlphaModeOf(it) == blend.ordinal }

	private companion object {
		/** Draws one frame with the player and compares it with reference.rgba, writing the result into #result. */
		val CHECK_PAGE = """
			<!doctype html>
			<html><head><meta charset="utf-8"><title>web player check</title>
			<style>body { margin: 0; background: #fff; } canvas { display: block; width: 800px; height: 600px; }</style></head>
			<body><canvas id="stage"></canvas><pre id="result">running</pre>
			<script type="module">
			import { P2LPlayer } from "./p2l.js";
			const result = document.getElementById("result");
			try {
			  const [wasm, rig, reference, regions] = await Promise.all([
			    fetch("p2l_runtime.wasm").then((r) => r.arrayBuffer()), fetch("rig.p2lrt").then((r) => r.arrayBuffer()),
			    fetch("reference.rgba").then((r) => r.arrayBuffer()), fetch("regions.json").then((r) => r.json())]);
			  const { instance } = await WebAssembly.instantiate(wasm, {});
			  const canvas = document.getElementById("stage");
			  const player = await P2LPlayer.fromBytes(canvas, instance.exports, new Uint8Array(rig));
			  player.behaviors(0);
			  player.update(0);
			  player.draw();
			  const gl = player.gl, w = canvas.width, h = canvas.height;
			  if (w !== 800 || h !== 600) throw new Error("canvas is " + w + "x" + h + ", run at a device pixel ratio of 1");
			  const pixels = new Uint8Array(w * h * 4);
			  gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
			  const ref = new Uint8Array(reference);
			  const diff = new Uint8Array(w * h);
			  let max = 0, over2 = 0, at = null;
			  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
			    let d = 0;
			    for (let k = 0; k < 4; k++) d = Math.max(d, Math.abs(pixels[((h - 1 - y) * w + x) * 4 + k] - ref[(y * w + x) * 4 + k]));
			    diff[y * w + x] = d;
			    if (d > 2) over2++;
			    if (d > max) { max = d; at = [x, y, Array.from(pixels.slice(((h - 1 - y) * w + x) * 4, ((h - 1 - y) * w + x) * 4 + 4)), Array.from(ref.slice((y * w + x) * 4, (y * w + x) * 4 + 4))]; }
			  }
			  const worst = regions.map((r) => {
			    let m = 0;
			    for (let y = r.y0; y < r.y1; y++) for (let x = r.x0; x < r.x1; x++) m = Math.max(m, diff[y * w + x]);
			    return [r.name, m];
			  }).filter((r) => r[1] > 2);
			  const info = gl.getExtension("WEBGL_debug_renderer_info");
			  result.textContent = JSON.stringify({ renderer: gl.getParameter(info ? info.UNMASKED_RENDERER_WEBGL : gl.RENDERER), max, pixelsOver2: over2, worstAt: at, regionsOver2: worst });
			} catch (e) {
			  result.textContent = "error: " + e + "\n" + e.stack;
			}
			</script></body></html>
		""".trimIndent()
	}
}
