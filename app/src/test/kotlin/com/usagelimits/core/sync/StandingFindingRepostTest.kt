package com.usagelimits.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A standing finding is re-derived on every sync. Re-POSTING it on every sync is the bug:
 * a notification the user dismissed came back, silently, every thirty minutes, and the only
 * way to be rid of it was the toggle that turns the feature off.
 */
class StandingFindingRepostTest {

    private val credit = listOf("Codex · 1 reset credit available")

    @Test
    fun `a new alert always posts`() {
        assertTrue(NotificationPublisher.shouldPost(listOf("Claude · Weekly exhausted"), emptyList(), null))
        assertTrue(NotificationPublisher.shouldPost(listOf("x"), credit, NotificationPublisher.fingerprint(credit)))
    }

    @Test
    fun `a standing finding posts the first time and not again unchanged`() {
        assertTrue("first sight", NotificationPublisher.shouldPost(emptyList(), credit, null))
        val posted = NotificationPublisher.fingerprint(credit)
        assertFalse("same fact, next sync — the user may have dismissed it",
            NotificationPublisher.shouldPost(emptyList(), credit, posted))
    }

    @Test
    fun `a changed standing set posts again`() {
        val posted = NotificationPublisher.fingerprint(credit)
        val two = listOf("Codex · 2 reset credits available")
        assertTrue(NotificationPublisher.shouldPost(emptyList(), two, posted))
    }

    @Test
    fun `order does not count as change`() {
        val a = listOf("A · 1 reset credit available", "B · 1 reset credit available")
        assertFalse(NotificationPublisher.shouldPost(emptyList(), a.reversed(), NotificationPublisher.fingerprint(a)))
    }

    @Test
    fun `a quiet refresh says nothing and touches nothing`() {
        assertFalse(NotificationPublisher.shouldPost(emptyList(), emptyList(), null))
        assertFalse(NotificationPublisher.shouldPost(emptyList(), emptyList(), NotificationPublisher.fingerprint(credit)))
    }
}
