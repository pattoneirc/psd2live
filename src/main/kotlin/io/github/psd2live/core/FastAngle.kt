package io.github.psd2live.core

import kotlin.math.abs

/**
 * atan2 in float precision: [y], [x] folded into the first octant, then past tan(π/8) turned back by π/4, where
 * the Taylor series to t¹⁵ is within 2e-8 rad (2.7e-7 at worst over the circle, the float spacing near π).
 * Several times quicker than [kotlin.math.atan2], which the JVM computes in software.
 */
internal fun fastAtan2(y: Float, x: Float): Float {
	val ax = abs(x); val ay = abs(y)
	if (ax == 0f && ay == 0f) return 0f
	val a = if (ay <= ax) ay / ax else ax / ay
	val turned = a > TAN_PI_8
	val t = if (turned) (a - 1f) / (a + 1f) else a
	val t2 = t * t
	var r = t * (1f + t2 * (-1f / 3 + t2 * (1f / 5 + t2 * (-1f / 7 + t2 * (1f / 9 + t2 * (-1f / 11 + t2 * (1f / 13 - t2 / 15)))))))
	if (turned) r += QUARTER_PI
	if (ay > ax) r = HALF_PI - r
	if (x < 0f) r = PI_F - r
	return if (y < 0f) -r else r
}

private const val PI_F = 3.1415927f
private const val HALF_PI = PI_F / 2
private const val QUARTER_PI = PI_F / 4
private const val TAN_PI_8 = 0.41421357f
