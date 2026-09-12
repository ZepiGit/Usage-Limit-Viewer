import Foundation

public struct ProviderIconChoice: Identifiable, Equatable, Sendable {
    public let id: String
    public let label: String
    public let assetName: String
}

public enum ProviderIconCatalog {
    public static func choices(for provider: ProviderID) -> [ProviderIconChoice] {
        switch provider {
        case .claude: return [
            ProviderIconChoice(id: "claudecode-color", label: "Claude Code · Color", assetName: "ProviderIconClaudecodeColor"),
            ProviderIconChoice(id: "claudecode", label: "Claude Code · Monochrome", assetName: "ProviderIconClaudecode"),
            ProviderIconChoice(id: "claudecode-text", label: "Claude Code · Wordmark", assetName: "ProviderIconClaudecodeText"),
            ProviderIconChoice(id: "claude-color", label: "Claude · Color", assetName: "ProviderIconClaudeColor"),
            ProviderIconChoice(id: "claude", label: "Claude · Monochrome", assetName: "ProviderIconClaude"),
            ProviderIconChoice(id: "claude-text", label: "Claude · Wordmark", assetName: "ProviderIconClaudeText"),
        ]
        case .codex: return [
            ProviderIconChoice(id: "openai", label: "OpenAI", assetName: "ProviderIconOpenai"),
            ProviderIconChoice(id: "openai-text", label: "OpenAI · Wordmark", assetName: "ProviderIconOpenaiText"),
        ]
        case .xai: return [
            ProviderIconChoice(id: "grok", label: "Grok", assetName: "ProviderIconGrok"),
            ProviderIconChoice(id: "grok-text", label: "Grok · Wordmark", assetName: "ProviderIconGrokText"),
        ]
        case .antigravity: return [
            ProviderIconChoice(id: "gemini-color", label: "Gemini · Color", assetName: "ProviderIconGeminiColor"),
            ProviderIconChoice(id: "antigravity-color", label: "Antigravity · Color", assetName: "ProviderIconAntigravityColor"),
            ProviderIconChoice(id: "gemini", label: "Gemini · Monochrome", assetName: "ProviderIconGemini"),
            ProviderIconChoice(id: "antigravity", label: "Antigravity · Monochrome", assetName: "ProviderIconAntigravity"),
            ProviderIconChoice(id: "gemini-text", label: "Gemini · Wordmark", assetName: "ProviderIconGeminiText"),
            ProviderIconChoice(id: "antigravity-text", label: "Antigravity · Wordmark", assetName: "ProviderIconAntigravityText"),
        ]
        case .kimi: return [
            ProviderIconChoice(id: "kimi-color", label: "Kimi · Color", assetName: "ProviderIconKimiColor"),
            ProviderIconChoice(id: "kimi", label: "Kimi · Monochrome", assetName: "ProviderIconKimi"),
            ProviderIconChoice(id: "kimi-text", label: "Kimi · Wordmark", assetName: "ProviderIconKimiText"),
        ]
        }
    }
    public static func selected(for provider: ProviderID, id: String?) -> ProviderIconChoice {
        let options = choices(for: provider)
        return options.first { $0.id == id } ?? options[0]
    }
}
