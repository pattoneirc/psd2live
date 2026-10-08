package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LayerSelectionRangeTest {
    @Test fun layerSelectionRangeFollowsTheAnchor() {
        assertEquals(listOf("b", "c"), layerSelectionRange(listOf("a", "b", "c", "d"), "c", "b"))
        assertEquals(listOf("z"), layerSelectionRange(listOf("a", "b"), null, "z"))
    }
}
