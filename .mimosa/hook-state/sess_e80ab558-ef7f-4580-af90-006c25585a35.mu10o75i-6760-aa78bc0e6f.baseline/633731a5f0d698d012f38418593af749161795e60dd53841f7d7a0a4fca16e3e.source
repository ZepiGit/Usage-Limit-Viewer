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
import com.usagelimits.core.database.DirectTransactionRunner
import com.usagelimits.core.database.TransactionRunner
import com.usagelimits.core.database.NotificationDao
import com.usagelimits.core.database.NotificationEventEntity
import com.usagelimits.core.database.NotificationStateEntity
import com.usagelimits.core.notifications.NotificationEvaluator
import com.usagelimits.core.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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
    /**
     * Serialises the read-evaluate-write below against every other publisher and every delete.
     *
     * Pass-through by default, for tests with a fake DAO and nothing to protect it from. The
     * app wires the Room runner, for two failures that only a transaction closes:
     *
     * Two publishers can run at once — the periodic worker and a pull-to-refresh land in the
     * same window routinely. Each read the state table, evaluated, and wrote back. P1 reads an
     * account mid-episode and pauses; P2 reads the same state, sees a fresh dip, REUSES the
     * episode number P1 is about to end, and its warning key collides with one the ledger has
     * already spent — so the new dip is never announced. P1 then writes its stale state over
     * P2's, rolling `lastProcessedFetchedAt` backwards. Inside one transaction the second
     * publisher reads what the first wrote.
     *
     * And the account list arrives from a read moments earlier; see `existingAccountIds`.
     */
    private val transactions: TransactionRunner = DirectTransactionRunner,
) {

    suspend fun publishFor(accounts: List<AccountUsage>) {
        val settings = settingsStore.settings.first()
        val nowMs = now()

        val (outcome, claimed) = transactions.inTransaction {
            // Only accounts that still exist, decided INSIDE the transaction, so a delete
            // cannot slip in between the check and the writes it protects.
            val live = notificationDao.existingAccountIds().toHashSet()

            val outcome = NotificationEvaluator.evaluate(
                accounts = accounts.filter { it.account.localId in live },
                settings = settings,
                states = notificationDao.allStates().associate {
                    it.accountId to NotificationEvaluator.AccountState(
                        accountId = it.accountId,
                        lowQuotaEpisode = it.lowQuotaEpisode,
                        lowQuotaActive = it.lowQuotaActive,
                        lastProcessedFetchedAt = it.lastProcessedFetchedAt,
                        windows = decodeWindows(it.windowsJson),
                    )
                },
                nowMs = nowMs,
            )

            // Claim first, and advance state, whether or not anything can be posted. Skipping
            // this when permission is missing would replay every threshold the account ever
            // crossed the moment permission was granted.
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
                // Carried-over state for an account that no longer exists is dropped here for
                // the same foreign-key reason, rather than written and rejected.
                outcome.states.filter { it.accountId in live }.map {
                    NotificationStateEntity(
                        accountId = it.accountId,
                        lowQuotaEpisode = it.lowQuotaEpisode,
                        lowQuotaActive = it.lowQuotaActive,
                        lastProcessedFetchedAt = it.lastProcessedFetchedAt,
                        windowsJson = json.encodeToString(WINDOWS_SERIALIZER, it.windows),
                    )
                },
            )
            notificationDao.pruneEventsBefore(nowMs - EVENT_RETENTION_MS)
            outcome to claimed
        }

        if (!hasPermission()) return

        // Newly claimed events lead: the six-line cap must not push a fresh escalation below a
        // standing fact the user has already seen.
        val alerts = claimed.map { it.line }.filter { it.isNotBlank() }
        val findings = alerts + outcome.standingFindings

        // A standing fact — "1 reset credit available" — is re-derived every sync. Posting it
        // again every thirty minutes meant a notification the user had DISMISSED came back,
        // silently, for ever, and the only way to be rid of it was the toggle that turns the
        // feature off — which is the outcome the feature exists to avoid. It is re-posted only
        // when it changes; the fingerprint of what was last posted is what says whether it did.
        val standingPrefs = context.getSharedPreferences(STANDING_PREFS, Context.MODE_PRIVATE)
        val lastStanding = standingPrefs.getString(STANDING_FINGERPRINT, null)
        val standingNow = fingerprint(outcome.standingFindings)
        // The fingerprint records what is TRUE, and is updated whether or not anything posts.
        // It used to be written only after the post decision, so a refresh that found the
        // condition gone — credits spent, nothing to say — returned early and left the old
        // fingerprint behind. When the condition came back unchanged it matched that stale
        // fingerprint, read as "already posted", and was never posted again. Nothing is
        // cancelled here: a cleared condition takes nothing out of the shade, it only stops
        // pretending the shade still shows it.
        if (standingNow != lastStanding) {
            standingPrefs.edit().putString(STANDING_FINGERPRINT, standingNow).apply()
        }
        if (!shouldPost(alerts, outcome.standingFindings, lastStanding)) return

        if (findings.isEmpty()) {
            // Nothing new to say, and NOTHING is taken down. This used to cancel the
            // notification, which deleted an alert the user had not yet seen: an
            // edge-triggered "Weekly exhausted" was posted at one sync and cancelled by the
            // next, thirty minutes later, phone face-down in a pocket — and because its key
            // was already claimed it could never be posted again. The alert lives until the
            // user dismisses it or a later refresh has something to replace it with.
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

    private val json = Json { ignoreUnknownKeys = true }

    /** A map that cannot be read is an empty map: every window starts its own episode afresh. */
    private fun decodeWindows(raw: String?): Map<String, NotificationEvaluator.WindowState> =
        raw?.let { runCatching { json.decodeFromString(WINDOWS_SERIALIZER, it) }.getOrNull() }.orEmpty()

    companion object {
        private val WINDOWS_SERIALIZER = MapSerializer(String.serializer(), NotificationEvaluator.WindowState.serializer())
        private const val STANDING_PREFS = "usage_limits_notifications"
        private const val STANDING_FINGERPRINT = "standing_fingerprint"

        /** Order-independent identity of a set of standing lines; empty set is the empty string. */
        fun fingerprint(standing: List<String>): String = standing.sorted().joinToString("\u0000")

        /**
         * Whether this refresh has anything to say to the shade.
         *
         * A new alert always posts. With no new alert, standing findings post only if they
         * differ from what was last posted — a dismissed one must not come back unchanged —
         * and a refresh with nothing at all says nothing, leaving whatever is in the shade.
         */
        fun shouldPost(alerts: List<String>, standing: List<String>, lastStandingFingerprint: String?): Boolean =
            alerts.isNotEmpty() || (standing.isNotEmpty() && fingerprint(standing) != lastStandingFingerprint)

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
