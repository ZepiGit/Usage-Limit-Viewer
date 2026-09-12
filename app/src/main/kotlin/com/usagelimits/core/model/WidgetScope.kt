package com.usagelimits.core.model

/** What a placed widget shows. Persisted by name, so entries must not be renamed casually. */
enum class WidgetScope {
    /** Automatically surfaces whatever is closest to running out across all accounts. */
    MOST_CRITICAL,

    /** One specific account. */
    ACCOUNT,

    /** All accounts of one provider, aggregated. */
    PROVIDER,

    /** Everything, aggregated. */
    ALL_ACCOUNTS,
    CLOSEST_RESETS,
    CUSTOM;

    companion object {
        fun fromName(value: String?): WidgetScope =
            if (value == MOST_CRITICAL.name) CLOSEST_RESETS
            else entries.firstOrNull { it.name == value } ?: CLOSEST_RESETS
    }
}
