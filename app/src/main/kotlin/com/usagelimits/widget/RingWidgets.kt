package com.usagelimits.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
import com.usagelimits.MainActivity
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.core.time.Countdown
import com.usagelimits.ui.providerLogoResource
import com.usagelimits.ui.theme.UsageColors

open class AccountRingsWidget(private val mini: Boolean) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetUpdater.load(context, id)
        provideContent {
            val view by WidgetUpdater.observe(context, id).collectAsState(initial)
            val size = LocalSize.current
            val grid = WidgetLayout.miniGrid(size.width.value, size.height.value, view.metrics)
            val columns = if (mini) grid.columns else WidgetLayout.accountColumns(size.width.value, size.height.value, view.metrics)
            val selected = if (mini) view.snapshot.accounts.take(grid.capacity) else view.snapshot.accounts
            val groups = selected.chunked(columns)
            val rowHeight = if (mini) ((size.height.value - 8f) / grid.rows).coerceAtLeast(20f).dp else 44.dp
            val ringSize = if (mini) minOf((size.width.value - 8f) / columns, rowHeight.value).minus(8f).coerceIn(16f, 52f).dp else 36.dp
            Column(GlanceModifier.fillMaxSize().cornerRadius(24.dp)
                .background(if (view.transparent) Color.Transparent else UsageColors.Background)
                .padding(if (mini) 4.dp else 10.dp)) {
                if (groups.isEmpty()) Text("No matching accounts", style = TextStyle(color = ColorProvider(UsageColors.TextSecondary), fontSize = 12.sp))
                else if (mini) {
                    // Fixed visible capacity also avoids sharing a collection adapter between
                    // differently sized portrait and landscape RemoteViews.
                    groups.forEach { group -> RingAccountRow(context, group, columns, rowHeight, ringSize, true) }
                } else LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(groups, itemId = { it.first().accountId.hashCode().toLong() }) { group ->
                        RingAccountRow(context, group, columns, rowHeight, ringSize, false)
                    }
                }
            }
        }
    }
}

@Composable
private fun RingAccountRow(context: Context, group: List<WidgetAccount>, columns: Int,
    rowHeight: androidx.compose.ui.unit.Dp, ringSize: androidx.compose.ui.unit.Dp, mini: Boolean) {
    Row(GlanceModifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
        group.forEach { account ->
            val action = actionStartActivity(Intent(context, MainActivity::class.java).putExtra("accountId", account.accountId))
            if (mini) Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(2.dp).clickable(action), contentAlignment = Alignment.Center) {
                RingMark(account, ringSize)
            } else Row(GlanceModifier.defaultWeight().fillMaxHeight().padding(2.dp).clickable(action), verticalAlignment = Alignment.CenterVertically) {
                RingMark(account, ringSize)
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(account.accountLabel ?: account.subtitle ?: account.title, maxLines = 1,
                        style = TextStyle(color = ColorProvider(UsageColors.TextPrimary), fontSize = 11.sp, fontWeight = FontWeight.Medium))
                    Text(if (account.requiresReauthentication) "Reconnect" else ringLimit(account)?.resetAt?.let {
                        compactRingReset(context, it)
                    } ?: "No reset time", maxLines = 1,
                        style = TextStyle(color = ColorProvider(UsageColors.TextSecondary), fontSize = 11.sp))
                }
            }
        }
        repeat(columns - group.size) { Spacer(GlanceModifier.defaultWeight()) }
    }
}

private fun ringLimit(account: WidgetAccount): WidgetRow? = account.rows.minWithOrNull(
    compareBy<WidgetRow> { if (it.severity == Severity.EXHAUSTED) -1.0 else it.remainingPercent ?: Double.MAX_VALUE })

internal fun compactRingReset(context: Context, resetAt: Long): String {
    val now = System.currentTimeMillis()
    if (resetAt <= now) return "Reset due"
    val zone = java.time.ZoneId.systemDefault()
    val reset = java.time.Instant.ofEpochMilli(resetAt).atZone(zone)
    return if (reset.toLocalDate() == java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) {
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(resetAt))
    } else reset.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()))
}

@Composable
private fun RingMark(account: WidgetAccount, size: androidx.compose.ui.unit.Dp) {
    val row = ringLimit(account)
    val accent = when {
        account.requiresReauthentication -> UsageColors.Red
        account.severity == Severity.ERROR || account.severity == Severity.STALE -> UsageColors.Slate
        row?.severity == Severity.EXHAUSTED -> UsageColors.Red
        row?.severity == Severity.LOW || row?.severity == Severity.MEDIUM -> UsageColors.Amber
        else -> UsageColors.Green
    }
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(ImageProvider(UsageRing.draw(160, row?.remainingPercent, accent.toArgb(), UsageColors.ProgressTrack.toArgb())),
            "${account.title}, ${account.subtitle.orEmpty()}, ${com.usagelimits.core.model.percentLabel(row?.remainingPercent)} remaining" +
                if (account.requiresReauthentication) ", reconnect required" else "",
            GlanceModifier.fillMaxSize())
        ProviderId.fromId(account.providerId)?.let {
            Image(ImageProvider(providerLogoResource(it, account.iconChoiceId)), null, GlanceModifier.size(size * 0.46f))
        }
    }
}

class MinimalUsageWidget : AccountRingsWidget(false)
class MiniRingsWidget : AccountRingsWidget(true)
class MinimalUsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MinimalUsageWidget()
}
class MiniRingsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MiniRingsWidget()
}
