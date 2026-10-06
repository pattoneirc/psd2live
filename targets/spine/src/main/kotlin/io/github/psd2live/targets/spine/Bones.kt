package io.github.psd2live.targets.spine

import kotlin.math.cos
import kotlin.math.sin

/** A 2D affine transform in skeleton space (y up): the linear part (a b; c d) and a translation. */
internal class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val x: Float, val y: Float) {
	operator fun times(o: Affine) = Affine(a * o.a + b * o.c, a * o.b + b * o.d, c * o.a + d * o.c, c * o.b + d * o.d,
		a * o.x + b * o.y + x, c * o.x + d * o.y + y)

	fun applyX(px: Float, py: Float) = a * px + b * py + x
	fun applyY(px: Float, py: Float) = c * px + d * py + y

	fun inverse(): Affine {
		val det = a * d - b * c
		val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
		return Affine(ia, ib, ic, id, -(ia * x + ib * y), -(ic * x + id * y))
	}

	/** Every vertex of [points] (x, y pairs) through this transform. */
	fun map(points: FloatArray) = FloatArray(points.size) { if (it % 2 == 0) applyX(points[it], points[it + 1]) else applyY(points[it - 1], points[it]) }

	companion object {
		val Identity = Affine(1f, 0f, 0f, 1f, 0f, 0f)

		/** A Spine bone's local transform without shear: rotation then scale, in degrees. */
		fun local(x: Float, y: Float, rotation: Float, scaleX: Float, scaleY: Float): Affine {
			val r = Math.toRadians(rotation.toDouble())
			val c = cos(r).toFloat(); val s = sin(r).toFloat()
			return Affine(c * scaleX, -s * scaleY, s * scaleX, c * scaleY, x, y)
		}
	}
}

/** A bone's local values, as Spine stores them. */
internal class BoneLocal(val x: Float, val y: Float, val rotation: Float, val scaleX: Float, val scaleY: Float) {
	fun affine() = Affine.local(x, y, rotation, scaleX, scaleY)
}

/**
 * A rotation deformer's frame in skeleton space: canvas [angle] (y down, so it turns the other way in
 * skeleton space), uniform [scale], flips, and the bone's own constant turn [turn] (degrees, skeleton space)
 * that points the bone along its content. Bone coordinates b map to the deformer's local coordinates
 * (x, -y) turned by [turn].
 */
internal class BoneFrame(val x: Float, val y: Float, val angle: Float, val scale: Float, val flipX: Boolean, val flipY: Boolean, val turn: Float) {
	private val fx get() = if (flipX) -1f else 1f
	private val fy get() = if (flipY) -1f else 1f

	val world: Affine by lazy {
		Affine(1f, 0f, 0f, 1f, x, y) * Affine.local(0f, 0f, -angle, scale * fx, scale * fy) * Affine.local(0f, 0f, turn, 1f, 1f)
	}

	/**
	 * This frame relative to [parent], as a Spine bone's local values under normal inheritance: the rotation is
	 * built from the raw angles (no wrapping), so keyed turns past half a circle stay what they are. Frames are
	 * rotations and uniform scales with flips, closed under composition, so no shear is ever needed.
	 */
	fun relativeTo(parent: BoneFrame): BoneLocal {
		val k = scale / parent.scale
		val sigmaParent = parent.fx * parent.fy
		val gx = parent.fx * fx; val gy = parent.fy * fy
		val rotation = -parent.turn - sigmaParent * (angle - parent.angle) + gx * gy * turn
		val inverse = parent.world.inverse()
		return BoneLocal(inverse.applyX(x, y), inverse.applyY(x, y), rotation, k * gx, k * gy)
	}

	companion object {
		val Root = BoneFrame(0f, 0f, 0f, 1f, false, false, 0f)
	}
}
