package com.usagelimits.core.sync

import android.content.Context
import com.usagelimits.core.di.AppContainer
import com.usagelimits.widget.WidgetUpdater

/**
 * Everything that has to happen once a sync has moved the cache, wherever the sync came from.
 *
 * There were two answers to that question and only one of them was complete. The periodic
 * worker woke the widgets and then evaluated notifications; the manual refresh — the pull on
 * the overview, the per-account retry, the re-sync after spending a credit — woke the widgets
 * and stopped there. So a threshold crossed during a manual refresh said nothing at all:
 *
 *   the worker sees 25% left; the user pulls to refresh and it is 8%, silently; the limit
 *   resets before the next worker run, which then sees a healthy number. The dip the app
 *   observed, on the screen the user was looking at, was never announced.
 *
 * One function for every entry point, so a new caller cannot forget half of it. It costs no
 * extra network round trip — the snapshots have already been written, and this only reads
 * them back.
 *
 * Calling it more often does not mean notifying more often: the evaluator's ledger keys each
 * edge and the publisher swallows a key it has already acted on, which is what lets the manual
 * path and the worker both run in the same window without saying anything twice.
 */
suspend fun AppContainer.publishAfterSync(context: Context) {
    // Widgets read the cache, so they only need waking once the cache has moved.
    WidgetUpdater.refreshAll(context)

    NotificationPublisher(context, settingsStore, notificationDao, transactions = transactions)
        .publishFor(repository.accountUsageOnce())
}
