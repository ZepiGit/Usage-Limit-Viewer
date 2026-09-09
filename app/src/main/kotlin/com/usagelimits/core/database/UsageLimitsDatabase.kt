package com.usagelimits.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
    exportSchema = true,
)
abstract class UsageLimitsDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun usageSnapshotDao(): UsageSnapshotDao
    abstract fun widgetConfigDao(): WidgetConfigDao

    companion object {
        private const val DATABASE_NAME = "usage_limits.db"

        fun build(context: Context): UsageLimitsDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                UsageLimitsDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
