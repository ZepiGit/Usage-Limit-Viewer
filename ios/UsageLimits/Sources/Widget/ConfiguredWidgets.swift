#if canImport(AppIntents)
import AppIntents
import SwiftUI
import WidgetKit
import UsageLimitsKit

@available(iOS 17.0, *)
enum WidgetContentChoice: String, AppEnum {
    case closestResets, allAccounts, provider, custom, account
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Widget content"
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .closestResets: "Closest Resets", .allAccounts: "All accounts", .provider: "One provider",
        .custom: "Custom", .account: "One account"]
}

@available(iOS 17.0, *)
enum WidgetProviderChoice: String, AppEnum {
    case codex, claude, antigravity, xai, kimi
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Provider"
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .codex: "OpenAI Codex", .claude: "Claude", .antigravity: "Antigravity", .xai: "Grok", .kimi: "Kimi"]
}

@available(iOS 17.0, *)
enum WidgetToneChoice: String, AppEnum {
    case light, dark
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Text tone"
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .light: "Light", .dark: "Dark"]
}

@available(iOS 17.0, *)
struct WidgetAccountEntity: AppEntity {
    var id: String
    var name: String
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Account"
    static let defaultQuery = WidgetAccountQuery()
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

@available(iOS 17.0, *)
struct WidgetAccountQuery: EntityQuery {
    func suggestedEntities() async throws -> [WidgetAccountEntity] {
        SnapshotCache.load().accounts.map { WidgetAccountEntity(id: $0.id, name: "\($0.title) · \($0.subtitle ?? "Account")") }
    }
    func entities(for identifiers: [String]) async throws -> [WidgetAccountEntity] {
        let all = try await suggestedEntities()
        return identifiers.compactMap { id in all.first { $0.id == id } }
    }
}

@available(iOS 17.0, *)
struct WidgetPresetEntity: AppEntity {
    var id: String
    var name: String
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Custom layout"
    static let defaultQuery = WidgetPresetQuery()
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

@available(iOS 17.0, *)
struct WidgetPresetQuery: EntityQuery {
    func suggestedEntities() async throws -> [WidgetPresetEntity] {
        try await WidgetPresetStore.shared.load().map { WidgetPresetEntity(id: $0.id, name: $0.name) }
    }
    func entities(for identifiers: [String]) async throws -> [WidgetPresetEntity] {
        let all = try await suggestedEntities()
        return identifiers.compactMap { id in all.first { $0.id == id } }
    }
}

@available(iOS 17.0, *)
struct UsageWidgetIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Widget content"
    static let description = IntentDescription("Closest Resets automatically shows whatever account resets next. Create custom layouts in the app's widget settings.")
    @Parameter(title: "Content", default: .closestResets) var content: WidgetContentChoice
    @Parameter(title: "Provider", default: .codex) var provider: WidgetProviderChoice
    @Parameter(title: "Account") var account: WidgetAccountEntity?
    @Parameter(title: "Custom layout") var custom: WidgetPresetEntity?
    @Parameter(title: "Transparent background", default: false) var transparent: Bool
    // A transparent tile has no panel: the wallpaper is the backdrop and the widget cannot
    // see it. The fixed light ink is unreadable over a light wallpaper, so the tone is the
    // user's explicit choice — the same contract as the Android LIGHT/DARK override. Light
    // is the default, which is what every existing transparent tile has always drawn.
    @Parameter(title: "Text tone", default: .light) var tone: WidgetToneChoice
}

@available(iOS 17.0, *)
struct ConfiguredUsageProvider: AppIntentTimelineProvider {
    typealias Intent = UsageWidgetIntent
    typealias Entry = ConfiguredUsageEntry
    func placeholder(in context: Context) -> Entry { Entry(date: Date(), snapshot: .empty, transparent: false) }

