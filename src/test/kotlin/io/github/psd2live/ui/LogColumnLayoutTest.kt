package io.github.psd2live.ui

import io.github.psd2live.ui.views.LogColumn
import io.github.psd2live.ui.views.LogColumnLayout
import kotlin.test.*

class LogColumnLayoutTest {
    @Test fun aLayoutSurvivesItsEncoding() {
        val layout = LogColumnLayout().moved(LogColumn.MESSAGE, 1).resized(LogColumn.TAG, 90f).toggled(LogColumn.SOURCE)
        assertEquals("TIME:50,MESSAGE,-SOURCE:40,TAG:90", layout.encode())
        assertEquals(layout, LogColumnLayout.decode(layout.encode()))
        assertEquals(listOf(LogColumn.TIME, LogColumn.MESSAGE, LogColumn.TAG), layout.visible)
    }

    @Test fun movesSkipHiddenColumnsAndWidthsStayInRange() {
        val layout = LogColumnLayout().toggled(LogColumn.SOURCE)
        // The tag moves before the time among what is shown; the hidden source keeps its slot.
        assertEquals(listOf(LogColumn.TAG, LogColumn.SOURCE, LogColumn.TIME, LogColumn.MESSAGE), layout.moved(LogColumn.TAG, 0).order)
        assertEquals(LogColumn.TIME.minWidth, layout.resized(LogColumn.TIME, 1f).width(LogColumn.TIME))
        assertEquals(400f, layout.resized(LogColumn.TIME, 9999f).width(LogColumn.TIME))
        // The message takes what is left: it has no width and cannot be hidden.
        assertSame(layout, layout.resized(LogColumn.MESSAGE, 10f))
        assertSame(layout, layout.toggled(LogColumn.MESSAGE))
    }

    @Test fun garbledOrPartialTextFallsBackToDefaults() {
        assertEquals(LogColumnLayout(), LogColumnLayout.decode(null))
        val partial = LogColumnLayout.decode("TAG:abc,BOGUS:3,TAG:70,-MESSAGE")
        // Listed columns keep their order, the missing ones follow; the message cannot be hidden.
        assertEquals(listOf(LogColumn.TAG, LogColumn.MESSAGE, LogColumn.TIME, LogColumn.SOURCE), partial.order)
        assertEquals(LogColumn.TAG.defaultWidth, partial.width(LogColumn.TAG))
        assertTrue(partial.hidden.isEmpty())
    }
}
