package com.usagelimits.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every widget a user can place has to be on BOTH update paths.
 *
 * The app-driven path is [WidgetUpdater.allWidgets]: the repaint after a sync moves the cache.
 * It is the one step in adding a widget that fails silently, and it duly did: the ring widget
 * shipped with a receiver in the manifest, an XML descriptor, and no entry in that list, so it
 * rendered once when it was placed and then never again.
 *
 * The system-driven path is `updatePeriodMillis` on the provider XML: the launcher asks the
 * receiver for a render on the system's own clock, whether or not the app's own sync has run.
 * Every widget depended on that sync alone, and on a phone that deferred it the home screen
 * showed the numbers from the day the widget was placed — including after the limits had
 * reset — with nothing on the tile to say so. A quota display that is silently stale is the
 * exact failure this app exists to prevent, so both paths are checked from the manifest, which
 * is the authority on what can be placed.
 */
class WidgetRefreshCoverageTest {

    private val manifest = File("src/main/AndroidManifest.xml")

    /** `.widget.CompactUsageWidgetReceiver` -> `CompactUsageWidget`. */
    private fun placeableWidgets(): List<String> {
        assertTrue("the manifest must be readable from the module directory", manifest.isFile)
        return Regex("""android:name="\.widget\.(\w+)Receiver"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toList()
    }

    /** Receiver name -> the provider XML it declares. */
    private fun providerXmls(): Map<String, File> {
        val text = manifest.readText()
        return Regex(
            """<receiver[^>]*android:name="\.widget\.(\w+)Receiver"[\s\S]*?android:resource="@xml/(\w+)"""",
        ).findAll(text).associate { it.groupValues[1] to File("src/main/res/xml/${it.groupValues[2]}.xml") }
    }

    @Test
    fun `every widget receiver in the manifest is refreshed after a sync`() {
        val placeable = placeableWidgets()
        val refreshed = WidgetUpdater.allWidgets.map { it.name }

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
        val stale = WidgetUpdater.allWidgets.map { it.name } - placeableWidgets().toSet()

        assertEquals(
            "these are refreshed but have no receiver in the manifest: $stale",
            emptyList<String>(),
            stale,
        )
    }

    @Test
    fun `the refresh list names each widget's own receiver`() {
        // The fallback broadcast in refreshAll goes to this receiver; pointing a widget at
        // another's receiver would repair and render the wrong one.
        for (placed in WidgetUpdater.allWidgets) {
            assertEquals(
                "${placed.name} should be paired with its own receiver",
                "${placed.name}Receiver",
                placed.receiver.simpleName,
            )
        }
    }

    @Test
    fun `every placeable widget asks the system for a periodic update`() {
        val xmls = providerXmls()
        assertEquals("every receiver should declare a provider XML", placeableWidgets().toSet(), xmls.keys)

        for ((widget, xml) in xmls) {
            assertTrue("$xml should exist", xml.isFile)
            val period = Regex("""android:updatePeriodMillis="(\d+)"""").find(xml.readText())
                ?.groupValues?.get(1)?.toLong()
            assertTrue(
                "$widget declares no updatePeriodMillis, so it only ever updates when the app's own sync runs",
                period != null,
            )
            // Thirty minutes is the floor the framework honours; anything below is silently
            // raised to it, and reads as a request the system will not grant.
            assertTrue("$widget asks for a period the system will not honour: $period", period!! >= 1_800_000L)
        }
    }

    @Test
    fun `every placeable widget uses the receiver that turns the system tick into a sync`() {
        // A plain GlanceAppWidgetReceiver repaints the cache on the system's tick and stops
        // there — the same old number, thirty minutes later. UsageWidgetReceiver also asks
        // for a fetch when the cache has aged.
        for (placed in WidgetUpdater.allWidgets) {
            assertTrue(
                "${placed.receiver.simpleName} should extend UsageWidgetReceiver",
                UsageWidgetReceiver::class.java.isAssignableFrom(placed.receiver),
            )
        }
    }
}
