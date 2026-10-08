package io.github.psd2live.format.compile.render

import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.PlacedRaster
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.compile.RasterImage
import io.github.psd2live.format.model.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a posed rig into pixels in premultiplied floating point: textured triangles sampled bilinearly,
 * multiply and screen colors, masks by their texture alpha (inverted ones too), and every color blend
 * mode: Cubism's additive and multiply, and the separable and non-separable modes of the W3C compositing
 * specification. Meshes draw back to front by draw order, keeping rig order on ties; hidden meshes and
 * meshes under hidden parts are skipped. Group composites (isolated parts) and alpha blend modes other
 * than over are not drawn and are reported by [unsupported].
 */
public class SoftwareRasterizer(
	private val ir: RigIR,
	/**
	 * The ARGB pixels (row by row, never modified here) a page's PNG decodes to, when the host already holds
	 * them; null decodes the PNG.
	 */
	pixels: (ByteArray) -> IntArray? = { null },
) {
	/** A page as straight ARGB; texels are premultiplied as they are sampled. */
	private class Texture(val width: Int, val height: Int, val argb: IntArray)

	// Pages decode in parallel; their order is kept.
	private val textures: List<Texture> = ir.textures.pages.withIndex().toList().parallelStream().map { (index, page) ->
		val png = page.png.shared()
		pixels(png)?.takeIf { it.size == page.width * page.height }?.let { Texture(page.width, page.height, it) } ?: run {
			val image = requireNotNull(ImageIO.read(ByteArrayInputStream(png))) { "Texture page $index is not a PNG" }
			Texture(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
		}
	}.toList()

	private val hidden: Set<String> = run {
		val out = HashSet<String>()
		fun hide(part: Part) {
			for (child in part.children) when (child) {
				is ChildRef.MeshRef -> out += child.id
				is ChildRef.PartRef -> ir.parts.firstOrNull { it.id == child.id }?.let(::hide)
			}
		}
		ir.parts.filter { !it.visible }.forEach(::hide)
		ir.meshes.filter { !it.visible || it.geometry == null }.forEach { out += it.id }
		out
	}

	private val meshes = ir.meshes.associateBy { it.id }
	private val order = ir.meshes.withIndex().associate { it.value.id to it.index }

	/** What this renderer draws differently from the editor: group composites and non-over alpha blending. */
	public val unsupported: List<String> =
		ir.parts.filter { it.groupMode == GroupMode.ISOLATED }.map { it.id } + ir.meshes.filter { it.alphaBlend != AlphaBlend.OVER }.map { it.id }

	/**
	 * The rig at [pose] with [colors] into [frame]; [only] limits drawing to those meshes (their masks still
	 * apply).
	 */
	public fun render(pose: PoseGeometry, colors: Map<String, IrColors.MeshColor>, frame: FrameSpec, only: Set<String>? = null): RasterImage =
		render(pose, colors, frame, only, 0, 0, frame.outputWidth - 1, frame.outputHeight - 1)

	/**
	 * [mesh] alone at [pose] (its masks still apply), drawn only over the frame pixels its vertices can reach:
	 * each pixel is exactly the one [render] draws there with `only = setOf(mesh)`, which is transparent
	 * outside the returned rectangle. Null when the mesh reaches no pixel of [frame].
	 */
	public fun renderMesh(pose: PoseGeometry, colors: Map<String, IrColors.MeshColor>, frame: FrameSpec, mesh: String): PlacedRaster? =
		region(pose, frame, mesh)?.let { (x0, y0, x1, y1) -> PlacedRaster(x0, y0, render(pose, colors, frame, setOf(mesh), x0, y0, x1, y1)) }

	/**
	 * [meshes] each by [renderMesh], trimmed to their drawn pixels ([PlacedRaster.trimmed]), drawn in parallel
	 * as far as memory allows.
	 */
	public fun renderMeshes(pose: PoseGeometry, colors: Map<String, IrColors.MeshColor>, frame: FrameSpec, meshes: List<String>): List<PlacedRaster?> {
		val regions = meshes.map { region(pose, frame, it) }
		return bounded(meshes.size, { i -> regions[i]?.let { (x0, y0, x1, y1) -> (x1 - x0 + 1).toLong() * (y1 - y0 + 1) * BYTES_PER_PIXEL } ?: 0L }) { i ->
			regions[i]?.let { (x0, y0, x1, y1) -> PlacedRaster(x0, y0, render(pose, colors, frame, setOf(meshes[i]), x0, y0, x1, y1)).trimmed() }
		}
	}

	/** Whole frames at each pose with its colors, as [render] draws them, drawn in parallel as far as memory allows. */
	public fun renderFrames(poses: List<Pair<PoseGeometry, Map<String, IrColors.MeshColor>>>, frame: FrameSpec): List<RasterImage> =
		bounded(poses.size, { frame.outputWidth.toLong() * frame.outputHeight * BYTES_PER_PIXEL }) { i -> render(poses[i].first, poses[i].second, frame) }

	/** The frame pixels (x0, y0, x1, y1, inclusive) [triangles] can scan for [mesh], or null when there are none. */
	private fun region(pose: PoseGeometry, frame: FrameSpec, mesh: String): IntArray? {
		val w = frame.outputWidth; val h = frame.outputHeight
		val p = pose.positions[mesh] ?: return null
		val scale = min(w / frame.width, h / frame.height)
		var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
		var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
		for (i in 0 until p.size / 2) {
			val x = (p[i * 2] - frame.left) * scale; val y = (p[i * 2 + 1] - frame.top) * scale
			// A vertex off the number line: let the triangles decide, over the whole frame.
			if (!x.isFinite() || !y.isFinite()) return intArrayOf(0, 0, w - 1, h - 1)
			minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
		}
		// The union of the per-triangle ranges [triangles] computes, from the same coordinates.
		val x0 = max(0, floor(minX - 0.5f).toInt()); val x1 = min(w - 1, ceil(maxX - 0.5f).toInt())
		val y0 = max(0, floor(minY - 0.5f).toInt()); val y1 = min(h - 1, ceil(maxY - 0.5f).toInt())
		return if (x0 > x1 || y0 > y1) null else intArrayOf(x0, y0, x1, y1)
	}

	/** The pixels [x0]..[x1] by [y0]..[y1] (inclusive) of the full render of [frame]. */
	private fun render(pose: PoseGeometry, colors: Map<String, IrColors.MeshColor>, frame: FrameSpec, only: Set<String>?,
	                   x0: Int, y0: Int, x1: Int, y1: Int): RasterImage {
		val w = x1 - x0 + 1; val h = y1 - y0 + 1
		val out = FloatArray(w * h * 4)
		if (frame.background != 0) {
			val a = (frame.background ushr 24) / 255f
			val r = ((frame.background shr 16) and 0xff) / 255f * a; val g = ((frame.background shr 8) and 0xff) / 255f * a; val b = (frame.background and 0xff) / 255f * a
			for (i in 0 until w * h) { out[i * 4] = r; out[i * 4 + 1] = g; out[i * 4 + 2] = b; out[i * 4 + 3] = a }
		}
		val scale = min(frame.outputWidth / frame.width, frame.outputHeight / frame.height)
		val view = View(scale, frame.left, frame.top, x0, y0, x1, y1)
		val drawn = ir.meshes.filter { it.id !in hidden && (only == null || it.id in only) && pose.positions.containsKey(it.id) }
			.sortedWith(compareBy({ pose.drawOrder[it.id] ?: it.drawOrder }, { order.getValue(it.id) }))
		val masks = HashMap<List<String>, FloatArray>()
		for (mesh in drawn) {
			val opacity = (pose.opacity[mesh.id] ?: mesh.opacity).coerceIn(0f, 1f)
			if (opacity <= 0f) continue
			val coverage = if (mesh.maskedBy.isEmpty()) null else masks.getOrPut(mesh.maskedBy) { mask(mesh.maskedBy, pose, view) }
			val color = colors[mesh.id] ?: IrColors.MeshColor(mesh.multiply, mesh.screen)
			drawMesh(mesh, pose, view, out, opacity, color, coverage, mesh.invertMask)
		}
		val argb = IntArray(w * h)
		for (i in argb.indices) {
			val a = out[i * 4 + 3].coerceIn(0f, 1f)
			if (a <= 0f) continue
			fun channel(v: Float) = (v / a).coerceIn(0f, 1f).times(255f).plus(0.5f).toInt()
			argb[i] = ((a * 255f + 0.5f).toInt() shl 24) or (channel(out[i * 4]) shl 16) or (channel(out[i * 4 + 1]) shl 8) or channel(out[i * 4 + 2])
		}
		return RasterImage(w, h, argb)
	}

	/**
	 * Frame coordinates by [scale] from ([left], [top]); only the pixels [x0]..[x1] by [y0]..[y1] are drawn,
	 * into buffers of that rectangle. Coordinates stay those of the whole frame, so every pixel computes
	 * exactly as in a full render.
	 */
	private class View(val scale: Float, val left: Float, val top: Float, val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
		val width: Int = x1 - x0 + 1
		val height: Int = y1 - y0 + 1
		fun x(v: Float) = (v - left) * scale
		fun y(v: Float) = (v - top) * scale
	}

	private fun texture(mesh: Mesh): Texture? = textures.getOrNull(ir.textures.bindings[mesh.id] ?: mesh.page)

	/** Coverage of the union of [ids] by their texture alpha, 0..1 per pixel. */
	private fun mask(ids: List<String>, pose: PoseGeometry, view: View): FloatArray {
		val coverage = FloatArray(view.width * view.height)
		val sample = FloatArray(4)
		for (id in ids) {
			val mesh = meshes[id] ?: continue
			val texture = texture(mesh) ?: continue
			triangles(mesh, pose, view) { pixel, u, v ->
				sampleTexture(texture, u, v, sample)
				coverage[pixel] = max(coverage[pixel], sample[3])
			}
		}
		return coverage
	}

	private fun drawMesh(mesh: Mesh, pose: PoseGeometry, view: View, out: FloatArray, opacity: Float, color: IrColors.MeshColor,
	                     coverage: FloatArray?, invert: Boolean) {
		val texture = texture(mesh) ?: return
		val s = FloatArray(4)
		val mr = color.multiply.red; val mg = color.multiply.green; val mb = color.multiply.blue
		val sr = color.screen.red; val sg = color.screen.green; val sb = color.screen.blue
		triangles(mesh, pose, view) { pixel, u, v ->
			sampleTexture(texture, u, v, s)
			var a = s[3]
			if (a <= 0f) return@triangles
			// Multiply, then screen, on premultiplied color: c' = c·m, then c' + s·a − c'·s.
			var r = s[0] * mr; var g = s[1] * mg; var b = s[2] * mb
			r += sr * a - r * sr; g += sg * a - g * sg; b += sb * a - b * sb
			var k = opacity
			if (coverage != null) k *= if (invert) 1f - coverage[pixel] else coverage[pixel]
			if (k <= 0f) return@triangles
			r *= k; g *= k; b *= k; a *= k
			blend(mesh.blend, out, pixel * 4, r, g, b, a)
		}
	}

	/** Calls [fragment] for every pixel center inside [mesh]'s triangles with its texture coordinates. */
	private inline fun triangles(mesh: Mesh, pose: PoseGeometry, view: View, fragment: (Int, Float, Float) -> Unit) {
		val g = mesh.geometry ?: return
		val p = pose.positions[mesh.id] ?: return
		val uv = g.uvs.shared()
		val idx = g.indices
		for (t in 0 until idx.size / 3) {
			val ia = idx[t * 3]; val ib = idx[t * 3 + 1]; val ic = idx[t * 3 + 2]
			val ax = view.x(p[ia * 2]); val ay = view.y(p[ia * 2 + 1])
			val bx = view.x(p[ib * 2]); val by = view.y(p[ib * 2 + 1])
			val cx = view.x(p[ic * 2]); val cy = view.y(p[ic * 2 + 1])
			val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
			if (abs(area) < 1e-12f) continue
			val x0 = max(view.x0, floor(min(ax, min(bx, cx)) - 0.5f).toInt()); val x1 = min(view.x1, ceil(max(ax, max(bx, cx)) - 0.5f).toInt())
			val y0 = max(view.y0, floor(min(ay, min(by, cy)) - 0.5f).toInt()); val y1 = min(view.y1, ceil(max(ay, max(by, cy)) - 0.5f).toInt())
			if (x0 > x1 || y0 > y1) continue
			val inv = 1f / area
			for (y in y0..y1) {
				val py = y + 0.5f
				for (x in x0..x1) {
					val px = x + 0.5f
					// Barycentric weights; a top-left rule keeps shared edges from drawing twice.
					val w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) * inv
					val w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) * inv
					val w2 = 1f - w0 - w1
					if (!inside(w0, by - cy, cx - bx) || !inside(w1, cy - ay, ax - cx) || !inside(w2, ay - by, bx - ax)) continue
					val u = w0 * uv[ia * 2] + w1 * uv[ib * 2] + w2 * uv[ic * 2]
					val v = w0 * uv[ia * 2 + 1] + w1 * uv[ib * 2 + 1] + w2 * uv[ic * 2 + 1]
					fragment((y - view.y0) * view.width + x - view.x0, u, v)
				}
			}
		}
	}

	/** Inside the edge with weight [w]: positive, or zero on a top or left edge (by the edge's direction). */
	private fun inside(w: Float, dy: Float, dx: Float): Boolean = w > 0f || (w == 0f && (dy > 0f || (dy == 0f && dx < 0f)))

	private fun sampleTexture(t: Texture, u: Float, v: Float, out: FloatArray) {
		val fx = (u * t.width - 0.5f).coerceIn(0f, (t.width - 1).toFloat())
		val fy = (v * t.height - 0.5f).coerceIn(0f, (t.height - 1).toFloat())
		val x0 = fx.toInt(); val y0 = fy.toInt()
		val x1 = min(x0 + 1, t.width - 1); val y1 = min(y0 + 1, t.height - 1)
		val tx = fx - x0; val ty = fy - y0
		val p = t.argb
		val pa = p[y0 * t.width + x0]; val pb = p[y0 * t.width + x1]
		val pd = p[y1 * t.width + x0]; val pe = p[y1 * t.width + x1]
		for (c in 0 until 4) {
			val shift = CHANNEL_SHIFT[c]
			val a = premultiplied(pa, shift); val b = premultiplied(pb, shift)
			val d = premultiplied(pd, shift); val e = premultiplied(pe, shift)
			out[c] = (a + (b - a) * tx) * (1f - ty) + (d + (e - d) * tx) * ty
		}
	}

	/** The channel at [shift] of [argb] premultiplied by its alpha, 0..1; the alpha itself at shift 24. */
	private fun premultiplied(argb: Int, shift: Int): Float {
		val a = UNIT[argb ushr 24]
		return if (shift == 24) a else UNIT[(argb shr shift) and 0xff] * a
	}

	private fun blend(mode: ColorBlend, out: FloatArray, at: Int, sr: Float, sg: Float, sb: Float, sa: Float) {
		val dr = out[at]; val dg = out[at + 1]; val db = out[at + 2]; val da = out[at + 3]
		when (mode) {
			ColorBlend.NORMAL -> {
				out[at] = sr + dr * (1f - sa); out[at + 1] = sg + dg * (1f - sa); out[at + 2] = sb + db * (1f - sa); out[at + 3] = sa + da * (1f - sa)
			}
			// Cubism's additive and multiply keep the destination alpha.
			ColorBlend.ADD_PREMULTIPLIED, ColorBlend.ADD_GLOW -> { out[at] = dr + sr; out[at + 1] = dg + sg; out[at + 2] = db + sb }
			ColorBlend.MULTIPLY_PREMULTIPLIED -> {
				out[at] = sr * dr + dr * (1f - sa); out[at + 1] = sg * dg + dg * (1f - sa); out[at + 2] = sb * db + db * (1f - sa)
			}
			else -> {
				// W3C compositing: (1 − da)·s + (1 − sa)·d + sa·da·B(Cs, Cd) over unpremultiplied colors.
				val cs = if (sa > 0f) floatArrayOf(sr / sa, sg / sa, sb / sa) else floatArrayOf(0f, 0f, 0f)
				val cd = if (da > 0f) floatArrayOf(dr / da, dg / da, db / da) else floatArrayOf(0f, 0f, 0f)
				val mixed = mix(mode, cs, cd)
				val k = sa * da
				out[at] = (1f - da) * sr + (1f - sa) * dr + k * mixed[0]
				out[at + 1] = (1f - da) * sg + (1f - sa) * dg + k * mixed[1]
				out[at + 2] = (1f - da) * sb + (1f - sa) * db + k * mixed[2]
				out[at + 3] = sa + da - sa * da
			}
		}
	}

	private fun mix(mode: ColorBlend, s: FloatArray, d: FloatArray): FloatArray = when (mode) {
		ColorBlend.HUE -> setLum(setSat(s, sat(d)), lum(d))
		ColorBlend.COLOR -> setLum(s, lum(d))
		else -> FloatArray(3) { separable(mode, s[it], d[it]) }
	}

	private fun separable(mode: ColorBlend, s: Float, d: Float): Float = when (mode) {
		ColorBlend.ADD -> min(1f, s + d)
		ColorBlend.MULTIPLY -> s * d
		ColorBlend.SCREEN -> s + d - s * d
		ColorBlend.DARKEN -> min(s, d)
		ColorBlend.LIGHTEN -> max(s, d)
		ColorBlend.OVERLAY -> hardLight(d, s)
		ColorBlend.HARD_LIGHT -> hardLight(s, d)
		ColorBlend.COLOR_DODGE -> when { d == 0f -> 0f; s >= 1f -> 1f; else -> min(1f, d / (1f - s)) }
		ColorBlend.COLOR_BURN -> when { d >= 1f -> 1f; s <= 0f -> 0f; else -> 1f - min(1f, (1f - d) / s) }
		ColorBlend.LINEAR_BURN -> max(0f, s + d - 1f)
		ColorBlend.LINEAR_LIGHT -> (d + 2f * s - 1f).coerceIn(0f, 1f)
		ColorBlend.SOFT_LIGHT -> if (s <= 0.5f) d - (1f - 2f * s) * d * (1f - d) else {
			val dd = if (d <= 0.25f) ((16f * d - 12f) * d + 4f) * d else kotlin.math.sqrt(d)
			d + (2f * s - 1f) * (dd - d)
		}
		else -> s
	}

	private fun hardLight(s: Float, d: Float) = if (s <= 0.5f) d * 2f * s else { val t = 2f * s - 1f; d + t - d * t }

	private fun lum(c: FloatArray) = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]

	private fun clip(c: FloatArray): FloatArray {
		val l = lum(c); val n = min(c[0], min(c[1], c[2])); val x = max(c[0], max(c[1], c[2]))
		val out = c.copyOf()
		if (n < 0f) for (i in 0..2) out[i] = l + (out[i] - l) * l / (l - n)
		if (x > 1f) for (i in 0..2) out[i] = l + (out[i] - l) * (1f - l) / (x - l)
		return out
	}

	private fun setLum(c: FloatArray, l: Float): FloatArray { val d = l - lum(c); return clip(FloatArray(3) { c[it] + d }) }

	private fun sat(c: FloatArray) = max(c[0], max(c[1], c[2])) - min(c[0], min(c[1], c[2]))

	private fun setSat(c: FloatArray, s: Float): FloatArray {
		val out = FloatArray(3)
		val order = (0..2).sortedBy { c[it] }
		val (lo, mid, hi) = order
		if (c[hi] > c[lo]) { out[mid] = (c[mid] - c[lo]) * s / (c[hi] - c[lo]); out[hi] = s } else { out[mid] = 0f; out[hi] = 0f }
		out[lo] = 0f
		return out
	}

	private companion object {
		/** Working memory of one drawn pixel: the float color, a mask's coverage and the packed result. */
		const val BYTES_PER_PIXEL = 24L

		/** Each 8-bit value over 255, as dividing gives it. */
		val UNIT = FloatArray(256) { it / 255f }

		/** Red, green, blue and alpha's bit offsets in ARGB. */
		val CHANNEL_SHIFT = intArrayOf(16, 8, 0, 24)

		/**
		 * [task] over 0 until [count] in parallel, results in order, holding at most about a third of the heap in
		 * the [bytes] the tasks say they use at once.
		 */
		fun <R> bounded(count: Int, bytes: (Int) -> Long, task: (Int) -> R): List<R> {
			val results = arrayOfNulls<Any?>(count)
			if (count <= 1) { for (i in 0 until count) results[i] = task(i) } else {
				val unit = 1L shl 16
				val permits = (Runtime.getRuntime().maxMemory() / 3 / unit).coerceIn(1024L, Int.MAX_VALUE.toLong()).toInt()
				val budget = java.util.concurrent.Semaphore(permits)
				java.util.stream.IntStream.range(0, count).parallel().forEach { i ->
					val need = ((bytes(i) + unit - 1) / unit).coerceIn(1L, permits.toLong()).toInt()
					budget.acquireUninterruptibly(need)
					try { results[i] = task(i) } finally { budget.release(need) }
				}
			}
			@Suppress("UNCHECKED_CAST")
			return results.asList() as List<R>
		}
	}
}
