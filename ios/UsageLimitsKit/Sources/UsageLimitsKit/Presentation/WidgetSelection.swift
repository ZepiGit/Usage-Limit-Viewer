import Foundation

extension GlanceSnapshot {
    /// Selection is applied to the same ordered cache on every widget instance.
    public func selecting(scope: GlanceScope, now: Date, accountID: String? = nil,
        providerID: String? = nil, customAccountIDs: [String] = []) -> GlanceSnapshot {
        let selected: [GlanceAccount]
        switch scope {
        case .allAccounts: selected = accounts
        case .account: selected = accounts.filter { $0.id == accountID }
        case .provider: selected = accounts.filter { $0.providerID == providerID }
        case .custom:
            var seen = Set<String>()
            selected = customAccountIDs.filter { seen.insert($0).inserted }.compactMap { id in accounts.first { $0.id == id } }
        case .closestResets, .mostCritical:
            selected = accounts.enumerated().sorted { lhs, rhs in
                let left = lhs.element.resetDates.filter { $0 > now }.min() ?? .distantFuture
                let right = rhs.element.resetDates.filter { $0 > now }.min() ?? .distantFuture
                return left == right ? lhs.offset < rhs.offset : left < right
            }.map(\.element)
        }
        guard let lead = selected.first else { return .empty }
        return GlanceSnapshot(accounts: selected, accountCount: selected.count,
            updatedAt: selected.compactMap(\.fetchedAt).max(),
            nextResetAt: lead.resetDates.filter { $0 > now }.min(),
            overallSeverity: lead.severity(at: now, staleAfter: staleAfter),
            headlineShort: lead.rows.first { $0.category == .fiveHour },
            headlineLong: lead.rows.first { $0.category != .fiveHour }, staleAfter: staleAfter)
    }
}

public struct WidgetPreset: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var accountIDs: [String]
    public init(id: String = UUID().uuidString, name: String, accountIDs: [String]) {
        self.id = id; self.name = name; self.accountIDs = accountIDs
    }
}

/// The app and extension share only chosen account IDs and user-entered preset names.
public actor WidgetPresetStore {
    public static let shared = WidgetPresetStore()
    private let fileURL: URL?
    public init(fileURL: URL? = nil) {
        #if canImport(Darwin)
        self.fileURL = fileURL ?? FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.usagelimits.shared")?.appendingPathComponent("widget-presets.json")
        #else
        self.fileURL = fileURL
        #endif
    }
    public func load() throws -> [WidgetPreset] {
        guard let fileURL, FileManager.default.fileExists(atPath: fileURL.path) else { return [] }
        return try JSONDecoder().decode([WidgetPreset].self, from: Data(contentsOf: fileURL))
    }
    public func save(_ presets: [WidgetPreset]) throws {
        guard let fileURL else { throw CocoaError(.fileNoSuchFile) }
        try JSONEncoder().encode(presets).write(to: fileURL, options: .atomic)
    }
}
