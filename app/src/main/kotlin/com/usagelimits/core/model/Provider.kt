package com.usagelimits.core.model

/**
 * The providers this app can monitor.
 *
 * [id] is persisted in Room and in widget configuration, so the string values are part of
 * the on-disk contract and must not be renamed without a migration.
 */
enum class ProviderId(val id: String, val displayName: String) {
    CODEX("codex", "OpenAI Codex"),
    CLAUDE("claude", "Claude"),
    ANTIGRAVITY("antigravity", "Antigravity"),
    XAI("xai", "Grok");

    companion object {
        fun fromId(value: String?): ProviderId? = entries.firstOrNull { it.id == value }
    }
}

/**
 * A logged-in account, normalised across providers.
 *
 * Identity is [ProviderId] + [externalAccountId], never the e-mail alone: providers let the
 * same address back several accounts, and an address can change while the account stays the
 * same. [localId] is the stable primary key the rest of the app refers to.
 *
 * This type deliberately carries no tokens. Credentials live only in the encrypted
 * credential store, addressed by [credentialReference].
 */
data class ProviderAccount(
    val localId: String,
    val provider: ProviderId,
    val externalAccountId: String,
    val email: String?,
    val displayName: String?,
    val plan: String?,
    val credentialReference: String,
    val createdAt: Long,
    val lastSuccessfulSync: Long?,
    /** Provider-specific non-secret data, e.g. the Antigravity GCP project id. */
    val attributes: Map<String, String> = emptyMap(),
) {
    /** `m***@gmail.com` — what the UI shows instead of the full address. */
    val maskedEmail: String?
        get() = email?.let(::maskEmail)

    /** Falls back through the identifiers a provider may or may not supply. */
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: maskedEmail
            ?: externalAccountId.take(12)
}

internal fun maskEmail(email: String): String {
    val at = email.indexOf('@')
    if (at <= 0) return email
    val local = email.substring(0, at)
    val domain = email.substring(at)
    // `local.first()` IS `local` when the local part is one character, and `at <= 0` has
    // already returned, so the two branches this used to have produced identical output.
    return "${local.first()}***$domain"
}