    func snapshot(for configuration: Intent, in context: Context) async -> Entry {
        let capture = await self.capture(configuration)
        let date = Date()
        return Entry(date: date, snapshot: capture.selectedSnapshot(at: date),
            transparent: configuration.transparent, tone: configuration.tone,
            outcome: capture.outcome(at: date))
    }

    /// One request, one capture. The snapshot file is read exactly once and the preset store
    /// at most once — only for the Custom content, where a preset means anything. Every entry
    /// of the returned timeline derives from that capture, so the app publishing (or a preset
    /// being edited) mid-construction can no longer mix two input generations into one
    /// timeline; the NEXT request adopts the new generation wholesale.
    func timeline(for configuration: Intent, in context: Context) async -> Timeline<Entry> {
        let capture = await self.capture(configuration)
        let now = Date()
        let outcome = capture.outcome(at: now)
        let lead = capture.selectedSnapshot(at: now)
        var dates = stride(from: 0, through: 3600, by: 900).map { now.addingTimeInterval(Double($0)) }
        if let reset = lead.nextResetAt, reset > now, reset < now.addingTimeInterval(3600) {
            dates.append(reset.addingTimeInterval(2))
        }
        // A staleness boundary past the hour horizon needs its own entry: the views age each
        // account's severity at the entry's date, but only where an entry exists to age it.
        // Drawn from the captured cache — no reload, no network.
        dates.append(contentsOf: capture.presentationBoundaries(after: now)
            .filter { $0 > now.addingTimeInterval(3600) })
        let entries = dates.sorted().map { date in
            Entry(date: date, snapshot: capture.selectedSnapshot(at: date),
                transparent: configuration.transparent, tone: configuration.tone, outcome: outcome)
        }
        return Timeline(entries: entries, policy: .after(now.addingTimeInterval(900)))
    }

