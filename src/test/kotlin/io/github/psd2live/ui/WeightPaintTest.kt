package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightPaintTest {
    @Test fun weightModesCombineReachWithTheGroup() {
        val base = floatArrayOf(0.2f, 0.5f, 0.9f)
        val reach = floatArrayOf(1f, 0.5f, 0f)
        assertContentEquals(floatArrayOf(0.7f, 0.75f, 0.9f), WeightPaint.apply(base, reach, WeightPaintMode.ADD, 0.5f), 1e-5f)
        assertContentEquals(floatArrayOf(0f, 0.25f, 0.9f), WeightPaint.apply(base, reach, WeightPaintMode.SUBTRACT, 0.5f), 1e-5f)
        assertContentEquals(floatArrayOf(1f, 0.75f, 0.9f), WeightPaint.apply(base, reach, WeightPaintMode.SET, 1f), 1e-5f)
        assertEquals(WeightPaintMode.SUBTRACT, WeightPaint.effective(WeightPaintMode.ADD, alt = true))
        assertEquals(WeightPaintMode.SMOOTH, WeightPaint.effective(WeightPaintMode.SMOOTH, alt = true))
    }

    @Test fun smoothingEvensAReachedPointTowardItsNeighbours() {
        // A strip 0 - 1 - 2 with a spike in the middle: only the middle is reached.
        val neighbors = listOf(intArrayOf(1), intArrayOf(0, 2), intArrayOf(1))
        val out = WeightPaint.apply(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f), WeightPaintMode.SMOOTH, 1f, neighbors)
        assertEquals(0f, out[0])
        assertEquals(0f, out[2])
        assertTrue(out[1] < 0.1f, "the spike is smoothed away: ${out[1]}")
    }

    @Test fun gradientRunsFullAtTheStartToNothingAtTheEnd() {
        val points = listOf(Offset(-5f, 0f), Offset(0f, 3f), Offset(5f, -2f), Offset(10f, 0f), Offset(20f, 0f))
        val reach = WeightPaint.gradient(points, Offset(0f, 0f), Offset(10f, 0f))
        assertContentEquals(floatArrayOf(1f, 1f, 0.5f, 0f, 0f), reach, 1e-5f)
        assertTrue(WeightPaint.gradient(points, Offset(1f, 1f), Offset(1.2f, 1f)).all { it == 0f })
    }

    private fun assertContentEquals(expected: FloatArray, actual: FloatArray, tolerance: Float) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i], tolerance, "at $i")
    }
}
