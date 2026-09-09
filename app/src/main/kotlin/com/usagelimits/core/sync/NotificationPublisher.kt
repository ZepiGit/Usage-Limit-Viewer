package com.usagelimits.core.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.usagelimits.MainActivity
import com.usagelimits.R
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Turns a completed sync into at most one notification.
 *
 * The rule that keeps this from becoming spam: every finding across every account is folded
 * into a single grouped notification posted to a stable id, so a later sync replaces the
 * previous one instead of stacking. Nothing is posted when there is nothing to say.
 */
class NotificationPublisher(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {

    suspend fun publishFor(accounts: List<AccountUsage>) {
        if (!hasPermission()) return
        val settings = settingsStore.settings.first()

        val findings = accounts.flatMap { findingsFor(it, settings) }
        if (findings.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }

        ensureChannel()

        val title = if (findings.size == 1) findings.first() else "${findings.size} usage alerts"
        val style = NotificationCompat.InboxStyle().also { style ->
            findings.take(MAX_LINES).forEach(style::addLine)
            if (findings.size > MAX_LINES) {
                style.setSummaryText("+${findings.size - MAX_LINES} more")
            }
        }

        val intent = android.app.PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setStyle(style)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun findingsFor(usage: AccountUsage, settings: AppSettings): List<String> {
        val snapshot = usage.snapshot ?: return emptyList()
        val name = usage.account.label
        val findings = mutableListOf<String>()

        if (settings.notifyOnAuthExpired &&
            snapshot.status == SnapshotStatus.FAILED &&
            snapshot.errorMessage?.contains("expired", ignoreCase = true) == true
        ) {
            return listOf("$name needs to be reconnected")
        }

        val worst = snapshot.windows.minByOrNull { it.remainingPercent ?: Double.MAX_VALUE }
        val remaining = worst?.remainingPercent

        when {
            settings.notifyOnExhausted && worst != null && worst.severity == Severity.EXHAUSTED ->
                findings += "$name · ${worst.label} exhausted"

            settings.notifyOnLowUsage && remaining != null &&
                remaining <= settings.lowUsageThreshold ->
                findings += "$name · ${worst.label} at ${remaining.toInt()}% left"
        }

        if (settings.notifyOnResetCreditAvailable && snapshot.resetCredits.isNotEmpty()) {
            findings += "$name · ${snapshot.resetCredits.size} reset credit available"
        }

        return findings
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Usage alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Low quota, exhausted limits and available reset credits"
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val CHANNEL_ID = "usage_alerts"

        /** Fixed, so each sync replaces the previous alert rather than adding to a pile. */
        const val NOTIFICATION_ID = 1001
        const val MAX_LINES = 6
    }
}
