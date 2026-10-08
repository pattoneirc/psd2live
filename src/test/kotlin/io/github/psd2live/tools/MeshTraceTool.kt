package io.github.psd2live.tools

import io.github.psd2live.core.CanvasDensity
import io.github.psd2live.core.LayerSpace
import io.github.psd2live.core.MeshResolution
import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.MeshTrace
import io.github.psd2live.core.MeshUnits
import io.github.psd2live.core.AdaptiveMeshGenerator
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test

/**
 * Meshes synthetic layers with the canvas trace (as projects saved before the texture trace) and the texture
 * trace ([MeshTrace]), side by side, and reports their vertices, time and how much opaque texture each leaves
 * outside the mesh. Writes build/tools/mesh-trace/<case>.png (texture alpha in grey, the mesh in blue, opaque
 * texture pixels outside it in red) and report.txt.
 *
 * [wrap] meshes lashes, hair strands and a hand under the texture trace at several wrap sizes ([MeshSettings.wrap])
 * into build/tools/mesh-wrap/.
 */
class MeshTraceTool {
	private class Case(val name: String, val layer: WorkspaceSourceLayer, val unitScale: Float, val settings: MeshSettings)

	private fun layer(name: String, bounds: LayerBounds, raster: LayerRaster) = WorkspaceSourceLayer(
		LayerId(name), name, "", SourceLayerKind.Raster, true, 0, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		raster, null, null, false)

	private fun raster(width: Int, height: Int, inside: (Double, Double) -> Boolean) = LayerRaster(width, height,
		ByteArray(width * height * 4).also { rgba ->
			for (y in 0 until height) for (x in 0 until width) if (inside((x + 0.5) / width, (y + 0.5) / height)) {
				val o = (y * width + x) * 4; rgba[o] = 70; rgba[o + 1] = 50; rgba[o + 2] = 40; rgba[o + 3] = -1
			}
		})

	private fun nearSegment(u: Double, v: Double, ax: Double, ay: Double, bx: Double, by: Double, radius: Double): Boolean {
		val sx = bx - ax; val sy = by - ay
		val t = (((u - ax) * sx + (v - ay) * sy) / (sx * sx + sy * sy)).coerceIn(0.0, 1.0)
		val dx = u - ax - sx * t; val dy = v - ay - sy * t
		return dx * dx + dy * dy < radius * radius
	}

	/** An almond eye with lashes fanning from the upper lid, the lashes [lash] of the width thick. */
	private fun eye(u: Double, v: Double, lash: Double): Boolean {
		val dx = (u - 0.5) / 0.4; val dy = (v - 0.6) / 0.25
		if (dx * dx + dy * dy <= 1.0) return true
		return (0 until 9).any { k ->
			val a = Math.PI * (0.12 + 0.76 * k / 8.0)
			val bx = 0.5 - 0.4 * cos(a); val by = 0.6 - 0.25 * sin(a)
			nearSegment(u, v, bx, by, bx - 0.16 * cos(a), by - 0.22 * sin(a) - 0.05, lash * 0.5)
		}
	}

	/** Curved hair strands of a few texture pixels, joined at the crown. */
	private fun hair(u: Double, v: Double): Boolean {
		if (v < 0.12 && (u - 0.5) * (u - 0.5) + (v - 0.12) * (v - 0.12) < 0.1) return true
		return (0 until 11).any { k ->
			val x0 = 0.1 + 0.8 * k / 10.0
			val bend = 0.08 * sin(v * 5 + k)
			val width = 0.004 + 0.012 * (1 - v) * (k % 3 + 1) / 3
			v in 0.1..(0.7 + 0.25 * ((k * 7) % 5) / 4.0) && kotlin.math.abs(u - x0 - bend * v) < width
		}
	}

