package com.usagelimits.core.sync

import android.app.usage.UsageStatsManager
import com.usagelimits.core.sync.BackgroundRestrictions.StandbyBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one judgement the diagnostics card makes: is background work being held back for hours?
 *
 * Ordinary battery optimisation is not that — every app has it and periodic work still runs
 * in Doze windows. Saying "restricted" for the default state would make the warning noise.
 */
class BackgroundRestrictionsTest {

    @Test
    fun `battery optimisation alone is not severe`() {
        assertFalse(BackgroundRestrictions(false, batteryOptimised = true, StandbyBucket.WORKING_SET).severe)
        assertFalse(BackgroundRestrictions(false, batteryOptimised = true, StandbyBucket.ACTIVE).severe)
        assertFalse(BackgroundRestrictions(false, batteryOptimised = true, standbyBucket = null).severe)
    }

    @Test
    fun `an explicit restriction or a rare bucket is severe`() {
        assertTrue(BackgroundRestrictions(backgroundRestricted = true, batteryOptimised = false, standbyBucket = null).severe)
        assertTrue(BackgroundRestrictions(false, false, StandbyBucket.RARE).severe)
        assertTrue(BackgroundRestrictions(false, false, StandbyBucket.RESTRICTED).severe)
        assertFalse(BackgroundRestrictions(false, false, StandbyBucket.FREQUENT).severe)
    }

    @Test
    fun `framework bucket values map by name and unknown ones do not crash`() {
        assertEquals(StandbyBucket.ACTIVE, BackgroundRestrictions.bucketFor(UsageStatsManager.STANDBY_BUCKET_ACTIVE))
        assertEquals(StandbyBucket.WORKING_SET, BackgroundRestrictions.bucketFor(UsageStatsManager.STANDBY_BUCKET_WORKING_SET))
        assertEquals(StandbyBucket.FREQUENT, BackgroundRestrictions.bucketFor(UsageStatsManager.STANDBY_BUCKET_FREQUENT))
        assertEquals(StandbyBucket.RARE, BackgroundRestrictions.bucketFor(UsageStatsManager.STANDBY_BUCKET_RARE))
        assertEquals(StandbyBucket.RESTRICTED, BackgroundRestrictions.bucketFor(45))
        assertEquals(StandbyBucket.UNKNOWN, BackgroundRestrictions.bucketFor(999))
    }
}
