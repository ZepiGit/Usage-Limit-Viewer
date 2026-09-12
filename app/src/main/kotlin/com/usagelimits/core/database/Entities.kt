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
    val connectionStatus: String? = null,
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
    /**
     * Draw the widget without its own background, letting the wallpaper through.
     *
     * Per placed widget rather than per app: one on a busy wallpaper wants the panel, the one
     * tucked beside the clock does not.
     *
     * The default is declared HERE as well as in the migration. SQLite needs one to add a NOT
     * NULL column to a table with rows in it, and Room compares the resulting schema against
     * the one it expects — a column the migration defaults and the entity does not is a
     * mismatch that only surfaces on a real upgrade, on a real device, with existing widgets.
     * `windowsJson` in `notification_state` already does this; this field did not, until the
     * exported schema was read back and compared with the ALTER statement.
     */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val transparent: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "'[]'")
    val customAccountIdsJson: String = "[]",
    @androidx.room.ColumnInfo(defaultValue = "'{}'")
    val layoutMetricsJson: String = "{}",
)

/**
 * One alert this app has already delivered.
 *
 * The row is the record that a given edge has been consumed, so it exists solely to stop the
 * same alert firing on the next sync. Insert-or-ignore against the primary key is what makes
 * claiming atomic: the insert either wins, and the caller may post, or loses, and it must not.
 *
 * Keys are opaque and internal — they may name an account row or a credit id, and never appear
 * in notification text.
 */
@Entity(
    tableName = "notification_events",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["localId"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["accountId"])],
)
data class NotificationEventEntity(
    @PrimaryKey val eventKey: String,
    val accountId: String,
    val consumedAt: Long,
)

/**
 * Per-account carried-over notification state.
 *
 * Separate from the snapshot because it must outlive any single fetch: whether the account is
 * mid-episode, and which snapshot has already been processed, are the two facts that turn a
 * series of point-in-time readings into edges.
 */
@Entity(
    tableName = "notification_state",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["localId"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NotificationStateEntity(
    @PrimaryKey val accountId: String,
    val lowQuotaEpisode: Int,
    val lowQuotaActive: Boolean,
    val lastProcessedFetchedAt: Long?,
    /**
     * Per-window episodes as a JSON object keyed by window identity, added in version 5.
     *
     * A column rather than a table, matching how `usage_snapshots` stores its windows: the
     * map is read and written whole, nothing queries inside it, and a window that a provider
     * stops reporting simply ages out of the map rather than leaving an orphaned row.
     */
    @androidx.room.ColumnInfo(defaultValue = "{}")
    val windowsJson: String = "{}",
)
