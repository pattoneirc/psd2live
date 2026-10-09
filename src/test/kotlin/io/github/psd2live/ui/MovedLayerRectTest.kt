package io.github.psd2live.ui

import io.github.psd2live.project.LayerCanvasRect
import kotlin.math.*
import kotlin.test.*

class MovedLayerRectTest {
    /** A small mesh well inside its layer, in world coordinates (canvas pixels, y up). */
    private val mesh = floatArrayOf(110f, -220f, 150f, -220f, 150f, -260f, 110f, -260f, 130f, -240f)
    private val layer = LayerCanvasRect(100f, 200f, 60f, 80f)

    private fun map(points: FloatArray, f: (Float, Float) -> Pair<Float, Float>) = FloatArray(points.size).also { out ->
        for (i in 0 until points.size / 2) f(points[i * 2], points[i * 2 + 1]).let { (x, y) -> out[i * 2] = x; out[i * 2 + 1] = y }
    }

    private fun assertRect(expected: LayerCanvasRect, actual: LayerCanvasRect?) {
        assertNotNull(actual)
        assertEquals(expected.left, actual.left, 1e-3f); assertEquals(expected.top, actual.top, 1e-3f)
        assertEquals(expected.width, actual.width, 1e-3f); assertEquals(expected.height, actual.height, 1e-3f)
    }

    @Test fun aMoveCarriesTheLayerRectangleWithTheMesh() {
        // 15 px right and 30 px down on the canvas: world y falls by 30.
        val moved = map(mesh) { x, y -> x + 15f to y - 30f }
        assertRect(LayerCanvasRect(115f, 230f, 60f, 80f), movedLayerRect(mesh, moved, layer))
    }

    @Test fun aScaleScalesTheRectangleAboutTheSameFixedPoint() {
        // Twice as wide and half as tall, about the mesh's top-left corner (110, 220 on the canvas).
        val scaled = map(mesh) { x, y -> 110f + (x - 110f) * 2f to -220f + (y + 220f) * 0.5f }
        assertRect(LayerCanvasRect(90f, 210f, 120f, 40f), movedLayerRect(mesh, scaled, layer))
    }

    @Test fun aTurnFlipOrReshapeKeepsTheMeshEdit() {
        val turned = map(mesh) { x, y -> val a = 10f * PI.toFloat() / 180f; x * cos(a) - y * sin(a) to x * sin(a) + y * cos(a) }
        assertNull(movedLayerRect(mesh, turned, layer))
        val flipped = map(mesh) { x, y -> 260f - x to y }
        assertNull(movedLayerRect(mesh, flipped, layer))
        val reshaped = mesh.copyOf().also { it[8] += 5f }
        assertNull(movedLayerRect(mesh, reshaped, layer))
    }
}
