package com.usagelimits.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local cache for account metadata, the latest usage snapshot per account, and widget config.
 *
 * By construction this database holds nothing sensitive: tokens live in the Keystore-backed
 * credential store instead, so a Room export or a debug inspection cannot leak credentials.
 */
@Database(
    entities = [
        AccountEntity::class,
        UsageSnapshotEntity::class,
        NotificationEventEntity::class,
        NotificationStateEntity::class,
        WidgetConfigEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class UsageLimitsDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun usageSnapshotDao(): UsageSnapshotDao
    abstract fun widgetConfigDao(): WidgetConfigDao

    abstract fun notificationDao(): NotificationDao

    companion object {
        private const val DATABASE_NAME = "usage_limits.db"

        /**
         * Adds the authoritative reset-credit count.
         *
         * Additive and nullable, so existing rows stay valid and simply report no count until
         * their next sync. A destructive fallback would also have been safe here — the cache
         * is re-derived from the providers — but silently dropping a user's account list on an
         * upgrade is a bad habit to start.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_snapshots ADD COLUMN resetCreditCount INTEGER")
            }
        }

        /**
         * Adds the count of reset credits that apply to the limit currently reached.
         *
         * Additive and nullable, matching MIGRATION_1_2: a row written before the upgrade
         * reports no distinction and falls back to the held count, which is the pre-upgrade
         * behaviour exactly.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE usage_snapshots ADD COLUMN applicableResetCreditCount INTEGER",
                )
            }
        }

        /**
         * Adds the notification event ledger and per-account notification state.
         *
         * Both start empty, which means the first sync after the upgrade treats every account
         * as unseen. That is the right direction to fail: an account already sitting below a
         * threshold produces one alert, rather than the alternative of back-filling state and
         * silently swallowing a limit the user is actually up against.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notification_events (
                        eventKey TEXT NOT NULL PRIMARY KEY,
                        accountId TEXT NOT NULL,
                        consumedAt INTEGER NOT NULL,
                        FOREIGN KEY(accountId) REFERENCES accounts(localId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_notification_events_accountId " +
                        "ON notification_events (accountId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notification_state (
                        accountId TEXT NOT NULL PRIMARY KEY,
                        lowQuotaEpisode INTEGER NOT NULL,
                        lowQuotaActive INTEGER NOT NULL,
                        lastProcessedFetchedAt INTEGER,
                        FOREIGN KEY(accountId) REFERENCES accounts(localId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Per-window notification episodes. Additive and defaulted, so a row written by
         * version 4 reads back with an empty map: every window then starts its own episode at
         * the next fresh snapshot, which means an account that is mid-dip on upgrade is told
         * once more — the same choice 3→4 made, and the right side of the trade against
         * silently suppressing a limit that is still exhausted.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE notification_state ADD COLUMN windowsJson TEXT NOT NULL DEFAULT '{}'",
                )
            }
        }

        /**
         * A placed widget may drop its own background and show the wallpaper instead.
         *
         * Defaults to 0 — opaque — so every widget already on a home screen keeps the look it
         * was placed with rather than turning transparent under its owner.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE widget_configs ADD COLUMN transparent INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_snapshots ADD COLUMN connectionStatus TEXT")
                db.execSQL("ALTER TABLE widget_configs ADD COLUMN customAccountIdsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE widget_configs ADD COLUMN layoutMetricsJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        fun build(context: Context): UsageLimitsDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                UsageLimitsDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            ).build()
    }
}

/**
 * The real transaction scope, backed by Room.
 *
 * `withTransaction` serialises against every other write on this database, which is what makes
 * the repository's read-then-write pairs atomic: a delete issued while a sync is mid-pair runs
 * as its own transaction and cannot interleave inside this one.
 */
class RoomTransactionRunner(private val database: UsageLimitsDatabase) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}
