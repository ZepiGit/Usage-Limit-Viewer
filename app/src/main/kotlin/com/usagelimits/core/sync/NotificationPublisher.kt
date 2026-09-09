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
import com.usagelimits.core.database.NotificationDao
import com.usagelimits.core.database.NotificationEventEntity
import com.usagelimits.core.database.NotificationStateEntity
import com.usagelimits.core.notifications.NotificationEvaluator
import com.usagelimits.core.settings.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Turns a completed sync into at most one notification.
 *
 * Two rules keep this from becoming spam. Every finding across every account is folded into a
 * single grouped notification posted to a stable id, so a later sync replaces the previous one
 * instead of stacking. And edge-triggered alerts — crossing a threshold, a reset drawing near,
 * a credit about to lapse — are claimed in the database before they are posted, so a limit the
 * user has already been told about stays quiet on the next sync.
 *
 * Claiming before posting is deliberate. A database write cannot commit atomically with
 * `NotificationManager.notify`, so one of two failures has to be chosen: an alert lost to a
 * crash in the gap, or an alert repeated every fifteen minutes. The first is recoverable; the
 * second is what makes people switch notifications off.
 */
class NotificationPublisher(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val notificationDao: NotificationDao,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun publishFor(accounts: List<AccountUsage>) {
        val settings = settingsStore.settings.first()
        val nowMs = now()

        val outcome = NotificationEvaluator.evaluate(
            accounts = accounts,
            settings = settings,
            states = notificationDao.allStates().associate {
                it.accountId to NotificationEvaluator.AccountState(
                    accountId = it.accountId,
                    lowQuotaEpisode = it.lowQuotaEpisode,
                    lowQuotaActive = it.lowQuotaActive,
                    lastProcessedFetchedAt = it.lastProcessedFetchedAt,
                )
            },
            nowMs = nowMs,
        )

        // Claim first, and advance state, whether or not anything can be posted. Skipping this
        // when permission is missing would replay every threshold the account ever crossed the
        // moment permission was granted.
        val claimed = outcome.events.filter { event ->
            notificationDao.claim(
                NotificationEventEntity(
                    eventKey = event.key,
                    accountId = event.accountId,
                    consumedAt = nowMs,
                ),
            ) != -1L
        }

        notificationDao.upsertStates(
            outcome.states.map {
                NotificationStateEntity(
                    accountId = it.accountId,
                    lowQuotaEpisode = it.lowQuotaEpisode,
                    lowQuotaActive = it.lowQuotaActive,
                    lastProcessedFetchedAt = it.lastProcessedFetchedAt,
                )
            },
        )
        notificationDao.pruneEventsBefore(nowMs - EVENT_RETENTION_MS)

        if (!hasPermission()) return

        // Newly claimed events lead: the six-line cap must not push a fresh escalation below a
        // standing fact the user has already seen.
        val alerts = claimed.map { it.line }.filter { it.isNotBlank() }
        val findings = alerts + outcome.standingFindings
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

        // A refresh that only restates standing facts replaces the notification silently.
        // setOnlyAlertOnce cannot do this job: it would also mute a genuine escalation from
        // 18 % to 8 % while the earlier notification is still on screen.
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setStyle(style)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(alerts.isEmpty())
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
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

        /**
         * How long a consumed event is remembered.
         *
         * Long enough that a monthly window's reset timestamp is still known when the next
         * month's schedule is published, so the same reset cannot be announced twice.
         */
        const val EVENT_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    }
}
