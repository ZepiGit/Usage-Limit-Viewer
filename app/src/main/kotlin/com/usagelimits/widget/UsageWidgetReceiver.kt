package com.usagelimits.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.usagelimits.core.sync.SyncWorker

/**
 * The receiver every placeable widget shares.
 *
 * The system calls [onUpdate] when a widget is placed, when the device boots, when the app is
 * updated, and — because every provider XML declares `updatePeriodMillis` — on its own clock
 * roughly every thirty minutes. That clock is the one update path this app does not own: it
 * needs no WorkManager job of ours to have run, survives the process being killed, and fires
 * inside Doze maintenance windows. Before it existed, every repaint depended on the app's own
 * periodic sync having run, and a home screen on a phone that deferred that sync showed the
 * numbers from the moment the widget was placed, for as long as it stayed there.
 *
 * Glance's `onUpdate` repaints from the cache. That is right for a placement and for a boot,
 * and it is not enough on the periodic tick: a repaint of a cache nobody has refreshed is the
 * same old number. So the tick also asks for a sync — but only when the cache has aged past
 * the user's interval or a reset has passed since it was fetched (`SyncWorker.cacheNeedsSync`),
 * because the same callback fires when a widget is dropped, and that must not cost a provider
 * round trip a minute after the last one. The fetch itself goes through WorkManager rather
 * than running here: a receiver has seconds, not a token refresh plus five provider calls.
 */
abstract class UsageWidgetReceiver : GlanceAppWidgetReceiver() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        SyncWorker.syncIfCacheAged(context)
    }
}
