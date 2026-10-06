package io.github.psd2live.format.compile

import kotlin.math.abs

/**
 * Keyframe reduction for sampled tracks: keeps a key only where linear interpolation between the kept keys
 * around it would miss a sample by more than [tolerance] (in the track's units). The first and last keys
 * always stay, so the reduced track still covers the full time range.
 */
public object KeyReduction {
	/** Indices of the keys to keep from [times] (ascending) and their [values] (one array per key, equal sizes). */
	public fun reduce(times: List<Float>, values: List<FloatArray>, tolerance: Float): List<Int> {
		require(times.size == values.size) { "One value array per key" }
		if (times.size <= 2) return times.indices.toList()
		val kept = arrayListOf(0)
		var anchor = 0
		var candidate = 1
		while (candidate < times.size - 1) {
			// Extend the segment from the anchor while every skipped sample stays within tolerance.
			val end = candidate + 1
			if (fits(times, values, anchor, end, tolerance)) candidate = end
			else { kept += candidate; anchor = candidate; candidate = anchor + 1 }
		}
		kept += times.size - 1
		return kept
	}

	private fun fits(times: List<Float>, values: List<FloatArray>, from: Int, to: Int, tolerance: Float): Boolean {
		val t0 = times[from]; val t1 = times[to]; val a = values[from]; val b = values[to]
		for (k in from + 1 until to) {
			val u = if (t1 == t0) 0f else (times[k] - t0) / (t1 - t0)
			val v = values[k]
			for (i in v.indices) if (abs(a[i] + (b[i] - a[i]) * u - v[i]) > tolerance) return false
		}
		return true
	}
}
