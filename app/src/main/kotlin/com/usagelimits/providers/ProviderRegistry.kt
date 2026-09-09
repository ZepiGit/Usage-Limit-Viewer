package com.usagelimits.providers

import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.HttpClient
import com.usagelimits.providers.antigravity.AntigravityProvider
import com.usagelimits.providers.claude.ClaudeProvider
import com.usagelimits.providers.codex.CodexProvider
import com.usagelimits.providers.xai.XaiProvider

/**
 * Resolves a [ProviderId] to its implementation.
 *
 * The rest of the app only ever asks for a provider by id, so adding a provider means adding
 * one entry here and nothing else — no screen, widget, or sync code changes.
 */
class ProviderRegistry(http: HttpClient) {

    private val providers: Map<ProviderId, UsageProvider> = buildMap {
        put(ProviderId.CODEX, CodexProvider(http))
        put(ProviderId.CLAUDE, ClaudeProvider(http))
        put(ProviderId.ANTIGRAVITY, AntigravityProvider(http))
        put(ProviderId.XAI, XaiProvider(http))
    }

    fun forId(id: ProviderId): UsageProvider? = providers[id]

    /** Providers that can currently be added, in the order the picker shows them. */
    fun available(): List<UsageProvider> =
        ProviderId.entries.mapNotNull { providers[it] }.filter { it.isLoginAvailable }

    fun all(): List<UsageProvider> = ProviderId.entries.mapNotNull { providers[it] }
}
