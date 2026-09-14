package com.usagelimits.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The compact widget's content is chosen by the width the launcher actually supplies, because
 * its declared 180 dp minimum cannot hold four labelled tiles: after the outer padding, three
 * gaps, the refresh target and each tile's own padding, roughly 4 dp per tile would remain for
 * text. These pin the arithmetic the choice obeys.
 */
class WidgetCompactLayoutTest {

    @Test
    fun `180dp never selects four padded tiles`() {
        assertEquals(WidgetLayout.CompactLayout.FOCUS, WidgetLayout.compactLayout(180f, 1f))
    }

    @Test
    fun `the layout budget includes refresh, gaps and padding`() {
        // 372 dp is the four-tile budget at unit font scale: 20 outer + 40 refresh + 24 of
        // gaps + 4 × (20 tile padding + 52 text). One dp short, and a tile's text does not fit.
        assertEquals(WidgetLayout.CompactLayout.SUMMARY, WidgetLayout.compactLayout(371.9f, 1f))
        assertEquals(WidgetLayout.CompactLayout.FOUR_TILES, WidgetLayout.compactLayout(372f, 1f))
    }

    @Test
    fun `intermediate widths select the two-metric summary`() {
        // Two tiles need 20 outer + 40 refresh + 8 gap + 2 × (20 + 52) = 212 dp.
        assertEquals(WidgetLayout.CompactLayout.FOCUS, WidgetLayout.compactLayout(211f, 1f))
        assertEquals(WidgetLayout.CompactLayout.SUMMARY, WidgetLayout.compactLayout(212f, 1f))
        assertEquals(WidgetLayout.CompactLayout.SUMMARY, WidgetLayout.compactLayout(300f, 1f))
    }

    @Test
    fun `large font scales demand reduced content`() {
        // Text width scales with the user's font size, so a width that fits four tiles at
        // 1.0× does not at 1.3×.
        assertEquals(WidgetLayout.CompactLayout.SUMMARY, WidgetLayout.compactLayout(400f, 1.3f))
        assertEquals(WidgetLayout.CompactLayout.FOCUS, WidgetLayout.compactLayout(260f, 1.5f))
        // The scale never SHRINKS the budget: a 0.8× setting still counts full-size text,
        // because RemoteViews does not shrink sp below the platform floor either.
        assertEquals(WidgetLayout.CompactLayout.SUMMARY, WidgetLayout.compactLayout(300f, 0.8f))
    }
}
