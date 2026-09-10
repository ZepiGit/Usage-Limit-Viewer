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

/**
 * A provider's raw plan string, as a subscriber would recognise it.
 *
 * Providers disagree about case. Anthropic hands back `default_claude_max_5x`; OpenAI hands
 * back a bare `plus`. Until this existed the Codex path passed its value straight through, so
 * an account read "OpenAI Codex plus" while the Claude beside it read "Claude Max 5×".
 *
 * Read structurally rather than from a table of known tiers, for the reason Claude's tier
 * parsing already gives: vendors add tiers, and a table renders a new one as no plan at all.
 * An unrecognised tier still yields something readable.
 *
 * `Char.uppercase()` is the full Unicode mapping rather than the single-character one — 'ß'
 * has no single-char uppercase, so `replaceFirstChar` leaves it alone where the Swift twin
 * produces "SS". Matching Swift is what keeps the two apps showing the same string.
 */
fun planLabel(raw: String?): String? {
    val parts = raw?.trim()?.lowercase()
        // '_' and ' ' only, which is what the Kotlin this replaced split on and what the
        // Swift twin still splits on. Adding '-' would have been a guess about tier ids nobody
        // issues, and a guess that made the two apps disagree.
        ?.split('_', ' ')
        ?.filter { it.isNotBlank() }
        ?: return null
    if (parts.isEmpty()) return null

    // A trailing `5x` is a multiplier on the tier, not a word in its name — but only when
    // there is a name for it to multiply. `claude_20x` strips to a bare `20x`, which is the
    // tier's whole name, and reading it as a multiplier of nothing produced a null plan where
    // the Swift twin produces "20x". `ClaudeLabelParityTest` pins that case.
    val multiplier = parts.last()
        .takeIf { parts.size > 1 && Regex("^\\d+x$").matches(it) }
        ?.dropLast(1)
    val words = if (multiplier == null) parts else parts.dropLast(1)

    val name = words.joinToString(" ") { part -> part.first().uppercase() + part.drop(1) }
    return if (multiplier == null) name else "$name $multiplier×"
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
