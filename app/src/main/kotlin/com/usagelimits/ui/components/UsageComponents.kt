package com.usagelimits.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.core.model.percentLabel
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val motionEnabled = LocalMotionEnabled.current
    val animatedFraction by animateFloatAsState(targetValue = target, animationSpec = if (motionEnabled) tween(240) else snap(), label = "usageBar")
    val targetColor = SeverityPalette.barColor(remainingPercent, severity)
    val animatedColor by animateColorAsState(targetValue = targetColor, animationSpec = if (motionEnabled) tween(240) else snap(), label = "usageColor")
    val fraction = if (motionEnabled) animatedFraction else target
    val color = if (motionEnabled) animatedColor else targetColor

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
fun StatusPill(severity: Severity, modifier: Modifier = Modifier, label: String? = null) {
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
            text = label ?: SeverityPalette.label(severity),
            style = MaterialTheme.typography.labelMedium,
            // textColor, not accent: the dot can be the raw accent, the word cannot.
            color = SeverityPalette.textColor(severity),
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
fun UsageWindowRow(window: UsageWindow, nowMs: Long, modifier: Modifier = Modifier) {
    val remaining = window.remainingPercent
    val percent = percentLabel(remaining)
    val label = when (window.category) {
        com.usagelimits.core.model.WindowCategory.FIVE_HOUR -> "5h limit"
        com.usagelimits.core.model.WindowCategory.WEEKLY -> "Weekly"
        com.usagelimits.core.model.WindowCategory.MONTHLY -> "Monthly"
        else -> window.label
    }
    val reset = Countdown.resetLabel(window.resetAt, nowMs)
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "$label, $percent remaining" + (reset?.let { ", $it" } ?: "")
    }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.TextSecondary, maxLines = 2)
            Text(percent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = SeverityPalette.textColor(window.severity))
        }
        UsageBar(remaining, window.severity, Modifier.fillMaxWidth(), height = 6.dp)
        if (reset != null) Text(reset, style = MaterialTheme.typography.bodySmall, color = UsageColors.TextTertiary)
        Spacer(Modifier.height(3.dp))
    }
}

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

/**
 * A labelled switch.
 *
 * Shared rather than duplicated: the settings screen and one account's detail screen both need
 * exactly this row, and a second copy is how two switches end up looking slightly different.
 */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = UsageColors.TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.TextSecondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            modifier = Modifier.semantics { contentDescription = title },
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = UsageColors.Background,
                checkedTrackColor = UsageColors.Terracotta,
                uncheckedThumbColor = UsageColors.TextTertiary,
                uncheckedTrackColor = UsageColors.SurfaceMuted,
            ),
        )
    }
}
