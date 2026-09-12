package com.usagelimits.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.usagelimits.R
import com.usagelimits.core.model.ProviderId
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usagelimits.ui.theme.UsageColors

fun providerLogoResource(provider: ProviderId): Int = when (provider) {
    ProviderId.CODEX -> R.drawable.provider_codex
    ProviderId.CLAUDE -> R.drawable.provider_claude
    ProviderId.ANTIGRAVITY -> R.drawable.provider_antigravity
    ProviderId.XAI -> R.drawable.provider_grok
    ProviderId.KIMI -> R.drawable.provider_kimi
}

@Composable
fun ProviderLogo(provider: ProviderId, modifier: Modifier = Modifier) {
    Image(painterResource(providerLogoResource(provider)), provider.displayName, modifier)
}

@Composable
fun ProviderBadge(provider: ProviderId, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(UsageColors.SurfaceElevated),
        contentAlignment = Alignment.Center) { ProviderLogo(provider, Modifier.size(size * 0.58f)) }
}
