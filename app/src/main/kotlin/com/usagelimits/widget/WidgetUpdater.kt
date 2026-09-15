package com.usagelimits.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
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

    private const val TAG = "WidgetUpdater"

    /** One placeable widget: its manifest receiver and the Glance class the receiver renders. */
    data class PlacedWidget(
        val name: String,
        val receiver: Class<out GlanceAppWidgetReceiver>,
        val widget: () -> GlanceAppWidget,
    )

    /**
     * Every widget this app can place, named exactly once.
     *
     * This list is the app-driven half of the update path — the half that runs after a sync
     * has moved the cache. The system-driven half is `updatePeriodMillis` on each provider XML,
     * handled by [UsageWidgetReceiver]. A widget missing from here still gets the system's
     * half-hourly render, but not the immediate one after a sync, so a widget refreshed in the
     * app would lag the home screen by up to thirty minutes. `WidgetRefreshCoverageTest` reads
     * the manifest and fails if a placeable widget is missing from this list, and if a provider
     * XML drops its `updatePeriodMillis`.
     */
    internal val allWidgets: List<PlacedWidget> = listOf(
        PlacedWidget("CompactUsageWidget", CompactUsageWidgetReceiver::class.java) { CompactUsageWidget() },
        PlacedWidget("DetailedUsageWidget", DetailedUsageWidgetReceiver::class.java) { DetailedUsageWidget() },
        PlacedWidget("MinimalUsageWidget", MinimalUsageWidgetReceiver::class.java) { MinimalUsageWidget() },
        PlacedWidget("MiniRingsWidget", MiniRingsWidgetReceiver::class.java) { MiniRingsWidget() },
    )

    /**
     * Rebuilds every placed widget. Called after a sync pass moves the cache.
     *
     * Each widget on its own: one `runCatching` around all of them meant a failure in the
     * compact widget skipped the detailed one, which then kept showing whatever it had — an
     * account the user had just deleted, say — with nothing to say it was old.
     *
     * Failures are logged, not swallowed. This used to be a bare `runCatching`, so a widget
     * that could not be refreshed for the life of an install left no trace anywhere; the
     * symptom was a home screen frozen on the day the widget was placed and a log with nothing
     * in it. Cancellation is not a failure and is rethrown: the calling worker is being
     * stopped, and carrying on with a cancelled scope would only fail every remaining widget
     * in turn.
     */
    suspend fun refreshAll(context: Context) {
        for (placed in allWidgets) {
            try {
                refresh(context, placed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "refresh of ${placed.name} failed", e)
            }
        }
        (context.applicationContext as? UsageLimitsApp)?.container?.settingsStore
            ?.recordWidgetRefresh(System.currentTimeMillis())
    }

    /**
     * One widget class, every instance of it.
     *
     * Glance resolves the instances through a stored map from receiver to widget class name,
     * written only when the receiver handles a broadcast. If that map has no entry although
     * the framework knows placed instances — cleared app data, or a release build whose
     * minified class names moved between versions — `updateAll` quietly updates nothing. In
     * that case the receiver is sent the framework's own update intent for those ids, which
     * both rewrites the map and renders, so the repair and the repaint are one step.
     */
    private suspend fun refresh(context: Context, placed: PlacedWidget) {
        val widget = placed.widget()
        val component = ComponentName(context, placed.receiver)
        val placedIds = AppWidgetManager.getInstance(context).getAppWidgetIds(component)
        if (placedIds.isEmpty()) return

        val known = GlanceAppWidgetManager(context).getGlanceIds(widget.javaClass)
        if (known.isEmpty()) {
            Log.w(TAG, "${placed.name}: ${placedIds.size} placed but none known to Glance; asking the receiver")
            context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(component)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, placedIds),
            )
            return
        }
        widget.updateAll(context)
    }

    /**
     * What one placed widget needs to draw itself: its numbers, and how it was configured to
     * look. Returned together because both come from the same two reads, and a second call to
     * fetch the config would repeat them.
     */
    data class WidgetView(
        val snapshot: WidgetSnapshot,
        val style: WidgetStyle = WidgetStyle(),
        val metrics: WidgetMetrics = WidgetMetrics(),
    ) {
        val transparent: Boolean get() = style.background.isTransparent
    }

    /**
     * Loads the data one widget instance should render, honouring its saved configuration.
     *
     * Falls back to the auto ("most critical") scope when a widget has no stored config yet —
     * which is the case for one placed but never opened — so a fresh widget still shows
     * something useful.
     */
    suspend fun load(context: Context, glanceId: GlanceId): WidgetView = observe(context, glanceId).first()

    fun observe(context: Context, glanceId: GlanceId): kotlinx.coroutines.flow.Flow<WidgetView> =
        kotlinx.coroutines.flow.flow {
            val app = context.applicationContext as? UsageLimitsApp ?: return@flow
            val container = app.container
            val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
            emitAll(kotlinx.coroutines.flow.combine(
                container.repository.observeAccountUsage(),
                container.widgetConfigDao.observe(appWidgetId),
                container.settingsStore.settings,
            ) { accounts, config, settings ->
                val custom = runCatching {
                    val ids = org.json.JSONArray(config?.customAccountIdsJson ?: "[]")
                    (0 until ids.length()).map { ids.getString(it) }
                }.getOrDefault(emptyList())
                WidgetView(
                    WidgetDataBuilder.build(accounts, System.currentTimeMillis(),
                        WidgetScope.fromName(config?.scope), config?.accountId, config?.provider,
                        Severity.staleAfterMs(settings.syncIntervalMinutes), custom, settings.providerIcons),
                    WidgetStyle.fromStored(
                        config?.backgroundArgb, config?.backgroundOpacity,
                        config?.transparent ?: false, config?.textTone,
                    ),
                    WidgetMetrics.fromJson(config?.layoutMetricsJson ?: "{}") ?: WidgetMetrics(),
                )
            })
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
