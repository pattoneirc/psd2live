package io.github.psd2live.tools

import io.github.psd2live.format.model.*
import io.github.psd2live.targets.runtime.P2lrt
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
 * A synthetic rig of solid quads for checking a player's compositing at points: every color blend mode
 * under every alpha blend mode over an opaque backdrop and inside isolated groups (where the backdrop is
 * partly transparent), and isolated groups with opacity, multiply and screen colors, their own blend modes,
 * masks by meshes and by parts, inverted masks, nesting and Cubism's add and multiply inside a layer. Writes
 * build/tools/godot-layers/layers.p2lrt and expected.tsv: canvas x, y, the expected premultiplied RGBA
 * (0..255) from the editor's composite math (BlendMath.compositeReference), and a label.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*GodotLayerSamplesTool'
 */
class GodotLayerSamplesTool {
	private val backgroundColor = 0xff4080c0.toInt()
	private val backdropColor = 0xffc83c28.toInt()
	private val sourceColor = 0xff28b4dc.toInt()
	private val white = 0xffffffff.toInt()
	private val blocks = listOf(backgroundColor, backdropColor, sourceColor, white)

	private val meshes = ArrayList<Mesh>()
	private val parts = ArrayList<Part>()
	private val samples = ArrayList<String>()

	private fun quad(id: String, x: Float, y: Float, w: Float, h: Float, block: Int, blend: ColorBlend = ColorBlend.NORMAL,
	                 alpha: AlphaBlend = AlphaBlend.OVER, opacity: Float = 1f, maskedBy: List<String> = emptyList(), invert: Boolean = false,
	                 visible: Boolean = true): RenderMesh {
		val u = (block * 16 + 8) / 64f
		meshes += Mesh(id, id, null, blend = blend, alphaBlend = alpha, maskedBy = maskedBy, invertMask = invert, opacity = opacity,
			page = 0, visible = visible, offsets = null, geometry = MeshGeometry(
				Floats.values(x, y, x + w, y, x, y + h, x + w, y + h), Floats.values(u, 0.5f, u, 0.5f, u, 0.5f, u, 0.5f), Ints.values(0, 1, 2, 1, 3, 2)))
		return RenderMesh(id)
	}

	private fun group(id: String, children: List<RenderNode>, composite: Composite = Composite()): RenderGroup {
		parts += Part(id, id, children.map { if (it is RenderGroup) ChildRef.PartRef(it.part!!) else ChildRef.MeshRef((it as RenderMesh).id) },
			groupMode = GroupMode.ISOLATED, composite = composite)
		return RenderGroup(id, RigIR.DEFAULT_DRAW_ORDER, children, composite = composite)
	}

	private fun color(argb: Int, opacity: Float = 1f): FloatArray {
		val a = (argb ushr 24) / 255f * opacity
		return floatArrayOf(((argb shr 16) and 0xff) / 255f * a, ((argb shr 8) and 0xff) / 255f * a, (argb and 0xff) / 255f * a, a)
	}

	private fun composite(s: FloatArray, d: FloatArray, blend: ColorBlend = ColorBlend.NORMAL, alpha: AlphaBlend = AlphaBlend.OVER): FloatArray =
		compositeReference(s, d, BlendMode.entries.first { packedColorModeOf(it) == blend.ordinal },
			AlphaBlendMode.entries.first { packedAlphaModeOf(it) == alpha.ordinal })

	/** A layer pixel as its group draws it: unpremultiplied, colored as a texel, scaled by opacity and coverage. */
	private fun layer(l: FloatArray, opacity: Float = 1f, multiply: Rgb = Rgb.White, screen: Rgb = Rgb.Black, coverage: Float = 1f): FloatArray {
		val a = l[3]
		if (a <= 0f) return FloatArray(4)
		val m = floatArrayOf(multiply.red, multiply.green, multiply.blue)
		val s = floatArrayOf(screen.red, screen.green, screen.blue)
		val k = opacity * coverage
		val c = FloatArray(3) { (l[it] / a).coerceIn(0f, 1f) * a * m[it] }
		return FloatArray(4) { if (it == 3) a * k else (c[it] + s[it] * a - c[it] * s[it]) * k }
	}

	private fun sample(x: Float, y: Float, expected: FloatArray, label: String) {
		samples += "${x.toInt()}\t${y.toInt()}\t" + expected.joinToString("\t") { "%.2f".format(it.coerceIn(0f, 1f) * 255f) } + "\t$label"
	}

