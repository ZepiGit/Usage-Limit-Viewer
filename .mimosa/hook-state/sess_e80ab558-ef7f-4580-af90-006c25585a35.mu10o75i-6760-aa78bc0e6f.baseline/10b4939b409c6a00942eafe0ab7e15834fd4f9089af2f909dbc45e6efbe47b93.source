package com.usagelimits.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetMetricsTest {
    private val pixel = WidgetMetrics(70f, 131f, 132f, 66f)

    @Test fun `portrait and landscape placements both hold two accounts per cell`() {
        assertEquals(2, WidgetLayout.miniGrid(62.9f, 123.6f, pixel).capacity)
        assertEquals(2, WidgetLayout.miniGrid(124f, 58.2f, pixel).capacity)
        assertEquals(4, WidgetLayout.miniGrid(62f, 254f, pixel).capacity)
        assertEquals(8, WidgetLayout.miniGrid(132f, 254f, pixel).capacity)
        assertEquals(8, WidgetLayout.miniGrid(256f, 124f, pixel).capacity)
    }

    @Test fun `account rings add a column when the host grows from three by two to three by four`() {
        assertEquals(1, WidgetLayout.accountColumns(202f, 254f, pixel))
        assertEquals(2, WidgetLayout.accountColumns(202f, 516f, pixel))
    }

    @Test fun `square launcher cells preserve the same capacity contract`() {
        val square = WidgetMetrics(88f, 88f, 88f, 88f)
        assertEquals(2, WidgetLayout.miniGrid(80f, 80f, square).capacity)
        assertEquals(4, WidgetLayout.miniGrid(80f, 168f, square).capacity)
        assertEquals(8, WidgetLayout.miniGrid(168f, 168f, square).capacity)
    }
}
