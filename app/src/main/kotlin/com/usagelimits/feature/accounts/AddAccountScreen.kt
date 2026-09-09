package com.usagelimits.feature.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagelimits.core.model.ProviderId
import com.usagelimits.feature.overview.providerSymbol
import com.usagelimits.feature.overview.providerTint
import com.usagelimits.ui.components.IconBadge
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors

/**
 * Add-account flow.
 *
 * Each provider's step is described in plain language before the browser opens, because an
 * unexplained jump to an OAuth page is exactly what a phishing flow looks like.
 */
@Composable
fun AddAccountScreen(
    state: AddAccountState,
    providers: List<ProviderId>,
    onStart: (ProviderId) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Add account",
            style = MaterialTheme.typography.headlineLarge,
            color = UsageColors.TextPrimary,
        )

        when (state) {
            is AddAccountState.PickProvider -> {
                Text(
                    text = "You can connect several accounts per provider.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = UsageColors.TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                providers.forEach { provider ->
                    ProviderRow(provider) { onStart(provider) }
                }
            }

            is AddAccountState.Starting -> LoadingCard("Contacting ${state.provider.displayName}…")

            is AddAccountState.AwaitingDeviceCode -> {
                UsageCard {
                    Text(
                        text = "Enter this code",
                        style = MaterialTheme.typography.titleMedium,
                        color = UsageColors.TextPrimary,
                    )
                    Text(
                        text = "Your browser is opening ${state.verificationUri}. Sign in there " +
                            "and enter the code below — this app never sees your password.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = UsageColors.TextSecondary,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = state.userCode,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        // Wide tracking so the code is easy to read a character at a time.
                        letterSpacing = 6.sp,
                        color = UsageColors.Terracotta,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = UsageColors.TextTertiary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Waiting for approval…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = UsageColors.TextSecondary,
                        )
                    }
                }
                TextButton(onClick = onCancel) { Text("Cancel", color = UsageColors.TextSecondary) }
            }

            is AddAccountState.AwaitingBrowser -> {
                LoadingCard(
                    "Complete the sign-in in your browser. You'll come straight back here.",
                )
                TextButton(onClick = onCancel) { Text("Cancel", color = UsageColors.TextSecondary) }
            }

            is AddAccountState.Success -> {
                UsageCard {
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.titleMedium,
                        color = UsageColors.Green,
                    )
                    Text(
                        text = "${state.accountLabel} is now being monitored.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = UsageColors.TextSecondary,
                    )
                }
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UsageColors.Terracotta,
                        contentColor = UsageColors.Background,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Done") }
            }

            is AddAccountState.Failed -> {
                UsageCard(borderColor = UsageColors.RedSurface) {
                    Text(
                        text = "Sign-in failed",
                        style = MaterialTheme.typography.titleMedium,
                        color = UsageColors.Red,
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = UsageColors.TextSecondary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.provider?.let { provider ->
                        Button(
                            onClick = { onStart(provider) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = UsageColors.Terracotta,
                                contentColor = UsageColors.Background,
                            ),
                        ) { Text("Try again") }
                    }
                    TextButton(onClick = onCancel) {
                        Text("Back", color = UsageColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(provider: ProviderId, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(UsageColors.Surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            symbol = providerSymbol(provider),
            tint = providerTint(provider),
            container = UsageColors.SurfaceElevated,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = UsageColors.TextPrimary,
            )
            Text(
                text = loginHint(provider),
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.TextSecondary,
            )
        }
    }
}

/** Sets expectations before the browser opens — device code versus a redirect back. */
private fun loginHint(provider: ProviderId): String = when (provider) {
    ProviderId.CODEX -> "Sign in with a device code"
    ProviderId.XAI -> "Sign in with a device code"
    ProviderId.CLAUDE -> "Sign in in your browser"
    ProviderId.ANTIGRAVITY -> "Sign in with Google"
}

@Composable
private fun LoadingCard(message: String) {
    UsageCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = UsageColors.Terracotta,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.TextSecondary,
            )
        }
    }
}