	private val cases = listOf(
		Case("eye-dense-1024-on-64", layer("eye", LayerBounds(0, 0, 64, 64), raster(1024, 1024) { u, v -> eye(u, v, 0.004) }),
			1f, MeshSettings(maxEdgeDistance = 12f, interiorDensity = 18f)),
		Case("eye-dense-512-on-96", layer("eye", LayerBounds(0, 0, 96, 96), raster(512, 512) { u, v -> eye(u, v, 0.008) }),
			1f, MeshSettings(maxEdgeDistance = 12f, interiorDensity = 18f)),
		Case("hair-dense-1024-on-128", layer("hair", LayerBounds(0, 0, 128, 128), raster(1024, 1024, ::hair)),
			1f, MeshSettings(maxEdgeDistance = 16f, interiorDensity = 26f)),
		Case("eye-canvas-64", layer("eye", LayerBounds(0, 0, 64, 64), raster(64, 64) { u, v -> eye(u, v, 0.03) }),
			1f, MeshSettings(maxEdgeDistance = 12f, interiorDensity = 18f)),
		Case("hair-6000doc-1200px", layer("hair", LayerBounds(0, 0, 1200, 1200), raster(1200, 1200, ::hair)),
			MeshResolution.unitScale(MeshUnits.DOCUMENT, 6000, 6000), MeshSettings(maxEdgeDistance = 16f, interiorDensity = 26f)),
		Case("hair-2048doc-1200px", layer("hair", LayerBounds(0, 0, 1200, 1200), raster(1200, 1200, ::hair)),
			1f, MeshSettings(maxEdgeDistance = 16f, interiorDensity = 26f)),
	)

	private class Run(val mesh: AdaptiveMeshGenerator.Result?, val millis: Double, val detail: Float)

	private fun run(case: Case, trace: MeshTrace, wrap: Float = case.settings.wrap): Run {
		val view = CanvasDensity.canvasLayer(case.layer)
		val input = MeshResolution.input(view, trace, case.unitScale)
		val settings = case.settings.copy(wrap = wrap)
		repeat(2) { MeshResolution.mesh(input, 8, settings, null) }
		val started = System.nanoTime()
		val runs = 5
		var mesh: AdaptiveMeshGenerator.Result? = null
		repeat(runs) { mesh = MeshResolution.mesh(input, 8, settings, null) }
		return Run(mesh, (System.nanoTime() - started) / 1e6 / runs, input.detail)
	}

	/** Opaque texture pixels whose centre no triangle covers, and the mesh area in canvas units. */
	private fun coverage(case: Case, mesh: AdaptiveMeshGenerator.Result?): Pair<Int, Double> {
		val raster = case.layer.raster
		val space = LayerSpace.of(case.layer)
		val covered = BooleanArray(raster.width * raster.height)
		var area = 0.0
		if (mesh != null) {
			val p = mesh.positions; val idx = mesh.indices
			fun tx(i: Int) = (p[i * 2] + case.layer.bounds.left - space.left) * space.scaleX.toDouble()
			fun ty(i: Int) = (p[i * 2 + 1] + case.layer.bounds.top - space.top) * space.scaleY.toDouble()
			for (t in idx.indices step 3) {
				val ax = tx(idx[t]); val ay = ty(idx[t]); val bx = tx(idx[t + 1]); val by = ty(idx[t + 1]); val cx = tx(idx[t + 2]); val cy = ty(idx[t + 2])
				val d = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
				area += kotlin.math.abs(d) * 0.5 / (space.scaleX * space.scaleY)
				if (d == 0.0) continue
				for (y in max(0, minOf(ay, by, cy).toInt() - 1)..min(raster.height - 1, maxOf(ay, by, cy).toInt() + 1))
					for (x in max(0, minOf(ax, bx, cx).toInt() - 1)..min(raster.width - 1, maxOf(ax, bx, cx).toInt() + 1)) {
						val px = x + 0.5; val py = y + 0.5
						val w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / d
						val w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / d
						if (w0 >= -1e-6 && w1 >= -1e-6 && w0 + w1 <= 1 + 1e-6) covered[y * raster.width + x] = true
					}
			}
		}
		var missing = 0
		for (i in covered.indices) if ((raster.rgba[i * 4 + 3].toInt() and 0xff) >= 8 && !covered[i]) missing++
		return missing to area
	}

