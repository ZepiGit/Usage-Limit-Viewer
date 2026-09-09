package com.usagelimits.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Account metadata.
 *
 * Contains no token material of any kind — only [credentialReference], which names an entry
 * in the encrypted credential store. That separation is what lets widgets and the UI read
 * this table freely.
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["provider", "externalAccountId"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey val localId: String,
    val provider: String,
    val externalAccountId: String,
    val email: String?,
    val displayName: String?,
    val plan: String?,
    val credentialReference: String,
    val createdAt: Long,
    val lastSuccessfulSync: Long?,
    /** Non-secret provider extras (e.g. Antigravity project id), serialised as JSON. */
    val attributesJson: String = "{}",
    /** Sort position in the account list, user-controllable later. */
    val sortOrder: Int = 0,
)

/**
 * The most recent usage fetch per account.
 *
 * One row per account rather than a history table: the app answers "how much is left right
 * now", and keeping a single row means widgets read a bounded, predictable amount of data.
 */
@Entity(
    tableName = "usage_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["localId"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class UsageSnapshotEntity(
    @PrimaryKey val accountId: String,
    val fetchedAt: Long,
    val status: String,
    val errorMessage: String?,
    /** Normalised [com.usagelimits.core.model.UsageWindow] list as JSON. */
    val windowsJson: String,
    /** Normalised [com.usagelimits.core.model.ResetCredit] list as JSON. */
    val resetCreditsJson: String,
    /**
     * The count the provider reports, which is authoritative over the row count.
     *
     * The credit list can be truncated or filtered while the count stays exact, so gating the
     * redeem button on the list size would hide it from a user who actually holds credits.
     */
    val resetCreditCount: Int? = null,
    /**
     * How many of those credits apply to the limit currently reached.
     *
     * Separate from [resetCreditCount] because the provider reports both and a zero here does
     * not mean the user holds nothing — see CodexUsageParser.applicableCreditCount.
     */
    val applicableResetCreditCount: Int? = null,
)

/**
 * Per-widget configuration.
 *
 * Keyed by the framework's appWidgetId so each placed widget can target a different account
 * or scope. Deliberately holds only ids and enum names.
 */
@Entity(tableName = "widget_configs")
data class WidgetConfigEntity(
    @PrimaryKey val appWidgetId: Int,
    /** One of [com.usagelimits.core.model.WidgetScope]. */
    val scope: String,
    val accountId: String?,
    val provider: String?,
    val updatedAt: Long,
)
