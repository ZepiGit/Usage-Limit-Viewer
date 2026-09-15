package com.usagelimits.core.sync

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * What the device currently allows this app to do in the background.
 *
 * Read for the Settings diagnostics, and only there. None of it changes what the app does —
 * WorkManager already obeys all of it — but it answers the question a frozen widget raises
 * and the app could not: is the background refresh being deferred by the device? A user who
 * can see "background use restricted" knows where to look; one who sees a stale tile and a
 * green app does not.
 */
data class BackgroundRestrictions(
    /** The user turned off background activity for this app (Android 9+). */
    val backgroundRestricted: Boolean,
    /** Battery optimisation applies, so Doze defers this app's jobs (the default for every app). */
    val batteryOptimised: Boolean,
    /** The App Standby bucket, as the system names it; null where the API is unavailable. */
    val standbyBucket: StandbyBucket?,
) {
    enum class StandbyBucket { ACTIVE, WORKING_SET, FREQUENT, RARE, RESTRICTED, UNKNOWN }

    /**
     * Whether background work is likely to be held back for hours rather than minutes.
     *
     * Ordinary battery optimisation is not counted: every app has it, and periodic work still
     * runs in Doze maintenance windows. The rare and restricted buckets, and an explicit
     * restriction, are the states in which a thirty-minute refresh becomes a daily one.
     */
    val severe: Boolean
        get() = backgroundRestricted ||
            standbyBucket == StandbyBucket.RARE ||
            standbyBucket == StandbyBucket.RESTRICTED

    companion object {
        fun read(context: Context): BackgroundRestrictions {
            val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val pie = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            return BackgroundRestrictions(
                backgroundRestricted = pie && activity?.isBackgroundRestricted == true,
                batteryOptimised = power?.isIgnoringBatteryOptimizations(context.packageName) == false,
                standbyBucket = if (pie) usage?.appStandbyBucket?.let(::bucketFor) else null,
            )
        }

        /** Maps the framework's integers; anything it adds later reads as UNKNOWN rather than crashing. */
        fun bucketFor(value: Int): StandbyBucket = when (value) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> StandbyBucket.ACTIVE
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> StandbyBucket.WORKING_SET
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> StandbyBucket.FREQUENT
            UsageStatsManager.STANDBY_BUCKET_RARE -> StandbyBucket.RARE
            // STANDBY_BUCKET_RESTRICTED is API 30; the constant is 45 on every release.
            45 -> StandbyBucket.RESTRICTED
            else -> StandbyBucket.UNKNOWN
        }
    }
}
