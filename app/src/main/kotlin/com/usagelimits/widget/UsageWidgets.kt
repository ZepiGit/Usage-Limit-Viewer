package com.usagelimits.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.usagelimits.MainActivity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.time.Countdown

/**
 * Palette mirrored from the app theme.
 *
 * Glance runs in the launcher's process and cannot read the app's Compose MaterialTheme, so
 * the few colours the widgets need are restated here. Kept in one object so a palette change
 * is still a single edit on each side.
 */
private object W {
    val Background = Color(0xFF0F0F0E)
    val Card = Color(0xFF1A1918)
    val Track = Color(0xFF2E2D2B)
    val TextPrimary = Color(0xFFF0EEE6)
    val TextSecondary = Color(0xFFB4B2A9)
    val Teal = Color(0xFF4FBFA8)
    val Green = Color(0xFF6FBF73)
    val Amber = Color(0xFFE0A33E)
    val Red = Color(0xFFD9584F)
    val Slate = Color(0xFF6B6960)

    // Text tones, mirroring UsageColors.RedText / SlateText. On [Card] the raw accents
    // measure 4.56:1 and 3.19:1 — the status word and the percentage are both normal text
    // and need 4.5:1, so Slate misses outright and Red only just clears; "Stale" is the word
    // the widget most needs a user to read.
    val RedText = Color(0xFFE8756B)
    val SlateText = Color(0xFFA29F94)

    fun accent(severity: Severity) = when (severity) {
        Severity.HEALTHY -> Green
        Severity.MEDIUM, Severity.LOW -> Amber
        Severity.EXHAUSTED, Severity.ERROR -> Red
        Severity.STALE -> Slate
    }

    fun bar(row: WidgetRow) = when {
        row.remainingPercent != null && row.remainingPercent >= 99.5 -> Teal
        else -> accent(row.severity)
    }

    /** [accent] for bars and dots; this for anything drawn as text on [Card]. */
    fun textColor(severity: Severity) = when (severity) {
        Severity.EXHAUSTED, Severity.ERROR -> RedText
        Severity.STALE -> SlateText
        else -> accent(severity)
    }

    fun barText(row: WidgetRow) = when {
        row.remainingPercent != null && row.remainingPercent >= 99.5 -> Teal
        else -> textColor(row.severity)
    }
}

/**
 * A usage bar.
 *
 * Glance's own LinearProgressIndicator fills the width it is given, so the bar is correct at
 * every launcher grid size. The previous hand-rolled version took a fixed dp width, which was
 * wider than a tile actually gets: the fill was clipped, and anything above roughly half
 * remaining painted as a full bar — a confidently wrong number on the home screen.
 */
