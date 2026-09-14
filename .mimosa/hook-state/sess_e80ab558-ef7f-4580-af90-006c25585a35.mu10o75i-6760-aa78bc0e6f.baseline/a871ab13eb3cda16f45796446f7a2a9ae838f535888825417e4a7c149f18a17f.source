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
        /**
         * Decodes the persisted name exactly. [MOST_CRITICAL] used to be aliased to
         * [CLOSEST_RESETS] here — a compatibility shim that silently changed what a saved
         * widget watched: an account chosen for being closest to running out is not the one
         * chosen for needing attention most, and the two orderings legitimately diverge the
         * moment a future reset boundary moves. Leadership then changed with no configuration
         * write anywhere, which is the symptom that reads as "the widget swapped its content".
         * A stored name keeps its stored meaning; null and unknown names — a widget placed but
         * never configured — still fall back to the current default below.
         */
        fun fromName(value: String?): WidgetScope =
            entries.firstOrNull { it.name == value } ?: CLOSEST_RESETS
    }
}
