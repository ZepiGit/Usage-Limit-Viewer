package com.usagelimits.core.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a stored preferences file means, including the ones written by older builds.
 *
 * Untested until a mutation sweep found it: dropping the legacy opt-out fallback, and dropping
 * the muted-account read entirely, both left the whole suite green. Every rule here is an
 * UPGRADE rule — it runs only for a user coming from an earlier version, which is the one path
 * nobody exercises by hand and the one where a mistake silently un-does a choice they made.
 *
 * The key NAMES are spelled out rather than taken from the private `Keys` object. They are the
 * on-disk contract: renaming one does not fail to compile, it just makes every stored value
 * invisible and quietly resets the user to defaults.
 */
class SettingsMappingTest {

    private fun settingsFrom(vararg pairs: androidx.datastore.preferences.core.Preferences.Pair<*>) =
        mutablePreferencesOf(*pairs).toSettings()

    @Test
    fun `nothing stored means the documented defaults`() {
        val settings = settingsFrom()

        assertEquals(AppSettings(), settings)
        assertTrue(settings.showSubscriptionTier)
        assertFalse(settings.showRenewalTime)
        assertFalse(settings.accountsManuallyOrdered)
        assertTrue(settings.mutedAccountIds.isEmpty())
    }

    @Test
    fun `a pre-split opt-out silences BOTH low-quota tiers, not just one`() {
        // The old build had a single "notify on low usage" switch. Someone who turned it off
        // meant "stop telling me about low quota". Reading it for only one of the two tiers
        // hands them back the 20% alert they had switched off, with no setting having changed
        // on their side — and nothing on screen to explain why it came back.
        val settings = settingsFrom(booleanPreferencesKey("notify_low_usage") to false)

        assertFalse("the 20% tier must inherit the old opt-out", settings.notifyBelow20Percent)
        assertFalse("and so must the 10% tier", settings.notifyBelow10Percent)
    }

    @Test
    fun `an explicit new-style choice wins over the legacy switch`() {
        val settings = settingsFrom(
            booleanPreferencesKey("notify_low_usage") to false,
            booleanPreferencesKey("notify_below_20_percent") to true,
        )

        assertTrue(settings.notifyBelow20Percent)
        assertFalse(settings.notifyBelow10Percent)
    }

    @Test
    fun `muted accounts are read back, so a silenced account stays silenced`() {
        val settings = settingsFrom(
            stringSetPreferencesKey("muted_account_ids") to setOf("acct-1", "acct-2"),
        )

        assertEquals(setOf("acct-1", "acct-2"), settings.mutedAccountIds)
    }

    @Test
    fun `the display and ordering preferences are read back`() {
        val settings = settingsFrom(
            booleanPreferencesKey("show_subscription_tier") to false,
            booleanPreferencesKey("show_renewal_time") to true,
            booleanPreferencesKey("accounts_manually_ordered") to true,
        )

        assertFalse(settings.showSubscriptionTier)
        assertTrue(settings.showRenewalTime)
        assertTrue(settings.accountsManuallyOrdered)
    }

    @Test
    fun `a stored interval below the platform floor is raised on the way out`() {
        val settings = settingsFrom(intPreferencesKey("sync_interval_minutes") to 1)

        assertEquals(
            "an interval the platform ignores must not survive a read",
            AppSettings.MIN_SYNC_INTERVAL_MINUTES,
            settings.syncIntervalMinutes,
        )
    }
}