	private fun panel(case: Case, run: Run, title: String, size: Int): BufferedImage {
		val raster = case.layer.raster
		val space = LayerSpace.of(case.layer)
		val image = BufferedImage(size, size + 40, BufferedImage.TYPE_INT_RGB)
		val g = image.createGraphics()
		g.color = Color.WHITE; g.fillRect(0, 0, size, size + 40)
		// Canvas units of the layer rectangle, with a margin, onto the panel.
		val margin = max(space.width, space.height) * 0.06f
		val scale = size / (max(space.width, space.height) + margin * 2)
		fun sx(canvasX: Double) = ((canvasX - space.left + margin) * scale)
		fun sy(canvasY: Double) = ((canvasY - space.top + margin) * scale)
		val mesh = run.mesh
		val covered = BooleanArray(raster.width * raster.height)
		if (mesh != null) {
			val p = mesh.positions
			val probe = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_BYTE_GRAY)
			val pg = probe.createGraphics()
			pg.color = Color.WHITE
			for (t in mesh.indices.indices step 3) {
				val xs = IntArray(3); val ys = IntArray(3)
				for (k in 0..2) {
					val v = mesh.indices[t + k]
					xs[k] = ((p[v * 2] + case.layer.bounds.left - space.left) * space.scaleX).toInt()
					ys[k] = ((p[v * 2 + 1] + case.layer.bounds.top - space.top) * space.scaleY).toInt()
				}
				pg.fillPolygon(xs, ys, 3)
			}
			for (y in 0 until raster.height) for (x in 0 until raster.width) covered[y * raster.width + x] = probe.raster.getSample(x, y, 0) > 0
		}
		for (y in 0 until size) for (x in 0 until size) {
			val canvasX = x / scale - margin + space.left; val canvasY = y / scale - margin + space.top
			val rx = ((canvasX - space.left) * space.scaleX).toInt(); val ry = ((canvasY - space.top) * space.scaleY).toInt()
			if (rx !in 0 until raster.width || ry !in 0 until raster.height) continue
			val alpha = raster.rgba[(ry * raster.width + rx) * 4 + 3].toInt() and 0xff
			if (alpha >= 8) image.setRGB(x, y, if (covered[ry * raster.width + rx]) 0xb8b8b8 else 0xff3030)
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		if (mesh != null) {
			val p = mesh.positions
			g.color = Color(30, 90, 220); g.stroke = BasicStroke(1f)
			for (t in mesh.indices.indices step 3) for (k in 0..2) {
				val a = mesh.indices[t + k]; val b = mesh.indices[t + (k + 1) % 3]
				g.draw(Line2D.Double(sx(p[a * 2] + case.layer.bounds.left.toDouble()), sy(p[a * 2 + 1] + case.layer.bounds.top.toDouble()),
					sx(p[b * 2] + case.layer.bounds.left.toDouble()), sy(p[b * 2 + 1] + case.layer.bounds.top.toDouble())))
			}
		}
		g.color = Color.BLACK; g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
		g.drawString(title, 6, size + 16)
		g.drawString("${(mesh?.positions?.size ?: 0) / 2} vertices, ${"%.1f".format(run.millis)} ms, detail ${"%.2f".format(run.detail)}", 6, size + 33)
		g.dispose()
		return image
	}

	/** A palm with five fingers a few mesh units apart: a wrap wider than their gaps fuses them. */
	private fun hand(u: Double, v: Double): Boolean {
		val dx = (u - 0.5) / 0.3; val dy = (v - 0.72) / 0.22
		if (dx * dx + dy * dy <= 1.0) return true
		val tips = listOf(0.15 to 0.42, 0.37 to 0.12, 0.5 to 0.08, 0.63 to 0.12, 0.76 to 0.26)
		val roots = listOf(0.3, 0.37, 0.5, 0.63, 0.72)
		return tips.indices.any { k -> nearSegment(u, v, roots[k], 0.62, tips[k].first, tips[k].second, 0.045) }
	}

