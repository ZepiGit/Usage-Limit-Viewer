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
}

@available(iOS 17.0, *)
struct ConfiguredUsageProvider: AppIntentTimelineProvider {
    typealias Intent = UsageWidgetIntent
    typealias Entry = ConfiguredUsageEntry
    func placeholder(in context: Context) -> Entry { Entry(date: Date(), snapshot: .empty, transparent: false) }
    func snapshot(for configuration: Intent, in context: Context) async -> Entry { await entry(configuration, at: Date()) }
    func timeline(for configuration: Intent, in context: Context) async -> Timeline<Entry> {
        let now = Date()
        let first = await entry(configuration, at: now)
        var dates = stride(from: 0, through: 3600, by: 900).map { now.addingTimeInterval(Double($0)) }
        if let reset = first.snapshot.nextResetAt, reset > now, reset < now.addingTimeInterval(3600) {
            dates.append(reset.addingTimeInterval(2))
        }
        let entries = dates.sorted().map { Entry(date: $0, snapshot: first.snapshot, transparent: configuration.transparent) }
        return Timeline(entries: entries, policy: .after(now.addingTimeInterval(900)))
    }
    private func entry(_ config: Intent, at date: Date) async -> Entry {
        let presets = (try? await WidgetPresetStore.shared.load()) ?? []
        let scope: GlanceScope = switch config.content {
        case .closestResets: .closestResets
        case .allAccounts: .allAccounts
        case .provider: .provider
        case .custom: .custom
        case .account: .account
        }
        return Entry(date: date, snapshot: SnapshotCache.load().selecting(scope: scope, now: date,
            accountID: config.account?.id, providerID: config.provider.rawValue,
            customAccountIDs: presets.first { $0.id == config.custom?.id }?.accountIDs ?? []), transparent: config.transparent)
    }
}

@available(iOS 17.0, *)
struct ConfiguredUsageEntry: TimelineEntry {
    var date: Date
    var snapshot: GlanceSnapshot
    var transparent: Bool
}

@available(iOS 17.0, *)
struct ConfiguredUsageWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "com.usagelimits.widget.configurable", intent: UsageWidgetIntent.self, provider: ConfiguredUsageProvider()) { entry in
            UsageWidgetEntryView(entry: UsageEntry(date: entry.date, snapshot: entry.snapshot), transparent: entry.transparent)
        }.configurationDisplayName("Usage Bars")
            .description("Your accounts, your order. Choose one account, a provider or a custom layout.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@available(iOS 17.0, *)
struct ConfiguredRingWidget: Widget {
    var mini: Bool
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: mini ? "com.usagelimits.widget.mini-rings" : "com.usagelimits.widget.account-rings", intent: UsageWidgetIntent.self, provider: ConfiguredUsageProvider()) { entry in
            ConfiguredRingGrid(entry: entry, mini: mini)
        }.configurationDisplayName(mini ? "Mini Rings" : "Account Rings")
            .description(mini ? "Only your usage rings and provider logos." : "Usage, account and reset at a glance.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@available(iOS 17.0, *)
private struct ConfiguredRingGrid: View {
    let entry: ConfiguredUsageEntry
    let mini: Bool
    var body: some View {
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
                                Circle().stroke(UsageColors.surfaceElevated, lineWidth: 4)
                                Circle().trim(from: 0, to: CGFloat((limit?.remainingPercent ?? 0) / 100))
                                    .stroke(SeverityPalette.text(severity), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                                    .rotationEffect(.degrees(-90))
                                if let provider = ProviderID(rawValue: account.providerID ?? "") {
                                    Image(provider.assetName).resizable().scaledToFit().frame(width: 20, height: 20)
                                }
                            }.frame(width: 38, height: 38)
                            if !mini {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(account.title).font(.caption).lineLimit(1)
                                    if account.connectionStatus == .reconnectRequired { Text("Reconnect").font(.caption2) }
                                    else if let reset = limit?.resetAt { Text(reset, style: .time).font(.caption2) }
                                }.foregroundStyle(UsageColors.textPrimary)
                            }
                        }.accessibilityLabel("\(account.title), \(account.subtitle ?? ""), \(QuotaFormatting.percentText(account.rows.map(\.remainingPercent).compactMap { $0 }.min())) remaining")
                    }
                }
            }
        }.containerBackground(for: .widget) { entry.transparent ? Color.clear : UsageColors.background }
    }
}
#endif
