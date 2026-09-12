package com.usagelimits.core.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every database a shipped build could have left behind upgrades to the current one with its
 * rows intact.
 *
 * An update that loses the user's accounts is worse than one that does not install: the
 * credentials, the cached numbers and the widget configuration all hang off these rows, and
 * Room's answer to a version it has no migration for is to throw at launch. So this builds
 * each exported schema — the JSON Room wrote for that version — puts an account, its snapshot
 * and a widget into it, opens it with the current app, and checks all three are still there
 * and readable. A version that ships without its migration fails here before it can strand
 * anybody.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
class DatabaseUpgradeTest {

    private val schemaDir = File("schemas/com.usagelimits.core.database.UsageLimitsDatabase")

    private fun currentVersion(): Int =
        schemaDir.listFiles()!!.map { it.nameWithoutExtension.toInt() }.max()

    /** Creates the tables exactly as version [version] shipped them. */
    private fun createSchema(db: SQLiteDatabase, version: Int): JSONObject {
        val schema = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val table = entities.getJSONObject(i)
            val name = table.getString("tableName")
            db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = table.getJSONArray("indices")
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        return schema
    }

    @Test
    fun `the schema on disk is the current version`() {
        // The exported JSON is what the other test builds from; a bumped @Database version
        // without a matching export would make it test the wrong thing.
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase("usage_limits.db")
        val db = UsageLimitsDatabase.build(context)
        try {
            assertEquals(currentVersion(), db.openHelper.readableDatabase.version)
        } finally { db.close(); context.deleteDatabase("usage_limits.db") }
    }

    @Test
    fun `every shipped version upgrades to the current one keeping its rows`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val current = currentVersion()
        for (version in 1 until current) {
            context.deleteDatabase("usage_limits.db")
            val file = context.getDatabasePath("usage_limits.db")
            file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                createSchema(db, version)
                db.execSQL(
                    "INSERT INTO accounts(localId, provider, externalAccountId, email, credentialReference, createdAt, attributesJson, sortOrder) " +
                        "VALUES('kept-$version', 'codex', 'ext-$version', 'kept@example.com', 'ref-$version', 1, '{}', 0)",
                )
                db.execSQL(
                    "INSERT INTO usage_snapshots(accountId, fetchedAt, status, windowsJson, resetCreditsJson) " +
                        "VALUES('kept-$version', 2, 'OK', '[]', '[]')",
                )
                db.execSQL(
                    "INSERT INTO widget_configs(appWidgetId, scope, accountId, provider, updatedAt) " +
                        "VALUES(7, 'ACCOUNT', 'kept-$version', NULL, 3)",
                )
            }

            val upgraded = UsageLimitsDatabase.build(context)
            try {
                val account = upgraded.accountDao().getAll().single()
                assertEquals("version $version lost its account", "kept-$version", account.localId)
                assertEquals("ref-$version", account.credentialReference)
                assertEquals("kept@example.com", account.email)

                val snapshot = upgraded.usageSnapshotDao().getForAccount("kept-$version")
                assertTrue("version $version lost its snapshot", snapshot != null)
                assertEquals(2L, snapshot!!.fetchedAt)

                val widget = upgraded.widgetConfigDao().get(7)
                assertTrue("version $version lost its widget", widget != null)
                assertEquals("kept-$version", widget!!.accountId)
                assertEquals(100, widget.backgroundOpacity)
                assertEquals(current, upgraded.openHelper.readableDatabase.version)
            } finally {
                upgraded.close()
                context.deleteDatabase("usage_limits.db")
            }
        }
    }
}
