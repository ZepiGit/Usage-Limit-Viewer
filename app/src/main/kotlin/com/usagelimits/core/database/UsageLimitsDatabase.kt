package com.usagelimits.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
        WidgetConfigEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class UsageLimitsDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun usageSnapshotDao(): UsageSnapshotDao
    abstract fun widgetConfigDao(): WidgetConfigDao

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

        fun build(context: Context): UsageLimitsDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                UsageLimitsDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
