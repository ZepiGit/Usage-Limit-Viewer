package com.usagelimits.core.model

fun ProviderAccount.title(showTier: Boolean = true): String {
    val tier = plan?.trim()?.takeIf { it.isNotBlank() && !it.equals(provider.displayName, true) }
    return when {
        !showTier || tier == null -> provider.displayName
        tier.startsWith(provider.displayName + " ", true) -> tier
        else -> "${provider.displayName} $tier"
    }
}
