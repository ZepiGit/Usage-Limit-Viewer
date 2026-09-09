package com.usagelimits.core.di

import android.content.Context
import com.usagelimits.core.auth.CredentialStore
import com.usagelimits.core.auth.KeystoreCredentialStore
import com.usagelimits.core.database.UsageLimitsDatabase
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.settings.SettingsStore
import com.usagelimits.core.sync.SyncEngine
import com.usagelimits.providers.ProviderRegistry

/**
 * Manual dependency graph.
 *
 * A DI framework (Hilt) would be the conventional choice, but this graph is small, entirely
 * singleton-scoped, and has no build-variant branching — so annotation processing would add
 * build time and a failure mode without buying anything. Everything is constructed lazily and
 * wired explicitly, which also makes the dependencies obvious in one screenful and keeps tests
 * free to build their own graph.
 *
 * Revisit this if the graph grows scoped or variant-specific bindings.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val httpClient: HttpClient by lazy { HttpClient() }

    val credentialStore: CredentialStore by lazy { KeystoreCredentialStore(appContext) }

    private val database: UsageLimitsDatabase by lazy { UsageLimitsDatabase.build(appContext) }

    val repository: UsageRepository by lazy {
        UsageRepository(database.accountDao(), database.usageSnapshotDao())
    }

    val widgetConfigDao by lazy { database.widgetConfigDao() }

    val providerRegistry: ProviderRegistry by lazy { ProviderRegistry(httpClient) }

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(repository, credentialStore, providerRegistry)
    }
}
