package com.usagelimits.feature.accounts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.ProviderException
import com.usagelimits.core.sync.userMessage
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.codex.CodexProvider
import com.usagelimits.providers.xai.XaiProvider
import com.usagelimits.widget.WidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the add-account flow currently is. */
sealed interface AddAccountState {
    data object PickProvider : AddAccountState

    data class Starting(val provider: ProviderId) : AddAccountState

    /** Device flow: the user types [userCode] at [verificationUri] while the app polls. */
    data class AwaitingDeviceCode(
        val provider: ProviderId,
        val userCode: String,
        val verificationUri: String,
    ) : AddAccountState

    /**
     * No flow to drive: the user has to fetch a key from the provider's console and paste it.
     *
     * The login job parks here rather than unwinding, so cancelling the screen still closes
     * everything the attempt opened.
     */
    data class AwaitingApiKey(
        val provider: ProviderId,
        val consoleUrl: String,
        val hint: String,
    ) : AddAccountState

    /** Redirect flow: the browser is open and the loopback listener is waiting. */
    data class AwaitingBrowser(val provider: ProviderId) : AddAccountState

    data class Success(val provider: ProviderId, val accountLabel: String) : AddAccountState

    data class Failed(val provider: ProviderId?, val message: String) : AddAccountState
}

/**
 * Drives one login attempt.
 *
 * Deliberately separate from [com.usagelimits.feature.UsageViewModel]: a login is a bounded,
 * cancellable transaction with its own failure states, and folding it into the shared state
 * would leak half-finished attempts into every screen.
 */
class AddAccountViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<AddAccountState>(AddAccountState.PickProvider)
    val state: StateFlow<AddAccountState> = _state.asStateFlow()

    private var loginJob: Job? = null

    fun availableProviders(): List<ProviderId> =
        container.providerRegistry.available().map { it.providerId }

    /**
     * Runs a full login: challenge, user step, completion, profile, first sync.
     *
     * The whole thing is one job so cancelling closes the loopback listener and stops polling
     * rather than leaving either running in the background.
     */
    /** Set while a login is parked waiting for a pasted key. */
    private var pendingApiKey: CompletableDeferred<String>? = null

    /** Hands the key the user pasted to the login job that is waiting for it. */
    fun submitApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isNotEmpty()) pendingApiKey?.complete(trimmed)
    }

    fun startLogin(context: Context, providerId: ProviderId) {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            _state.value = AddAccountState.Starting(providerId)
            try {
                val provider = container.providerRegistry.forId(providerId)
                    ?: throw ProviderException.Unexpected("Provider unavailable")

                val challenge = provider.beginLogin()

                when (challenge) {
                    is LoginChallenge.DeviceCode -> {
                        // The packed challenge carries the device code and token endpoint
                        // alongside the user code; only the user code may ever be shown.
                        val display = when (providerId) {
                            ProviderId.CODEX -> CodexProvider.displayCode(challenge.userCode)
                            ProviderId.XAI -> XaiProvider.displayCode(challenge.userCode)
                            else -> challenge.userCode.substringBefore('|')
                        }
                        _state.value = AddAccountState.AwaitingDeviceCode(
                            provider = providerId,
                            userCode = display,
                            verificationUri = challenge.verificationUriComplete
                                ?: challenge.verificationUri,
                        )
                        openUrl(context, challenge.verificationUriComplete ?: challenge.verificationUri)
                    }

                    is LoginChallenge.Redirect -> {
                        _state.value = AddAccountState.AwaitingBrowser(providerId)
                        openUrl(context, challenge.authorizationUrl)
                    }

                    is LoginChallenge.ApiKey -> {
                        _state.value = AddAccountState.AwaitingApiKey(
                            provider = providerId,
                            consoleUrl = challenge.consoleUrl,
                            hint = challenge.hint,
                        )
                    }
                }

                // Parks the job until the key arrives, rather than returning and resuming
                // later: cancelling the screen then cancels the await along with everything
                // else the attempt opened.
                val typed = if (challenge is LoginChallenge.ApiKey) {
                    CompletableDeferred<String>().also { pendingApiKey = it }.await()
                } else {
                    null
                }

                val credentials = provider.completeLogin(challenge, typed)
                val profile = provider.fetchProfile(credentials)

                val account = container.repository.upsertFromLogin(
                    provider = providerId,
                    externalAccountId = profile.externalAccountId,
                    email = profile.email,
                    displayName = profile.displayName,
                    plan = profile.plan,
                    attributes = profile.attributes,
                )

                // Store credentials only once the account row exists, so a crash cannot leave
                // a credential nothing references.
                container.credentialStore.save(account.credentialReference, credentials)

                container.syncEngine.syncAccount(account)
                // A newly added account should appear on the home screen immediately, not at
                // the next background pass.
                WidgetUpdater.refreshAll(context.applicationContext)

                _state.value = AddAccountState.Success(providerId, account.label)
            } catch (e: ProviderException) {
                _state.value = AddAccountState.Failed(providerId, e.userMessage())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = AddAccountState.Failed(providerId, "Sign-in failed")
            }
        }
    }

    fun cancel() {
        loginJob?.cancel()
        loginJob = null
        _state.value = AddAccountState.PickProvider
    }

    fun reset() {
        _state.value = AddAccountState.PickProvider
    }

    /**
     * Opens the system browser via Custom Tabs.
     *
     * Never a WebView: the user must be able to see the real address bar and use their saved
     * credentials, and an embedded WebView would put this app in a position to observe the
     * password. Falls back to a plain intent when no Custom Tabs provider exists.
     */
    private fun openUrl(context: Context, url: String) {
        runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, Uri.parse(url))
        }.recoverCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun onCleared() {
        loginJob?.cancel()
        super.onCleared()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AddAccountViewModel(container) as T
    }
}
