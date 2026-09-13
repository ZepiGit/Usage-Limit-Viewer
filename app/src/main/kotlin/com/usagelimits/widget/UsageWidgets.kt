package com.usagelimits.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import com.usagelimits.core.model.percentLabel
import com.usagelimits.core.time.Countdown

/**
 * The style of the widget being composed: its panel and the ink derived from it.
 *
 * Every colour a widget draws comes from here, never from a constant. Glance runs in the
 * launcher's process and cannot read the app's Compose theme, and the panel is the user's
 * per-widget choice, so the palette has to travel with the composition. See [WidgetStyle]
 * for how the ink follows the panel.
 */
internal val LocalWidgetStyle = staticCompositionLocalOf { WidgetStyle() }

/**
 * A usage bar.
 *
 * Glance's own LinearProgressIndicator fills the width it is given, so the bar is correct at
 * every launcher grid size. The previous hand-rolled version took a fixed dp width, which was
 * wider than a tile actually gets: the fill was clipped, and anything above roughly half
 * remaining painted as a full bar — a confidently wrong number on the home screen.
 */
@Composable
internal fun UsageBar(row: WidgetRow, modifier: GlanceModifier = GlanceModifier) {
    val style = LocalWidgetStyle.current
    val fraction = ((row.remainingPercent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()

    LinearProgressIndicator(
        progress = fraction,
        modifier = modifier.height(6.dp),
        color = ColorProvider(style.bar(row)),
        backgroundColor = ColorProvider(style.track),
    )
}

private fun percentText(row: WidgetRow): String = percentLabel(row.remainingPercent)

/**
 * The refresh control both list widgets carry: a touch target around a small chip.
 *
 * One composable rather than the two identical subtrees it replaced, so the action, colours and
 * alignment cannot drift apart. The sizes differ on purpose — the compact widget has no room
 * for a 48dp target beside four tiles, the detailed one has — so they are the parameters.
 *
 * The glyph is tinted with the widget's ink rather than shipped white: on a light panel a
 * white arrow is invisible.
 */
@Composable
internal fun RefreshChip(touchSize: Dp, chipSize: Dp) {
    val style = LocalWidgetStyle.current
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
                .background(style.card),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                ImageProvider(com.usagelimits.R.drawable.ic_refresh),
                "Refresh usage",
                modifier = GlanceModifier.size(18.dp),
                colorFilter = ColorFilter.tint(ColorProvider(style.textPrimary)),
            )
        }
    }
}

/** A tile or card on the panel; nothing at all when the panel itself is gone. */
private fun WidgetStyle.cardOrNothing(): Color = if (background.isTransparent) Color.Transparent else card

/**
 * The compact 1×4 widget: four tiles reading 5h, weekly, next reset and overall status.
 *
 * Deliberately shows aggregate headline numbers rather than one account, because at this size
 * there is room for a glance, not a list.
 */
class CompactUsageWidget : GlanceAppWidget() {

