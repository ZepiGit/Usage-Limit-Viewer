package com.usagelimits.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.glance.action.ActionParameters
import com.usagelimits.UsageLimitsApp
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

    /** Rebuilds every placed widget. Called after a sync pass moves the cache. */
    suspend fun refreshAll(context: Context) {
        runCatching {
            CompactUsageWidget().updateAll(context)
            DetailedUsageWidget().updateAll(context)
        }
    }

    /**
     * Loads the data one widget instance should render, honouring its saved configuration.
     *
     * Falls back to the auto ("most critical") scope when a widget has no stored config yet —
     * which is the case for one placed but never opened — so a fresh widget still shows
     * something useful.
     */
    suspend fun loadSnapshot(context: Context, glanceId: GlanceId): WidgetSnapshot {
        val app = context.applicationContext as? UsageLimitsApp ?: return WidgetSnapshot.Empty
        val container = app.container

        val appWidgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        }.getOrNull()

        val config = appWidgetId?.let { container.widgetConfigDao.get(it) }

        return WidgetDataBuilder.build(
            all = container.repository.accountUsageOnce(),
            nowMs = System.currentTimeMillis(),
            scope = WidgetScope.fromName(config?.scope),
            accountId = config?.accountId,
            providerId = config?.provider,
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