	@Test fun layers() {
		requireTools()
		val out = output("godot-layers")
		val atlas = BufferedImage(64, 16, BufferedImage.TYPE_INT_ARGB)
		for ((i, c) in blocks.withIndex()) for (x in i * 16 until i * 16 + 16) for (y in 0 until 16) atlas.setRGB(x, y, c)
		val bg = color(backgroundColor)
		val nodes = ArrayList<RenderNode>()
		nodes += quad("background", 0f, 0f, 800f, 600f, 0)

		// Every pair over the opaque background.
		for (alpha in AlphaBlend.entries) for (blend in ColorBlend.entries) {
			val x = 10f + blend.ordinal * 44f; val y = 10f + alpha.ordinal * 44f
			nodes += quad("a-${blend.ordinal}-${alpha.ordinal}", x, y, 40f, 40f, 2, blend, alpha, opacity = 0.7f)
			sample(x + 20f, y + 20f, composite(color(sourceColor, 0.7f), bg, blend, alpha), "mesh ${blend.name} ${alpha.name}")
		}
		// Every pair inside an isolated group per alpha mode: a backdrop at 0.6 under the source at 0.7.
		for (alpha in AlphaBlend.entries) {
			val children = ArrayList<RenderNode>()
			for (blend in ColorBlend.entries) {
				val x = 10f + blend.ordinal * 44f; val y = 240f + alpha.ordinal * 44f
				children += quad("b-back-${blend.ordinal}-${alpha.ordinal}", x, y, 27f, 40f, 1, opacity = 0.6f)
				children += quad("b-src-${blend.ordinal}-${alpha.ordinal}", x + 13f, y, 27f, 40f, 2, blend, alpha, opacity = 0.7f)
				val back = color(backdropColor, 0.6f); val src = color(sourceColor, 0.7f)
				val label = "group ${blend.name} ${alpha.name}"
				sample(x + 6f, y + 20f, composite(layer(back), bg), "$label backdrop")
				sample(x + 20f, y + 20f, composite(layer(composite(src, back, blend, alpha)), bg), "$label overlap")
				sample(x + 34f, y + 20f, composite(layer(composite(src, FloatArray(4), blend, alpha)), bg), "$label source")
			}
			nodes += group("row-${alpha.name.lowercase()}", children)
		}

		// Groups' own settings, one cell each.
		val y = 470f; val h = 60f
		fun cell(i: Int) = 10f + i * 78f
		val src = color(sourceColor); val back = color(backdropColor)
		run {
			val x = cell(0)
			val c = Composite(blend = ColorBlend.MULTIPLY, opacity = 0.5f, multiply = Rgb(1f, 0.5f, 0.25f), screen = Rgb(0.1f, 0.2f, 0.3f))
			nodes += group("c-colors", listOf(quad("c0", x, y, 70f, h, 2)), c)
			sample(x + 35f, y + 30f, composite(layer(src, 0.5f, c.multiply, c.screen), bg, ColorBlend.MULTIPLY), "group multiply, colors, opacity")
		}
		run {
			val x = cell(1)
			nodes += group("c-out", listOf(quad("c1", x, y, 70f, h, 2, opacity = 0.6f)), Composite(alphaBlend = AlphaBlend.OUT))
			sample(x + 35f, y + 30f, composite(layer(color(sourceColor, 0.6f)), bg, alpha = AlphaBlend.OUT), "group out")
		}
		for ((i, invert) in listOf(2 to false, 3 to true)) {
			val x = cell(i)
			nodes += quad("c$i-mask", x, y, 35f, h, 3, visible = false)
			nodes += group("c-mask-$i", listOf(quad("c$i", x, y, 70f, h, 2)),
				Composite(blend = ColorBlend.HARD_LIGHT, maskedBy = listOf("c$i-mask"), invertMask = invert))
			val drawn = composite(layer(src), bg, ColorBlend.HARD_LIGHT)
			sample(x + 17f, y + 30f, if (invert) bg else drawn, "group mask (inverted $invert) inside")
			sample(x + 52f, y + 30f, if (invert) drawn else bg, "group mask (inverted $invert) outside")
		}
		run {
			val x = cell(4)
			val mask = quad("c4-mask", x, y, 35f, h, 3, visible = false)
			parts += Part("c4-mask-part", "c4-mask-part", listOf(ChildRef.MeshRef(mask.id)))
			nodes += RenderGroup("c4-mask-part", RigIR.DEFAULT_DRAW_ORDER, listOf(mask))
			nodes += group("c-part-mask", listOf(quad("c4", x, y, 70f, h, 2)), Composite(maskedByParts = listOf("c4-mask-part"), opacity = 0.8f))
			sample(x + 17f, y + 30f, composite(layer(src, 0.8f), bg), "group masked by a part inside")
			sample(x + 52f, y + 30f, bg, "group masked by a part outside")
		}
		run {
			val x = cell(5)
			val inner = group("c-inner", listOf(quad("c5-src", x + 23f, y, 47f, h, 2, opacity = 0.7f)), Composite(blend = ColorBlend.SCREEN, opacity = 0.9f))
			nodes += group("c-outer", listOf(quad("c5-back", x, y, 47f, h, 1), inner), Composite(opacity = 0.8f))
			val innerLayer = layer(color(sourceColor, 0.7f), 0.9f)
			sample(x + 10f, y + 30f, composite(layer(back, 0.8f), bg), "nested backdrop")
			sample(x + 35f, y + 30f, composite(layer(composite(innerLayer, back, ColorBlend.SCREEN), 0.8f), bg), "nested overlap")
			sample(x + 60f, y + 30f, composite(layer(composite(innerLayer, FloatArray(4), ColorBlend.SCREEN), 0.8f), bg), "nested source")
		}
		for ((i, invert) in listOf(6 to false, 7 to true)) {
			val x = cell(i)
			nodes += quad("c$i-mask", x, y, 35f, h, 3, visible = false)
			nodes += group("c-mesh-mask-$i", listOf(quad("c$i-back", x, y, 70f, h, 1, opacity = 0.6f),
				quad("c$i", x, y, 70f, h, 2, ColorBlend.HARD_LIGHT, AlphaBlend.ATOP, opacity = 0.8f, maskedBy = listOf("c$i-mask"), invert = invert)))
			val b = color(backdropColor, 0.6f)
			val drawn = composite(layer(composite(color(sourceColor, 0.8f), b, ColorBlend.HARD_LIGHT, AlphaBlend.ATOP)), bg)
			val plain = composite(layer(b), bg)
			sample(x + 17f, y + 30f, if (invert) plain else drawn, "mesh mask in a group (inverted $invert) inside")
			sample(x + 52f, y + 30f, if (invert) drawn else plain, "mesh mask in a group (inverted $invert) outside")
		}
		for ((i, invert) in listOf(8 to false, 9 to true)) {
			val x = cell(i)
			val (blend, alpha) = if (invert) ColorBlend.COLOR_DODGE to AlphaBlend.CONJOINT else ColorBlend.OVERLAY to AlphaBlend.OVER
			nodes += quad("c$i-mask", x, y, 35f, h, 3, visible = false)
			nodes += quad("c$i", x, y, 70f, h, 2, blend, alpha, opacity = 0.8f, maskedBy = listOf("c$i-mask"), invert = invert)
			val drawn = composite(color(sourceColor, 0.8f), bg, blend, alpha)
			sample(x + 17f, y + 30f, if (invert) bg else drawn, "mesh mask (inverted $invert) inside")
			sample(x + 52f, y + 30f, if (invert) drawn else bg, "mesh mask (inverted $invert) outside")
		}
		// Cubism's add and multiply inside a layer keep its alpha; add glow is the W3C add; colors on a translucent layer.
		val y2 = 540f; val h2 = 50f
		for ((i, blend) in listOf(0 to ColorBlend.ADD_PREMULTIPLIED, 1 to ColorBlend.MULTIPLY_PREMULTIPLIED)) {
			val x = cell(i)
			nodes += group("d-$i", listOf(quad("d$i-back", x, y2, 47f, h2, 1, opacity = 0.6f), quad("d$i", x + 23f, y2, 47f, h2, 2, blend, opacity = 0.7f)))
			val b = color(backdropColor, 0.6f); val s = color(sourceColor, 0.7f)
			sample(x + 35f, y2 + 25f, composite(layer(composite(s, b, blend)), bg), "${blend.name} in a group overlap")
			sample(x + 60f, y2 + 25f, composite(layer(composite(s, FloatArray(4), blend)), bg), "${blend.name} in a group source")
		}
		run {
			val x = cell(2)
			nodes += group("d-glow", listOf(quad("d2", x, y2, 70f, h2, 2, opacity = 0.7f)), Composite(blend = ColorBlend.ADD_GLOW))
			sample(x + 35f, y2 + 25f, composite(layer(color(sourceColor, 0.7f)), bg, ColorBlend.ADD_GLOW), "group add glow")
		}
		run {
			val x = cell(3)
			val c = Composite(multiply = Rgb(0.5f, 1f, 0.8f), screen = Rgb(0.4f, 0.1f, 0.2f), opacity = 0.9f)
			nodes += group("d-colors", listOf(quad("d3", x, y2, 70f, h2, 2, opacity = 0.5f)), c)
			sample(x + 35f, y2 + 25f, composite(layer(color(sourceColor, 0.5f), 0.9f, c.multiply, c.screen), bg), "colors on a translucent layer")
		}

		val ir = RigIR(Canvas(800f, 600f), emptyList(), meshes = meshes, parts = parts,
			rootChildren = parts.filter { p -> parts.none { q -> q.children.contains(ChildRef.PartRef(p.id)) } }.map { ChildRef.PartRef(it.id) } +
				meshes.filter { m -> parts.none { p -> p.children.contains(ChildRef.MeshRef(m.id)) } }.map { ChildRef.MeshRef(it.id) },
			textures = Textures(pages = listOf(TexturePage(64, 16, Bytes.wrap(ByteArrayOutputStream().also { ImageIO.write(atlas, "png", it) }.toByteArray())))),
			renderRoot = RenderGroup(null, RigIR.DEFAULT_DRAW_ORDER, nodes))
		File(out, "layers.p2lrt").writeBytes(P2lrt.write(ir))
		File(out, "expected.tsv").writeText(samples.joinToString("\n", postfix = "\n"))
	}
}
