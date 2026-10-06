package io.github.psd2live.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals

class TextureDensityTest {
    @Test fun snapStepsAlongQuarterPowersOfTwoWithinTheRange() {
        assertEquals(1f, TextureDensity.snap(1.05f))
        assertEquals(2f, TextureDensity.snap(1.95f))
        assertEquals(TextureDensity.pow2(0.25f), TextureDensity.snap(1.2f))
        assertEquals(TextureDensity.MAX, TextureDensity.snap(1000f))
        assertEquals(TextureDensity.MIN, TextureDensity.snap(0f))
        assertEquals(TextureDensity.MIN, TextureDensity.snap(Float.NaN))
    }

    @Test fun aCornerDragScalesTheDensityByTheDistanceRatio() {
        // Dragging the corner twice as far from the opposite corner doubles the density.
        assertEquals(2f, TextureDensity.dragged(1f, 100f, 200f))
        assertEquals(0.25f, TextureDensity.dragged(0.5f, 100f, 50f))
        // Small jitter around the start stays on the current step.
        assertEquals(1f, TextureDensity.dragged(1f, 100f, 103f))
        // A drag through the opposite corner bottoms out at the minimum instead of flipping.
        assertEquals(TextureDensity.MIN, TextureDensity.dragged(1f, 100f, 0f))
        assertEquals(1.5f, TextureDensity.dragged(1.5f, 0f, 40f))
    }
}
