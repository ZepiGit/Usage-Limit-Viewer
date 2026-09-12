package com.usagelimits.core.model

/** Authentication state is independent of quota and cached-data freshness. */
enum class ConnectionStatus {
    CONNECTED, RECONNECT_REQUIRED, UNKNOWN;

    companion object {
        fun fromStored(value: String?, status: SnapshotStatus, message: String?): ConnectionStatus =
            entries.firstOrNull { it.name == value } ?: when {
                message == "Sign-in expired — reconnect this account" ||
                    message == "This account needs signing in again." -> RECONNECT_REQUIRED
                status != SnapshotStatus.FAILED -> CONNECTED
                else -> UNKNOWN
            }
    }
}
