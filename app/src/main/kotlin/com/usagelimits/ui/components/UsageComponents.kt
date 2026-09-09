package com.usagelimits.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.time.Countdown
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors

/**
 * A quota bar.
 *
 * Fill length encodes what is *left*, not what is used, so a long bar always reads as good —
 * the direction people expect from a battery meter.
 */
@Composable
fun UsageBar(
    remainingPercent: Double?,
    severity: Severity,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val target = ((remainingPercent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val fraction by animateFloatAsState(targetValue = target, label = "usageBar")
    val color = SeverityPalette.barColor(remainingPercent, severity)

    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(UsageColors.ProgressTrack),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(height)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** The "Healthy" / "Low" chip: a dot plus a word, so status never rests on colour alone. */
@Composable
fun StatusPill(severity: Severity, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(SeverityPalette.container(severity))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(SeverityPalette.accent(severity)),
        )
        Text(
            text = SeverityPalette.label(severity),
            style = MaterialTheme.typography.labelMedium,
            color = SeverityPalette.accent(severity),
        )
    }
}

/**
 * One quota row: label, bar, remaining percent, reset countdown.
 *
 * The whole row is collapsed into a single spoken sentence for screen readers, because
 * hearing four disconnected fragments per window is unusable once an account has five of them.
 */
@Composable
fun UsageWindowRow(
    window: UsageWindow,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val remaining = window.remainingPercent
    val percentText = remaining?.let { "${it.toInt()}%" } ?: "—"
    val qualifier = if (remaining != null && remaining >= 99.5) "available" else "remaining"
    val resetText = Countdown.resetLabel(window.resetAt, nowMs)

    val spoken = buildString {
        append(window.label)
        append(", ")
        append(if (remaining != null) "$percentText $qualifier" else "usage unknown")
        resetText?.let { append(", ").append(it) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = window.label,
            style = MaterialTheme.typography.bodyMedium,
            color = UsageColors.TextSecondary,
            maxLines = 1,
            modifier = Modifier.width(96.dp),
        )
        UsageBar(
            remainingPercent = remaining,
            severity = window.severity,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Row(
            modifier = Modifier.width(104.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = percentText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = SeverityPalette.barColor(remaining, window.severity),
            )
            Text(
                text = qualifier,
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.TextTertiary,
                maxLines = 1,
            )
        }
        Text(
            text = resetText?.removePrefix("Reset in ")?.let { "Reset $it" } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = UsageColors.TextSecondary,
            maxLines = 1,
            modifier = Modifier.width(112.dp),
        )
    }
}

/** The app's standard card: large radius, lifted surface, hairline border. */
@Composable
fun UsageCard(
    modifier: Modifier = Modifier,
    borderColor: Color = UsageColors.Outline,
    background: Color = UsageColors.Surface,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(22.dp))
            .padding(16.dp),
        content = content,
    )
}

/** A round tinted glyph holder — provider avatars and the summary card's stat icons. */
@Composable
fun IconBadge(
    symbol: String,
    tint: Color,
    container: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            // Decorative: the surrounding row already names the thing this sits beside.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = UsageColors.TextPrimary,
        )
        trailing?.invoke()
    }
}
