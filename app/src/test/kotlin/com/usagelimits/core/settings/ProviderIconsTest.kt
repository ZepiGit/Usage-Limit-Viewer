package com.usagelimits.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.usagelimits.core.model.ProviderId
import com.usagelimits.ui.ProviderIconCatalog
import org.junit.Assert.*
import org.junit.Test

class ProviderIconsTest {
    @Test fun `two logo families share one provider and have the requested defaults`() {
        assertEquals(5, ProviderId.entries.size)
        assertEquals("claudecode-color", ProviderIconCatalog.selected(ProviderId.CLAUDE, null).id)
        assertEquals("gemini-color", ProviderIconCatalog.selected(ProviderId.ANTIGRAVITY, null).id)
        assertEquals(6, ProviderIconCatalog.choices(ProviderId.CLAUDE).size)
        assertEquals(6, ProviderIconCatalog.choices(ProviderId.ANTIGRAVITY).size)
        assertEquals(19, ProviderId.entries.sumOf { ProviderIconCatalog.choices(it).size })
    }

    @Test fun `stored selections remain independent and unknown icons fall back safely`() {
        val settings = mutablePreferencesOf(stringSetPreferencesKey("provider_icons") to setOf(
            "claude:claude-color", "antigravity:antigravity-color", "codex:openai-text")).toSettings()
        assertEquals("claude-color", ProviderIconCatalog.selected(ProviderId.CLAUDE, settings.providerIcons["claude"]).id)
        assertEquals("antigravity-color", ProviderIconCatalog.selected(ProviderId.ANTIGRAVITY, settings.providerIcons["antigravity"]).id)
        assertEquals("openai-text", ProviderIconCatalog.selected(ProviderId.CODEX, settings.providerIcons["codex"]).id)
        assertEquals("claudecode-color", ProviderIconCatalog.selected(ProviderId.CLAUDE, "deleted-variant").id)
        assertTrue(mutablePreferencesOf().toSettings().providerIcons.isEmpty())
    }
}
