package io.github.psd2live.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals

class TextureDensityTest {
    @Test fun densitiesAreContinuousWithinTheRange() {
        assertEquals(1.05f, TextureDensity.clamp(1.05f))
        assertEquals(1.2f, TextureDensity.clamp(1.2f))
        assertEquals(TextureDensity.MAX, TextureDensity.clamp(1000f))
        assertEquals(TextureDensity.MIN, TextureDensity.clamp(0f))
        assertEquals(TextureDensity.MIN, TextureDensity.clamp(Float.NaN))
    }

    @Test fun aCornerDragScalesTheDensityByTheDistanceRatio() {
        // Dragging the corner twice as far from the opposite corner doubles the density.
        assertEquals(2f, TextureDensity.dragged(1f, 100f, 200f))
        assertEquals(0.25f, TextureDensity.dragged(0.5f, 100f, 50f))
        // No steps: a small move gives a small change.
        assertEquals(1.03f, TextureDensity.dragged(1f, 100f, 103f), 1e-5f)
        // A drag through the opposite corner bottoms out at the minimum instead of flipping.
        assertEquals(TextureDensity.MIN, TextureDensity.dragged(1f, 100f, 0f))
        assertEquals(1.5f, TextureDensity.dragged(1.5f, 0f, 40f))
    }
}
