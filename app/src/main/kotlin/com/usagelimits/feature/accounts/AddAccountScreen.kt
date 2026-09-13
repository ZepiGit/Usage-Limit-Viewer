package com.usagelimits.feature.accounts

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import com.usagelimits.ui.theme.LocalMotionEnabled
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
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
    onSubmitApiKey: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The pasted-key way in, for a provider that offers one beside its flow. */
    onStartWithKey: (ProviderId) -> Unit = {},
    /** The device-code way in, for a provider whose browser redirect may not land. */
    onStartWithDeviceCode: (ProviderId) -> Unit = {},
) {
    val context = LocalContext.current
    val activeState by rememberUpdatedState(state)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Add account",
            style = MaterialTheme.typography.headlineLarge,
            color = UsageColors.TextPrimary,
        )

        Crossfade(
            targetState = state,
            animationSpec = tween(if (LocalMotionEnabled.current) 180 else 0),
            label = "signInStep",
        ) { displayedState ->
            // Crossfade remembers outgoing content, so read the latest state through a State.
            val acceptsInput = { displayedState == activeState }
            Column(
                modifier = if (acceptsInput()) Modifier else Modifier.clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val state = displayedState) {
                    is AddAccountState.PickProvider -> {
                        Text(
                            text = "You can connect several accounts per provider.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = UsageColors.TextSecondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        providers.forEach { provider ->
                            ProviderRow(provider) { if (acceptsInput()) onStart(provider) }
                        }
                    }

                    is AddAccountState.Starting -> LoadingCard("Contacting ${state.provider.displayName}…")

                    is AddAccountState.AwaitingDeviceCode -> {
                        // Reading the code off the screen and typing it into a browser on the same phone
                        // means holding eight characters in your head while switching apps. The code goes
                        // to the clipboard the moment it exists, so the sign-in page needs a paste and
                        // nothing else.
                        val clipboard = LocalClipboardManager.current
                        val uriHandler = LocalUriHandler.current
                        var copied by remember(state.userCode) { mutableStateOf(false) }
                        val copy = {
                            clipboard.setText(AnnotatedString(state.userCode))
                            copied = true
                        }
                        LaunchedEffect(state.userCode) { copy() }

                        UsageCard {
                            Text(
                                text = "Enter this code",
                                style = MaterialTheme.typography.titleMedium,
                                color = UsageColors.TextPrimary,
                            )
                            Text(
                                text = "Your browser is opening ${state.verificationUri}. Paste the code " +
                                    "there — this app never sees your password.",
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // The code itself is the biggest target on the card, so it is also
                                    // the copy button.
                                    .clickable(onClick = { if (acceptsInput()) copy() }),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (copied) "Copied to your clipboard" else "Tap the code to copy",
                                style = MaterialTheme.typography.bodySmall,
                                color = UsageColors.TextTertiary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { if (acceptsInput()) copy() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = UsageColors.Terracotta,
                                        contentColor = UsageColors.Background,
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) { Text("Copy code") }
                                // The browser was opened once already. This is for the times it was not —
                                // no default browser, the tab dismissed, the wrong profile — where the
                                // flow is otherwise dead with a code and nowhere to put it.
                                Button(
                                    onClick = { if (acceptsInput()) uriHandler.openUri(state.verificationUri) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = UsageColors.TerracottaSurface,
                                        contentColor = UsageColors.Terracotta,
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) { Text("Open page") }
                            }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.keyAlternative) {
                                // The flow is the default because it costs the user nothing. The key
                                // is for when the flow's account cannot reach the API yet.
                                TextButton(onClick = { if (acceptsInput()) onStartWithKey(state.provider) }) {
                                    Text("Use an API key instead", color = UsageColors.Terracotta)
                                }
                            }
                            TextButton(onClick = { if (acceptsInput()) onCancel() }) { Text("Cancel", color = UsageColors.TextSecondary) }
                        }
                    }

                    is AddAccountState.AwaitingApiKey -> {
                        val uriHandler = LocalUriHandler.current
                        var key by remember(state.provider) { mutableStateOf("") }

                        UsageCard {
                            Text(
                                text = "Paste your ${state.provider.displayName} key",
                                style = MaterialTheme.typography.titleMedium,
                                color = UsageColors.TextPrimary,
                            )
                            Text(
                                text = state.hint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = UsageColors.TextSecondary,
                            )
                            Spacer(Modifier.height(14.dp))
                            OutlinedTextField(
                                value = key,
                                onValueChange = { if (acceptsInput()) key = it },
                                singleLine = true,
                                label = { Text("API key") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = UsageColors.TextPrimary,
                                    unfocusedTextColor = UsageColors.TextPrimary,
                                    focusedBorderColor = UsageColors.Terracotta,
                                    unfocusedBorderColor = UsageColors.Outline,
                                    focusedLabelColor = UsageColors.Terracotta,
                                    unfocusedLabelColor = UsageColors.TextSecondary,
                                    cursorColor = UsageColors.Terracotta,
                                ),
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { if (acceptsInput()) onSubmitApiKey(key) },
                                    enabled = key.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = UsageColors.Terracotta,
                                        contentColor = UsageColors.Background,
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) { Text("Connect") }
                                Button(
                                    onClick = { if (acceptsInput()) uriHandler.openUri(state.consoleUrl) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = UsageColors.TerracottaSurface,
                                        contentColor = UsageColors.Terracotta,
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) { Text("Open console") }
                            }
                        }
                        TextButton(onClick = { if (acceptsInput()) onCancel() }) { Text("Cancel", color = UsageColors.TextSecondary) }
                    }

                    is AddAccountState.AwaitingBrowser -> {
                        val uriHandler = LocalUriHandler.current
                        LoadingCard(
                            "Complete the sign-in in your browser. You'll come straight back here.",
                        )
                        if (state.deviceCodeAlternative) {
                            // The browser flow needs the browser to reach this app on
                            // localhost, and not every phone lets it. The code needs nothing
                            // of the sort, so it is offered right where the wait would
                            // otherwise run out the clock.
                            Text(
                                text = "Browser not coming back? Sign in with a code instead — " +
                                    "you type it on the provider's page and no redirect is needed.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = UsageColors.TextSecondary,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.deviceCodeAlternative) {
                                TextButton(onClick = { if (acceptsInput()) onStartWithDeviceCode(state.provider) }) {
                                    Text("Use a device code", color = UsageColors.Terracotta)
                                }
                            }
                            state.authorizationUrl?.let { url ->
                                TextButton(onClick = { if (acceptsInput()) uriHandler.openUri(url) }) {
                                    Text("Open page again", color = UsageColors.Terracotta)
                                }
                            }
                            TextButton(onClick = { if (acceptsInput()) onCancel() }) { Text("Cancel", color = UsageColors.TextSecondary) }
                        }
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
                            onClick = { if (acceptsInput()) onDone() },
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
                                    // The same way in as the attempt that failed: someone who
                                    // chose the code because the browser never came back
                                    // must not be sent back to the browser by "try again".
                                    onClick = {
                                        if (acceptsInput()) {
                                            if (state.viaDeviceCode) onStartWithDeviceCode(provider) else onStart(provider)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = UsageColors.Terracotta,
                                        contentColor = UsageColors.Background,
                                    ),
                                ) { Text("Try again") }
                                if (state.keyAlternative) {
                                    TextButton(onClick = { if (acceptsInput()) onStartWithKey(provider) }) {
                                        Text("Use an API key", color = UsageColors.Terracotta)
                                    }
                                }
                                if (state.deviceCodeAlternative) {
                                    if (state.viaDeviceCode) {
                                        TextButton(onClick = { if (acceptsInput()) onStart(provider) }) {
                                            Text("Use the browser", color = UsageColors.Terracotta)
                                        }
                                    } else {
                                        TextButton(onClick = { if (acceptsInput()) onStartWithDeviceCode(provider) }) {
                                            Text("Use a device code", color = UsageColors.Terracotta)
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = { if (acceptsInput()) onCancel() }) {
                                Text("Back", color = UsageColors.TextSecondary)
                            }
                        }
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
        com.usagelimits.ui.ProviderBadge(provider = provider)
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
    ProviderId.CODEX -> "Sign in in your browser"
    ProviderId.XAI -> "Sign in with a device code"
    ProviderId.CLAUDE -> "Sign in in your browser"
    ProviderId.ANTIGRAVITY -> "Sign in with Google"
    ProviderId.KIMI -> "Sign in with a device code"
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
