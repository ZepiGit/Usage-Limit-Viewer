package com.usagelimits.core.model

/**
 * The single scale the whole app colours by.
 *
 * Ordering matters: [compareTo] is used to pick the worst state across an account's windows,
 * so entries run from best to worst.
 */
enum class Severity {
    HEALTHY,
    MEDIUM,
    LOW,
    EXHAUSTED,
    STALE,
    ERROR;

    companion object {
        /** Central thresholds — the one place these numbers are defined. */
        const val HEALTHY_ABOVE = 50.0
        const val MEDIUM_ABOVE = 20.0

        /**
         * Default age beyond which a snapshot is shown as stale rather than current.
         *
         * Only a default. Prefer [staleAfterMs], which derives the threshold from the sync
         * interval the user actually chose — see below for why a fixed hour is wrong.
         */
        const val STALE_AFTER_MS = 60L * 60 * 1000

        /**
         * Never call a snapshot stale before it has had a fair chance to refresh.
         *
         * A floor as well as a multiple, because two fifteen-minute periods is half an hour,
         * and Doze routinely defers background work by more than that on a phone in a pocket.
         */
        private const val MIN_STALE_AFTER_MS = 45L * 60 * 1000

        /**
         * How old a snapshot may be before it stops being trustworthy, given how often the app
         * was told to refresh.
         *
         * A fixed hour was a bug at a setting the app itself offers: the sync interval can be
         * set to three hours, and every snapshot would then be older than an hour by the time
         * the next one arrived — so every account read STALE permanently, greyed out and
         * ranked as "cannot tell you" no matter how healthy it actually was.
         *
         * Two missed refreshes is the signal worth acting on, so the threshold follows the
         * interval rather than the clock.
         */
        fun staleAfterMs(syncIntervalMinutes: Int): Long =
            maxOf(2L * syncIntervalMinutes * 60_000L, MIN_STALE_AFTER_MS)

        fun fromRemainingPercent(remaining: Double?, exhausted: Boolean = false): Severity = when {
            exhausted -> EXHAUSTED
            remaining == null -> ERROR
            remaining <= 0.0 -> EXHAUSTED
            remaining <= MEDIUM_ABOVE -> LOW
            remaining <= HEALTHY_ABOVE -> MEDIUM
            else -> HEALTHY
        }
    }
}
