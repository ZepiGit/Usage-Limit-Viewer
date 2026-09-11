package com.usagelimits.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.flow.first
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.glance.action.ActionParameters
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.WidgetScope
import com.usagelimits.core.sync.SyncWorker

/**
 * Bridges the local cache to the home screen.
 *
 * Widgets read only through here, and this reads only [com.usagelimits.core.database.UsageRepository]
 * — never a provider and never the credential store. That is the structural reason a token
 * cannot end up in widget state.
 */
object WidgetUpdater {

    /**
     * Every widget this app can place, named exactly once.
     *
     * This list is the whole update path. None of the three declares `updatePeriodMillis`, so
     * the system never refreshes them on its own, and only the detailed one has a refresh
     * button — a widget missing from here renders once when it is placed and then never again,
     * showing the numbers that were true at that moment for as long as it stays on the home
     * screen. Nothing reports it: the widget is not broken, it is just old, and a quota tile
     * that is silently old is the one failure this app exists to prevent.
     *
     * That is not hypothetical. The ring widget shipped omitted from here and did exactly
     * that. `WidgetRefreshCoverageTest` now reads the manifest and fails if a placeable widget
     * is missing from this list, so the fourth one cannot repeat it.
     */
    internal val allWidgets: List<Pair<String, () -> GlanceAppWidget>> = listOf(
        "CompactUsageWidget" to { CompactUsageWidget() },
        "DetailedUsageWidget" to { DetailedUsageWidget() },
        "MinimalUsageWidget" to { MinimalUsageWidget() },
    )

    /** Rebuilds every placed widget. Called after a sync pass moves the cache. */
    suspend fun refreshAll(context: Context) {
        // Each widget on its own: one `runCatching` around all of them meant a failure in the
        // compact widget skipped the detailed one, which then kept showing whatever it had — an
        // account the user had just deleted, say — with nothing to say it was old.
        for ((_, widget) in allWidgets) {
            runCatching { widget().updateAll(context) }
        }
    }

    /**
     * Loads the data one widget instance should render, honouring its saved configuration.
     *
     * Falls back to the auto ("most critical") scope when a widget has no stored config yet —
     * which is the case for one placed but never opened — so a fresh widget still shows
     * something useful.
     */
    /**
     * What one placed widget needs to draw itself: its numbers, and how it was configured to
     * look. Returned together because both come from the same two reads, and a second call to
     * fetch the config would repeat them.
     */
    data class WidgetView(
        val snapshot: WidgetSnapshot,
        val transparent: Boolean = false,
    )

    suspend fun load(context: Context, glanceId: GlanceId): WidgetView {
        val app = context.applicationContext as? UsageLimitsApp ?: return WidgetView(WidgetSnapshot.Empty)
        val container = app.container

        val appWidgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        }.getOrNull()

        val config = appWidgetId?.let { container.widgetConfigDao.get(it) }

        val interval = container.settingsStore.settings.first().syncIntervalMinutes

        return WidgetView(
            snapshot = WidgetDataBuilder.build(
                all = container.repository.accountUsageOnce(),
                nowMs = System.currentTimeMillis(),
                scope = WidgetScope.fromName(config?.scope),
                accountId = config?.accountId,
                providerId = config?.provider,
                staleAfterMs = Severity.staleAfterMs(interval),
            ),
            transparent = config?.transparent ?: false,
        )
    }
}

/**
 * The widget's refresh button.
 *
 * Enqueues the ordinary background sync rather than fetching inline: the launcher process is
 * a poor place to hold network work, and routing through WorkManager means the refresh obeys
 * the same network constraints and concurrency guards as every other sync.
 */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        SyncWorker.syncNow(context)
    }
}
