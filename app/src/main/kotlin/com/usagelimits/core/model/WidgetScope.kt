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
    ALL_ACCOUNTS;

    companion object {
        fun fromName(value: String?): WidgetScope =
            entries.firstOrNull { it.name == value } ?: MOST_CRITICAL
    }
}
