package com.usagelimits.ui

import com.usagelimits.R
import com.usagelimits.core.model.ProviderId

data class ProviderIconChoice(val id: String, val label: String, val drawable: Int)

object ProviderIconCatalog {
    fun choices(provider: ProviderId): List<ProviderIconChoice> = when (provider) {
        ProviderId.CLAUDE -> listOf(
            ProviderIconChoice("claudecode-color", "Claude Code · Color", R.drawable.icon_claudecode_color),
            ProviderIconChoice("claudecode", "Claude Code · Monochrome", R.drawable.icon_claudecode),
            ProviderIconChoice("claudecode-text", "Claude Code · Wordmark", R.drawable.icon_claudecode_text),
            ProviderIconChoice("claude-color", "Claude · Color", R.drawable.icon_claude_color),
            ProviderIconChoice("claude", "Claude · Monochrome", R.drawable.icon_claude),
            ProviderIconChoice("claude-text", "Claude · Wordmark", R.drawable.icon_claude_text),
        )
        ProviderId.CODEX -> listOf(
            ProviderIconChoice("openai", "OpenAI", R.drawable.icon_openai),
            ProviderIconChoice("openai-text", "OpenAI · Wordmark", R.drawable.icon_openai_text),
        )
        ProviderId.XAI -> listOf(
            ProviderIconChoice("grok", "Grok", R.drawable.icon_grok),
            ProviderIconChoice("grok-text", "Grok · Wordmark", R.drawable.icon_grok_text),
        )
        ProviderId.ANTIGRAVITY -> listOf(
            ProviderIconChoice("gemini-color", "Gemini · Color", R.drawable.icon_gemini_color),
            ProviderIconChoice("antigravity-color", "Antigravity · Color", R.drawable.icon_antigravity_color),
            ProviderIconChoice("gemini", "Gemini · Monochrome", R.drawable.icon_gemini),
            ProviderIconChoice("antigravity", "Antigravity · Monochrome", R.drawable.icon_antigravity),
            ProviderIconChoice("gemini-text", "Gemini · Wordmark", R.drawable.icon_gemini_text),
            ProviderIconChoice("antigravity-text", "Antigravity · Wordmark", R.drawable.icon_antigravity_text),
        )
        ProviderId.KIMI -> listOf(
            ProviderIconChoice("kimi-color", "Kimi · Color", R.drawable.icon_kimi_color),
            ProviderIconChoice("kimi", "Kimi · Monochrome", R.drawable.icon_kimi),
            ProviderIconChoice("kimi-text", "Kimi · Wordmark", R.drawable.icon_kimi_text),
        )
    }
    fun selected(provider: ProviderId, id: String?): ProviderIconChoice =
        choices(provider).firstOrNull { it.id == id } ?: choices(provider).first()
}