	private val wrapCases = listOf(
		Case("lashes-1024-on-64", layer("eye", LayerBounds(0, 0, 64, 64), raster(1024, 1024) { u, v -> eye(u, v, 0.004) }),
			1f, MeshSettings(maxEdgeDistance = 12f, interiorDensity = 18f)),
		Case("lashes-512-on-160", layer("eye", LayerBounds(0, 0, 160, 160), raster(512, 512) { u, v -> eye(u, v, 0.008) }),
			1f, MeshSettings(maxEdgeDistance = 12f, interiorDensity = 18f)),
		Case("hair-1024-on-128", layer("hair", LayerBounds(0, 0, 128, 128), raster(1024, 1024, ::hair)),
			1f, MeshSettings(maxEdgeDistance = 16f, interiorDensity = 26f)),
		Case("hand-512-on-128", layer("hand", LayerBounds(0, 0, 128, 128), raster(512, 512, ::hand)),
			1f, MeshSettings(maxEdgeDistance = 12f, interiorDensity = 18f)),
	)

	@Test fun wrap() {
		requireTools()
		val out = output("mesh-wrap")
		val wraps = listOf(0f, 2f, 4f, 8f, 16f, 32f)
		val lines = ArrayList<String>()
		lines += "%-22s %6s %8s %6s %9s %10s %12s".format("case", "wrap", "vertices", "loops", "ms", "uncovered", "mesh area")
		for (case in wrapCases) {
			val runs = wraps.map { it to run(case, MeshTrace.TEXTURE, it) }
			for ((wrap, run) in runs) {
				val (missing, area) = coverage(case, run.mesh)
				lines += "%-22s %6.1f %8d %6d %9.2f %10d %12.1f".format(case.name, wrap, (run.mesh?.positions?.size ?: 0) / 2,
					run.mesh?.boundaryLoops?.size ?: 0, run.millis, missing, area)
				println(lines.last())
			}
			val panels = runs.map { (wrap, run) -> panel(case, run, "${case.name} - wrap ${"%.0f".format(wrap)}", 360) }
			val sheet = BufferedImage(panels.sumOf { it.width }, panels.maxOf { it.height }, BufferedImage.TYPE_INT_RGB)
			val g = sheet.createGraphics()
			var x = 0
			for (panel in panels) { g.drawImage(panel, x, 0, null); x += panel.width }
			g.dispose()
			ImageIO.write(sheet, "png", File(out, "${case.name}.png"))
		}
		File(out, "report.txt").writeText(lines.joinToString("\n", postfix = "\n"))
		println("wrote ${out.absolutePath}")
	}

	@Test fun compare() {
		requireTools()
		val out = output("mesh-trace")
		val lines = ArrayList<String>()
		lines += "%-26s %-8s %8s %9s %9s %10s %12s".format("case", "trace", "vertices", "ms", "detail", "uncovered", "mesh area")
		for (case in cases) {
			val runs = MeshTrace.entries.map { it to run(case, it) }
			for ((trace, run) in runs) {
				val (missing, area) = coverage(case, run.mesh)
				lines += "%-26s %-8s %8d %9.2f %9.2f %10d %12.1f".format(case.name, trace.name, (run.mesh?.positions?.size ?: 0) / 2,
					run.millis, run.detail, missing, area)
				println(lines.last())
			}
			val panels = runs.map { (trace, run) -> panel(case, run, "${case.name} - ${trace.name.lowercase()} trace", 480) }
			val sheet = BufferedImage(panels.sumOf { it.width }, panels.maxOf { it.height }, BufferedImage.TYPE_INT_RGB)
			val g = sheet.createGraphics()
			var x = 0
			for (panel in panels) { g.drawImage(panel, x, 0, null); x += panel.width }
			g.dispose()
			ImageIO.write(sheet, "png", File(out, "${case.name}.png"))
		}
		File(out, "report.txt").writeText(lines.joinToString("\n", postfix = "\n"))
		println("wrote ${out.absolutePath}")
	}
}
