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
    val Background = Color(0xFF141413)
    val Card = Color(0xFF242322)
    val Track = Color(0xFF2E2D2B)
    val TextPrimary = Color(0xFFF0EEE6)
    val TextSecondary = Color(0xFFB4B2A9)
    val Teal = Color(0xFF4FBFA8)
    val Green = Color(0xFF6FBF73)
    val Amber = Color(0xFFE0A33E)
    val Red = Color(0xFFD9584F)
    val Slate = Color(0xFF6B6960)

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
}

/**
 * A usage bar.
 *
 * Glance has no progress primitive that can be tinted per-item, so the bar is two nested
 * boxes with a computed width. [maxWidth] has to be supplied because Glance cannot measure a
 * fractional width at layout time.
 */
@androidx.compose.runtime.Composable
private fun UsageBar(row: WidgetRow, maxWidth: Int) {
    val fraction = ((row.remainingPercent ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
    val filled = (maxWidth * fraction).toInt().coerceAtLeast(if (fraction > 0) 4 else 0)

    Box(
        modifier = GlanceModifier
            .width(maxWidth.dp)
            .height(6.dp)
            .cornerRadius(3.dp)
            .background(W.Track),
    ) {
        if (filled > 0) {
            Box(
                modifier = GlanceModifier
                    .width(filled.dp)
                    .height(6.dp)
                    .cornerRadius(3.dp)
                    .background(W.bar(row)),
            ) {}
        }
    }
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
                        label = "Weekly",
                        value = snapshot.headlineLong?.let(::percentText) ?: "—",
                        row = snapshot.headlineLong,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Tile(
                        label = "Resets in",
                        value = snapshot.nextResetAt
                            ?.let { Countdown.format(it - System.currentTimeMillis()) }
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
                        accent = W.accent(snapshot.overallSeverity),
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
                UsageBar(row, maxWidth = 62)
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
                text = Countdown.freshnessLabel(snapshot.updatedAt, System.currentTimeMillis())
                    .removePrefix("Updated "),
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(W.TextSecondary),
                    fontSize = 11.sp,
                ),
            )
            Spacer(GlanceModifier.width(8.dp))
            // Triggers the same background sync the app uses; it never touches credentials
            // here, it only asks WorkManager to run a pass.
            Box(
                modifier = GlanceModifier
                    .size(26.dp)
                    .cornerRadius(13.dp)
                    .background(W.Card)
                    .clickable(actionRunCallback<RefreshWidgetAction>()),
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
                    UsageBar(row, maxWidth = 88)
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = percentText(row),
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(W.bar(row)),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = GlanceModifier.width(42.dp),
                        maxLines = 1,
                    )
                    Text(
                        text = row.resetAt
                            ?.let { Countdown.format(it - System.currentTimeMillis()) }
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