    // Exact rather than Responsive: the launcher reports the real size, and the tiles share
    // whatever width that is, so the widget stays correct on tablets and unfolded foldables.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetUpdater.load(context, id)
        provideContent {
            val view by WidgetUpdater.observe(context, id).collectAsState(initial)
            val snapshot = view.snapshot
            val style = view.style
            GlanceTheme {
                CompositionLocalProvider(LocalWidgetStyle provides style) {
                    Row(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .cornerRadius(24.dp)
                            .background(style.background.color)
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
                            // The row already knows what it is. Hardcoding "Weekly" here
                            // labelled a monthly credit bucket as a week: the user paced their
                            // spending against a reset days away that was actually a month
                            // away, while the larger widget beside it, using the row's own
                            // label, said "Monthly".
                            label = snapshot.headlineLong?.label ?: "Weekly",
                            value = snapshot.headlineLong?.let(::percentText) ?: "—",
                            row = snapshot.headlineLong,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        Tile(
                            // A wall-clock instant rather than a countdown: a widget recomposes
                            // only when its worker runs, so "in 20m" rendered half an hour ago
                            // is not stale but wrong — the limit has already reset.
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
                            accent = style.textColor(snapshot.overallSeverity),
                        )
                        // No weight: the four tiles share the width and this takes what it
                        // needs, rather than a fifth of the row for one glyph.
                        RefreshChip(touchSize = 40.dp, chipSize = 28.dp)
                    }
                }
            }
        }
    }

    @Composable
    private fun Tile(
        label: String,
        value: String,
        row: WidgetRow?,
        modifier: GlanceModifier = GlanceModifier,
        accent: Color? = null,
    ) {
        val style = LocalWidgetStyle.current
        Column(
            modifier = modifier
                .cornerRadius(16.dp)
                .background(style.cardOrNothing())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 11.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = value,
                style = TextStyle(
                    color = ColorProvider(accent ?: style.textPrimary),
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
 * A single selected account fills the widget with no card around it and carries its own
 * refresh control; several scroll, each in a card.
 */
class DetailedUsageWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetUpdater.load(context, id)
        provideContent {
            val view by WidgetUpdater.observe(context, id).collectAsState(initial)
            val snapshot = view.snapshot
            val style = view.style
            GlanceTheme {
                CompositionLocalProvider(LocalWidgetStyle provides style) {
                    Column(
                        GlanceModifier.fillMaxSize().cornerRadius(24.dp)
                            .background(style.background.color).padding(8.dp)
                            .clickable(actionStartActivity<MainActivity>()),
                    ) {
                        if (snapshot.accounts.size != 1) {
                            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(GlanceModifier.defaultWeight()) {
                                    Text("Usage Limits", style = TextStyle(color = ColorProvider(style.textPrimary), fontSize = 15.sp, fontWeight = FontWeight.Bold))
                                    Text("${snapshot.accountCount} accounts", style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 11.sp))
                                }
                                RefreshChip(40.dp, 26.dp)
                            }
                            Spacer(GlanceModifier.height(6.dp))
                        }
                        if (snapshot.accounts.isEmpty()) {
                            Text(
                                "No matching accounts. Edit this widget to choose an account.",
                                style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 12.sp),
                            )
                        } else {
                            LazyColumn(GlanceModifier.fillMaxSize()) {
                                items(snapshot.accounts, itemId = { it.accountId.hashCode().toLong() }) { account ->
                                    Column {
                                        AccountCard(context, account, snapshot.accounts.size == 1)
                                        Spacer(GlanceModifier.height(6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AccountCard(context: Context, account: WidgetAccount, single: Boolean) {
        val style = LocalWidgetStyle.current
        val openAccount = androidx.glance.appwidget.action.actionStartActivity(
            android.content.Intent(context, MainActivity::class.java).putExtra("accountId", account.accountId),
        )
        Column(
            GlanceModifier.fillMaxWidth().cornerRadius(16.dp)
                .background(if (single) Color.Transparent else style.cardOrNothing())
                .padding(if (single) 4.dp else 8.dp)
                .clickable(openAccount),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.usagelimits.core.model.ProviderId.fromId(account.providerId)?.let { provider ->
                    Image(ImageProvider(com.usagelimits.ui.providerLogoResource(provider, account.iconChoiceId)), null, GlanceModifier.size(22.dp))
                    Spacer(GlanceModifier.width(8.dp))
                }
                Column(GlanceModifier.defaultWeight()) {
                    Text(account.title, maxLines = 1, style = TextStyle(color = ColorProvider(style.textPrimary), fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    Text(
                        account.accountLabel ?: account.subtitle.orEmpty(), maxLines = 1,
                        style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 10.sp),
                    )
                }
                if (single) RefreshChip(36.dp, 26.dp)
            }
            if (account.requiresReauthentication || account.severity == Severity.ERROR || account.severity == Severity.STALE) {
                Text(
                    when {
                        account.requiresReauthentication -> "Reconnect account"
                        account.severity == Severity.ERROR -> "Update failed · last known data"
                        else -> "Data is out of date"
                    },
                    style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 10.sp),
                )
            }
            account.rows.forEach { row ->
                Spacer(GlanceModifier.height(6.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.label, GlanceModifier.defaultWeight(), maxLines = 1,
                        style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 11.sp),
                    )
                    Text(percentText(row), style = TextStyle(color = ColorProvider(style.barText(row)), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                }
                Spacer(GlanceModifier.height(3.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    UsageBar(row, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        row.resetAt?.let { compactRingReset(context, it) } ?: "—", modifier = GlanceModifier.width(54.dp), maxLines = 1,
                        style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 10.sp),
                    )
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
