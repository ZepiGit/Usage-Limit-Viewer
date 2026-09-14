package com.usagelimits.core.model

import com.usagelimits.core.notifications.NotificationEvaluator

/** Authentication state is independent of quota and cached-data freshness. */
enum class ConnectionStatus {
    CONNECTED, RECONNECT_REQUIRED, UNKNOWN;

    companion object {
        fun fromStored(value: String?, status: SnapshotStatus, message: String?): ConnectionStatus =
            entries.firstOrNull { it.name == value } ?: when {
                // The evaluator's constant, not a second copy of the sentence: a reword there
                // must not leave a stored row reading as connected.
                message == NotificationEvaluator.SIGN_IN_EXPIRED_MESSAGE ||
                    // Compatibility fallback: the wording an earlier build stored in rows that
                    // outlive the upgrade. A literal on purpose — it is historical data to
                    // recognise, not a message this build produces.
                    message == "This account needs signing in again." -> RECONNECT_REQUIRED
                status != SnapshotStatus.FAILED -> CONNECTED
                else -> UNKNOWN
            }
    }
}
