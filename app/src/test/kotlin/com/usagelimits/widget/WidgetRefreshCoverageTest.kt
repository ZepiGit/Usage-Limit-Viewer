package com.usagelimits.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every widget a user can place has to be in the refresh path.
 *
 * This is the one step in adding a widget that fails silently, and it duly did: the ring widget
 * shipped with a receiver in the manifest, an XML descriptor, and no entry in
 * [WidgetUpdater.allWidgets]. None of the three declares `updatePeriodMillis` and only the
 * detailed one has a refresh button, so the omitted widget rendered once when it was placed and
 * then never again — showing whatever was true at that moment, for as long as it stayed on the
 * home screen, with nothing on the tile to say it was old. A quota display that is silently
 * stale is the exact failure this app exists to prevent.
 *
 * The manifest is the authority on what can be placed, so the test reads it rather than a list
 * someone would have to remember to update. A fourth widget added the same way fails here
 * before it can ship.
 */
class WidgetRefreshCoverageTest {

    /** `.widget.CompactUsageWidgetReceiver` -> `CompactUsageWidget`. */
    private fun placeableWidgets(): List<String> {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("the manifest must be readable from the module directory", manifest.isFile)
        return Regex("""android:name="\.widget\.(\w+)Receiver"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `every widget receiver in the manifest is refreshed after a sync`() {
        val placeable = placeableWidgets()
        val refreshed = WidgetUpdater.allWidgets.map { it.first }

        assertTrue("the manifest should declare at least one widget", placeable.isNotEmpty())
        val missing = placeable - refreshed.toSet()
        assertEquals(
            "these widgets can be placed but are never refreshed: $missing",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `the refresh list names no widget that cannot be placed`() {
        // The other direction, which costs nothing to check and catches a receiver deleted from
        // the manifest while its entry here stayed — a refresh that throws on every sync.
        val stale = WidgetUpdater.allWidgets.map { it.first } - placeableWidgets().toSet()

        assertEquals(
            "these are refreshed but have no receiver in the manifest: $stale",
            emptyList<String>(),
            stale,
        )
    }
}
