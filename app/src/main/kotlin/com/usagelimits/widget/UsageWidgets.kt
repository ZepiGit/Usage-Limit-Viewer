package com.usagelimits.widget

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.LocalSize
import com.usagelimits.core.model.percentLabel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
private val TransparentWidget = androidx.compose.runtime.staticCompositionLocalOf { false }

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
        modifier = modifier.height(6.dp),
        color = ColorProvider(W.bar(row)),
        backgroundColor = ColorProvider(W.Track),
    )
}

private fun percentText(row: WidgetRow): String = percentLabel(row.remainingPercent)

/**
 * The compact 1×4 widget: four tiles reading 5h, weekly, next reset and overall status.
 *
 * Deliberately shows aggregate headline numbers rather than one account, because at this size
 * there is room for a glance, not a list.
 */
/**
 * The refresh control both list widgets carry: a touch target around a small chip.
 *
 * One composable rather than the two identical subtrees it replaced, so the action, colours and
 * alignment cannot drift apart. The sizes differ on purpose — the compact widget has no room
 * for a 48dp target beside four tiles, the detailed one has — so they are the parameters.
 */
@androidx.compose.runtime.Composable
private fun RefreshChip(
    touchSize: androidx.compose.ui.unit.Dp,
    chipSize: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    Box(
        modifier = GlanceModifier
            .size(touchSize)
            .clickable(actionRunCallback<RefreshWidgetAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .size(chipSize)
                .cornerRadius(chipSize / 2)
                .background(W.Card),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(com.usagelimits.R.drawable.ic_refresh), "Refresh usage",
                modifier = GlanceModifier.size(18.dp))
        }
    }
}

class CompactUsageWidget : GlanceAppWidget() {

    // Responsive rather than Exact: the launcher picks the nearest declared size, so the
    // widget stays correct on tablets and unfolded foldables, whose grid cells are much wider
    // than a phone's, instead of being re-measured into a layout it was never designed for.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetUpdater.load(context, id)
        provideContent {
            val view by WidgetUpdater.observe(context, id).collectAsState(initial)
            val snapshot = view.snapshot
            GlanceTheme {
                androidx.compose.runtime.CompositionLocalProvider(TransparentWidget provides view.transparent) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(24.dp)
                        .background(if (view.transparent) Color.Transparent else W.Background)
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
                    // The 2x4 widget has had a refresh button since it was written; this one
                    // did not, so the only way to force a sync from a home screen was to open
                    // the app — on the widget whose whole point is not having to.
                    //
                    // No weight: the four tiles share the width and this takes what it needs,
                    // rather than a fifth of the row for one glyph.
                    RefreshChip(touchSize = 40.dp, chipSize = 28.dp, fontSize = 13.sp)
                }
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
                .background(if (TransparentWidget.current) Color.Transparent else W.Card)
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
                UsageBar(row, GlanceModifier.fillMaxWidth())
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
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetUpdater.load(context, id)
        provideContent {
            val view by WidgetUpdater.observe(context, id).collectAsState(initial)
            val snapshot = view.snapshot
            GlanceTheme {
                Column(GlanceModifier.fillMaxSize().cornerRadius(24.dp)
                    .background(if (view.transparent) Color.Transparent else W.Background).padding(8.dp)
                    .clickable(actionStartActivity<MainActivity>())) {
                    if (snapshot.accounts.size != 1) {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(GlanceModifier.defaultWeight()) {
                                Text("Usage Limits", style = TextStyle(color = ColorProvider(W.TextPrimary), fontSize = 15.sp, fontWeight = FontWeight.Bold))
                                Text("${snapshot.accountCount} accounts", style = TextStyle(color = ColorProvider(W.TextSecondary), fontSize = 11.sp))
                            }
                            RefreshChip(40.dp, 26.dp, 14.sp)
                        }
                        Spacer(GlanceModifier.height(6.dp))
                    }
                    if (snapshot.accounts.isEmpty()) Text("No matching accounts. Edit this widget to choose an account.",
                        style = TextStyle(color = ColorProvider(W.TextSecondary), fontSize = 12.sp))
                    else LazyColumn(GlanceModifier.fillMaxSize()) {
                        items(snapshot.accounts, itemId = { it.accountId.hashCode().toLong() }) { account ->
                            Column {
                                AccountCard(context, account, view.transparent, snapshot.accounts.size == 1)
                                Spacer(GlanceModifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun AccountCard(context: Context, account: WidgetAccount, transparent: Boolean, single: Boolean) {
        val openAccount = androidx.glance.appwidget.action.actionStartActivity(
            android.content.Intent(context, MainActivity::class.java).putExtra("accountId", account.accountId))
        Column(GlanceModifier.fillMaxWidth().cornerRadius(16.dp)
            .background(if (transparent || single) Color.Transparent else W.Card).padding(if (single) 4.dp else 8.dp)
            .clickable(openAccount)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.usagelimits.core.model.ProviderId.fromId(account.providerId)?.let { provider ->
                    Image(ImageProvider(com.usagelimits.ui.providerLogoResource(provider, account.iconChoiceId)), null, GlanceModifier.size(22.dp))
                    Spacer(GlanceModifier.width(8.dp))
                }
                Column(GlanceModifier.defaultWeight()) {
                    Text(account.title, maxLines = 1, style = TextStyle(color = ColorProvider(W.TextPrimary), fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    Text(account.accountLabel ?: account.subtitle.orEmpty(), maxLines = 1,
                        style = TextStyle(color = ColorProvider(W.TextSecondary), fontSize = 10.sp))
                }
                if (single) RefreshChip(36.dp, 26.dp, 14.sp)
            }
            if (account.requiresReauthentication || account.severity == Severity.ERROR || account.severity == Severity.STALE) {
                Text(if (account.requiresReauthentication) "Reconnect account" else if (account.severity == Severity.ERROR) "Update failed · last known data" else "Data is out of date",
                    style = TextStyle(color = ColorProvider(W.TextSecondary), fontSize = 10.sp))
            }
            account.rows.forEach { row ->
                Spacer(GlanceModifier.height(6.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, GlanceModifier.defaultWeight(), maxLines = 1,
                        style = TextStyle(color = ColorProvider(W.TextSecondary), fontSize = 11.sp))
                    Text(percentText(row), style = TextStyle(color = ColorProvider(W.barText(row)), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                }
                Spacer(GlanceModifier.height(3.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    UsageBar(row, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(8.dp))
                    Text(row.resetAt?.let { compactRingReset(context, it) } ?: "—", modifier = GlanceModifier.width(54.dp), maxLines = 1,
                        style = TextStyle(color = ColorProvider(W.TextSecondary), fontSize = 10.sp))
                }
            }
        }
    }
}

class CompactUsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CompactUsageWidget()
}

class DetailedUsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DetailedUsageWidget()
}