@androidx.compose.runtime.Composable
private fun UsageBar(row: WidgetRow, modifier: GlanceModifier = GlanceModifier) {
    val fraction = ((row.remainingPercent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()

    LinearProgressIndicator(
        progress = fraction,
        modifier = modifier.fillMaxWidth().height(6.dp),
        color = ColorProvider(W.bar(row)),
        backgroundColor = ColorProvider(W.Track),
    )
}

private fun percentText(row: WidgetRow): String =
    row.remainingPercent?.let { "${it.toInt()}%" } ?: "—"

/**
 * The compact 1×4 widget: four tiles reading 5h, weekly, next reset and overall status.
 *
 * Deliberately shows aggregate headline numbers rather than one account, because at this size
 * there is room for a glance, not a list.
 */
class CompactUsageWidget : GlanceAppWidget() {

    // Responsive rather than Exact: the launcher picks the nearest declared size, so the
    // widget stays correct on tablets and unfolded foldables, whose grid cells are much wider
    // than a phone's, instead of being re-measured into a layout it was never designed for.
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(250.dp, 48.dp),
            DpSize(320.dp, 48.dp),
            DpSize(420.dp, 56.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetUpdater.loadSnapshot(context, id)

        provideContent {
            GlanceTheme {
                Row(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(24.dp)
                        .background(W.Background)
                        .padding(10.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tile(
                        label = "5h limit",
                        value = snapshot.headlineShort?.let(::percentText) ?: "—",
                        row = snapshot.headlineShort,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Tile(
                        // The row already knows what it is. Hardcoding "Weekly" here labelled a
                        // monthly credit bucket as a week: the user paced their spending against
                        // a reset days away that was actually a month away, while the larger
                        // widget beside it, using the row's own label, said "Monthly".
                        label = snapshot.headlineLong?.label ?: "Weekly",
                        value = snapshot.headlineLong?.let(::percentText) ?: "—",
                        row = snapshot.headlineLong,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Tile(
                        // A wall-clock instant rather than a countdown: a widget recomposes
                        // only when its worker runs, so "in 20m" rendered half an hour ago is
                        // not stale but wrong — the limit has already reset.
                        label = "Next reset",
                        value = snapshot.nextResetAt
                            ?.let {
                                Countdown.absoluteResetLabel(it, System.currentTimeMillis())
                                    ?.removePrefix("Resets ")
                            }
                            ?: "—",
                        row = null,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Tile(
                        label = "Quota",
                        value = statusWord(snapshot.overallSeverity),
                        row = null,
                        modifier = GlanceModifier.defaultWeight(),
                        accent = W.textColor(snapshot.overallSeverity),
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Tile(
        label: String,
        value: String,
        row: WidgetRow?,
        modifier: GlanceModifier = GlanceModifier,
        accent: Color = W.TextPrimary,
    ) {
        Column(
            modifier = modifier
                .cornerRadius(16.dp)
                .background(W.Card)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(color = androidx.glance.unit.ColorProvider(W.TextSecondary), fontSize = 11.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = value,
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(accent),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            if (row != null) {
                Spacer(GlanceModifier.height(5.dp))
                UsageBar(row)
            }
        }
    }

    private fun statusWord(severity: Severity) = when (severity) {
        Severity.HEALTHY -> "OK"
        Severity.MEDIUM -> "Fair"
        Severity.LOW -> "Low"
        Severity.EXHAUSTED -> "Out"
        Severity.STALE -> "Stale"
        Severity.ERROR -> "Error"
    }
}

/**
 * The larger 2×4 widget: a header plus one card per account, each with its 5h and weekly bars.
 *
 * Caps the account list because a widget that overflows its host silently truncates; showing
 * a "+N more" line is honest about what is hidden.
 */
class DetailedUsageWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(250.dp, 110.dp),
            DpSize(320.dp, 150.dp),
            DpSize(420.dp, 200.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetUpdater.loadSnapshot(context, id)

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(24.dp)
                        .background(W.Background)
                        .padding(12.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                ) {
                    Header(snapshot)
                    Spacer(GlanceModifier.height(8.dp))

                    if (snapshot.accounts.isEmpty()) {
                        Text(
                            text = "No accounts yet — tap to add one",
                            style = TextStyle(
                                color = androidx.glance.unit.ColorProvider(W.TextSecondary),
                                fontSize = 13.sp,
                            ),
                        )
                    } else {
                        snapshot.accounts.take(MAX_ACCOUNTS).forEach { account ->
                            AccountCard(account)
                            Spacer(GlanceModifier.height(6.dp))
                        }
                        val hidden = snapshot.accounts.size - MAX_ACCOUNTS
                        if (hidden > 0) {
                            Text(
                                text = "+$hidden more",
                                style = TextStyle(
                                    color = androidx.glance.unit.ColorProvider(W.TextSecondary),
                                    fontSize = 11.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Header(snapshot: WidgetSnapshot) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "Usage Limits",
                    style = TextStyle(
                        color = androidx.glance.unit.ColorProvider(W.TextPrimary),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = "${snapshot.accountCount} accounts monitored",
                    style = TextStyle(
                        color = androidx.glance.unit.ColorProvider(W.TextSecondary),
                        fontSize = 11.sp,
                    ),
                )
            }
            Text(
                // "As of 12:40", not "2m ago": an age composed once and left on the home screen
                // for three hours goes on claiming the numbers are two minutes old.
                // And the DATE once it is no longer today. "09:15" on a Wednesday afternoon
                // reads as this morning; the numbers were from Monday.
                text = Countdown.asOfLabel(snapshot.updatedAt, nowMs = System.currentTimeMillis())
                    .removePrefix("As of "),
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(W.TextSecondary),
                    fontSize = 11.sp,
                ),
            )
            // Triggers the same background sync the app uses; it never touches credentials
            // here, it only asks WorkManager to run a pass.
            //
            // The clickable box is 48dp — the platform minimum touch target — while the chip
            // inside stays 26dp, so the control is reachable on a home screen without the
            // header reading as a button bar. Its transparent margin also supplies the gap to
            // the freshness label, which is why no spacer precedes it.
            Box(
                modifier = GlanceModifier
                    .size(48.dp)
                    .clickable(actionRunCallback<RefreshWidgetAction>()),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(26.dp)
                        .cornerRadius(13.dp)
                        .background(W.Card),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "↻",
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(W.TextPrimary),
                            fontSize = 14.sp,
                        ),
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun AccountCard(account: WidgetAccount) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(16.dp)
                .background(W.Card)
                .padding(10.dp),
        ) {
            Text(
                text = account.title,
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(W.TextPrimary),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            // The one line that carries staleness. Row severities never do — they are computed
            // from the percentage alone — so without this a two-day-old snapshot rendered every
            // bar in healthy green with nothing on the tile saying the numbers were old. The
            // compact widget already surfaces this; the larger one did not.
            when (account.severity) {
                Severity.STALE -> Text(
                    text = "Stale — not refreshed recently",
                    style = TextStyle(
                        color = androidx.glance.unit.ColorProvider(W.textColor(Severity.STALE)),
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
                Severity.ERROR -> Text(
                    text = "Refresh failed — showing last known numbers",
                    style = TextStyle(
                        color = androidx.glance.unit.ColorProvider(W.textColor(Severity.ERROR)),
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
                else -> Unit
            }
            // Provider and plan are not unique: two accounts on the same plan render an
            // identical title, and the masked address is the only thing that tells them apart.
            account.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = TextStyle(
                        color = androidx.glance.unit.ColorProvider(W.TextSecondary),
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
            }
            account.rows.forEach { row ->
                Spacer(GlanceModifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.label,
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(W.TextSecondary),
                            fontSize = 11.sp,
                        ),
                        modifier = GlanceModifier.width(52.dp),
                        maxLines = 1,
                    )
                    UsageBar(row, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = percentText(row),
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(W.barText(row)),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = GlanceModifier.width(42.dp),
                        maxLines = 1,
                    )
                    Text(
                        text = row.resetAt
                            ?.let {
                                Countdown.absoluteResetLabel(it, System.currentTimeMillis())
                                    ?.removePrefix("Resets ")
                            }
                            ?: "—",
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(W.TextSecondary),
                            fontSize = 11.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_ACCOUNTS = 3
    }
}

class CompactUsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CompactUsageWidget()
}

class DetailedUsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DetailedUsageWidget()
}
