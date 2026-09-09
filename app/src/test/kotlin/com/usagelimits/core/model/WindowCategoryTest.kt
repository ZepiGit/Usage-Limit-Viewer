package com.usagelimits.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Classification is by declared duration alone. These cases pin the exact seconds each provider
 * sends, because a window that lands in the wrong bucket is labelled with the wrong period and
 * the user reads a month of quota as a week.
 */
class WindowCategoryTest {

    @Test
    fun `eighteen thousand seconds is the five hour window`() {
        assertEquals(WindowCategory.FIVE_HOUR, WindowCategory.fromPeriodSeconds(18_000L))
    }

    @Test
    fun `six hundred and four thousand eight hundred seconds is the weekly window`() {
        assertEquals(WindowCategory.WEEKLY, WindowCategory.fromPeriodSeconds(604_800L))
    }

    @Test
    fun `a month is a range because calendar months differ in length`() {
        assertEquals(WindowCategory.MONTHLY, WindowCategory.fromPeriodSeconds(2_419_200L)) // 28d
        assertEquals(WindowCategory.MONTHLY, WindowCategory.fromPeriodSeconds(2_592_000L)) // 30d
        assertEquals(WindowCategory.MONTHLY, WindowCategory.fromPeriodSeconds(2_678_400L)) // 31d
    }

    @Test
    fun `just outside the month range is other, not monthly`() {
        assertEquals(WindowCategory.OTHER, WindowCategory.fromPeriodSeconds(2_419_199L))
        assertEquals(WindowCategory.OTHER, WindowCategory.fromPeriodSeconds(2_678_401L))
    }

    @Test
    fun `an unrecognised duration stays other so the window still renders`() {
        assertEquals(WindowCategory.OTHER, WindowCategory.fromPeriodSeconds(3_600L))
        assertEquals(WindowCategory.OTHER, WindowCategory.fromPeriodSeconds(86_400L))
        assertEquals(WindowCategory.OTHER, WindowCategory.fromPeriodSeconds(0L))
        assertEquals(WindowCategory.OTHER, WindowCategory.fromPeriodSeconds(-1L))
    }

    @Test
    fun `an absent duration is other rather than a guess`() {
        assertEquals(WindowCategory.OTHER, WindowCategory.fromPeriodSeconds(null))
    }
}