    /// Resolves everything one request needs, once: one snapshot read, at most one preset
    /// load. The outcome — including WHY a selection came up empty — is decided here and
    /// travels with the entry, instead of being re-derived (or re-guessed) by a view.
    private func capture(_ config: Intent) async -> ConfiguredWidgetCapture {
        let source = SnapshotCache.loadResult()
        let preset: ConfiguredWidgetCapture.PresetResolution
        if config.content == .custom {
            // A failed read is NOT an empty preset list: it is the reason the selection
            // is empty, and the entry keeps it.
            if let presets = try? await WidgetPresetStore.shared.load(),
               let match = presets.first(where: { $0.id == config.custom?.id }) {
                preset = .matched(accountIDs: match.accountIDs)
            } else {
                preset = .missing
            }
        } else {
            preset = .notRead
        }
        let scope: GlanceScope = switch config.content {
        case .closestResets: .closestResets
        case .allAccounts: .allAccounts
        case .provider: .provider
        case .custom: .custom
        case .account: .account
        }
        return ConfiguredWidgetCapture(source: source, preset: preset, scope: scope,
            accountID: config.account?.id, providerID: config.provider.rawValue,
            transparent: config.transparent)
    }
}

@available(iOS 17.0, *)
struct ConfiguredUsageEntry: TimelineEntry {
    var date: Date
    var snapshot: GlanceSnapshot
    var transparent: Bool
    var tone: WidgetToneChoice = .light
    var outcome: ConfiguredSelectionOutcome = .noAccounts
}

@available(iOS 17.0, *)
struct ConfiguredUsageWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "com.usagelimits.widget.configurable", intent: UsageWidgetIntent.self, provider: ConfiguredUsageProvider()) { entry in
            Group {
                if entry.outcome == .ready {
                    UsageWidgetEntryView(entry: UsageEntry(date: entry.date, snapshot: entry.snapshot),
                        transparent: entry.transparent,
                        ink: WidgetInk.ink(transparent: entry.transparent, darkTone: entry.tone == .dark))
                } else {
                    WidgetEmptyStateView(reason: entry.outcome)
                }
            }
            .widgetBackground(entry.transparent ? nil : UsageColors.background)
            .widgetURL(DeepLink.glance)
        }.configurationDisplayName("Usage Bars")
            .description("Your accounts, your order. Choose one account, a provider or a custom layout.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@available(iOS 17.0, *)
struct ConfiguredRingWidget: Widget {
    var mini: Bool
    init() { mini = false }
    init(mini: Bool) { self.mini = mini }
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: mini ? "com.usagelimits.widget.mini-rings" : "com.usagelimits.widget.account-rings", intent: UsageWidgetIntent.self, provider: ConfiguredUsageProvider()) { entry in
            ConfiguredRingGrid(entry: entry, mini: mini,
                ink: WidgetInk.ink(transparent: entry.transparent, darkTone: entry.tone == .dark))
        }.configurationDisplayName(mini ? "Mini Rings" : "Account Rings")
            .description(mini ? "Only your usage rings and provider logos." : "Usage, account and reset at a glance.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@available(iOS 17.0, *)
private struct ConfiguredRingGrid: View {
    let entry: ConfiguredUsageEntry
    let mini: Bool
    var ink: WidgetInk

    var body: some View {
        Group {
            if entry.outcome == .ready {
                grid
            } else {
                // The rings used to render nothing at all when the selection came up empty —
                // not even a word. The captured outcome says which nothing it is.
                WidgetEmptyStateView(reason: entry.outcome)
            }
        }
        .containerBackground(for: .widget) { entry.transparent ? Color.clear : UsageColors.background }
    }

    private var grid: some View {
        GeometryReader { geometry in
            let columns = max(1, Int(geometry.size.width / (mini ? 48 : 140)))
            let rows = max(1, Int(geometry.size.height / (mini ? 48 : 56)))
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: columns), spacing: 8) {
                ForEach(entry.snapshot.accounts.prefix(columns * rows)) { account in
                    Link(destination: URL(string: "usagelimits://account/\(account.id)")!) {
                        HStack(spacing: 8) {
                            let limit = account.rows.min { ($0.remainingPercent ?? .infinity) < ($1.remainingPercent ?? .infinity) }
                            let severity = account.severity(at: entry.date, staleAfter: entry.snapshot.staleAfter)
                            ZStack {
                                // In accented/monochrome renderings the host recolors both
                                // circles white at full opacity, and a partly filled ring read
                                // as full. The track drops its own opacity in those modes so
                                // the arc's geometry carries the amount. (WGT-004)
                                Circle().stroke(ink.track, lineWidth: 4).opacity(trackOpacity)
                                Circle().trim(from: 0, to: CGFloat((limit?.remainingPercent ?? 0) / 100))
                                    .stroke(SeverityPalette.text(severity), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                                    .rotationEffect(.degrees(-90))
                                if let provider = ProviderID(rawValue: account.providerID ?? "") {
                                    Image(account.iconAssetName ?? provider.assetName).resizable().scaledToFit().frame(width: 20, height: 20)
                                }
                            }.frame(width: 38, height: 38)
                            if !mini {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(account.title).font(.caption).lineLimit(1)
                                    if account.connectionStatus == .reconnectRequired { Text("Reconnect").font(.caption2) }
                                    // Overdue-aware and date-aware: a passed reset says so, a
                                    // reset days out carries its date, not a bare clock time
                                    // that could mean any day. (WGT-007)
                                    else if let reset = GlanceText.resetLine(resetAt: limit?.resetAt, now: entry.date) {
                                        Text(verbatim: reset).font(.caption2).lineLimit(1)
                                    }
                                }.foregroundStyle(ink.primary)
                            }
                        }.accessibilityLabel("\(account.title), \(account.subtitle ?? ""), \(QuotaFormatting.percentText(account.rows.map(\.remainingPercent).compactMap { $0 }.min())) remaining")
                    }
                }
            }
        }
    }

    private var trackOpacity: Double {
        renderingMode == .fullColor ? 1 : 0.3
    }

    @Environment(\.widgetRenderingMode) private var renderingMode
}
#endif
