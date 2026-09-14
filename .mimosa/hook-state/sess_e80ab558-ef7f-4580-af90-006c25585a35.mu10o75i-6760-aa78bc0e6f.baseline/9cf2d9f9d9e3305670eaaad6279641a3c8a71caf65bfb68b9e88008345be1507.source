package com.usagelimits.core.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class WidgetUpgradeTest {
    @Test fun `upgrading a shipped database preserves the account selection and transparency`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase("usage_limits.db")
        val file = context.getDatabasePath("usage_limits.db")
        file.parentFile!!.mkdirs()
        val schema = JSONObject(File("schemas/com.usagelimits.core.database.UsageLimitsDatabase/6.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val table = entities.getJSONObject(i)
                val name = table.getString("tableName")
                db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = table.getJSONArray("indices")
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO accounts(localId, provider, externalAccountId, credentialReference, createdAt, attributesJson, sortOrder) VALUES('kept', 'codex', 'external', 'test-only', 1, '{}', 0)")
            db.execSQL("INSERT INTO widget_configs(appWidgetId, scope, accountId, provider, updatedAt, transparent) VALUES(17, 'ACCOUNT', 'kept', NULL, 1, 1)")
            db.version = 6
        }
        val upgraded = UsageLimitsDatabase.build(context)
        try {
            assertEquals("kept", upgraded.accountDao().getAll().single().localId)
            val config = upgraded.widgetConfigDao().get(17)!!
            assertEquals("ACCOUNT", config.scope)
            assertEquals("kept", config.accountId)
            assertTrue(config.transparent)
            assertEquals("[]", config.customAccountIdsJson)
            assertEquals("{}", config.layoutMetricsJson)
            // Version 9: the old flag becomes 0 % opacity of the default panel, with auto ink.
            assertEquals(com.usagelimits.widget.WidgetBackground.DEFAULT_ARGB, config.backgroundArgb)
            assertEquals(0, config.backgroundOpacity)
            assertEquals("AUTO", config.textTone)
            assertTrue(com.usagelimits.widget.WidgetStyle.fromStored(config.backgroundArgb, config.backgroundOpacity,
                config.transparent, config.textTone).background.isTransparent)
        } finally { upgraded.close(); context.deleteDatabase("usage_limits.db") }
    }

    @Test fun `an opaque widget on version 8 keeps its full panel after the upgrade`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase("usage_limits.db")
        val file = context.getDatabasePath("usage_limits.db")
        file.parentFile!!.mkdirs()
        val schema = JSONObject(File("schemas/com.usagelimits.core.database.UsageLimitsDatabase/8.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val table = entities.getJSONObject(i)
                val name = table.getString("tableName")
                db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = table.getJSONArray("indices")
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO widget_configs(appWidgetId, scope, accountId, provider, updatedAt, transparent) VALUES(21, 'CLOSEST_RESETS', NULL, NULL, 1, 0)")
            db.version = 8
        }
        val upgraded = UsageLimitsDatabase.build(context)
        try {
            val config = upgraded.widgetConfigDao().get(21)!!
            assertEquals(100, config.backgroundOpacity)
            assertEquals(com.usagelimits.widget.WidgetBackground.DEFAULT_ARGB, config.backgroundArgb)
            val style = com.usagelimits.widget.WidgetStyle.fromStored(config.backgroundArgb, config.backgroundOpacity, config.transparent, config.textTone)
            assertEquals(com.usagelimits.widget.WidgetStyle(), style)
        } finally { upgraded.close(); context.deleteDatabase("usage_limits.db") }
    }
}
