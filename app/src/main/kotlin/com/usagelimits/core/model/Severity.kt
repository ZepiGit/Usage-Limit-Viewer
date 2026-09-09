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

        /** Beyond this a snapshot is shown as stale rather than current. */
        const val STALE_AFTER_MS = 60L * 60 * 1000

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
