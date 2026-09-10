import Foundation

/// Everything the user can change.
///
/// One type rather than several stores so a single value can be persisted, passed to the
/// evaluator, and bound to by the settings screen without three of them drifting apart.
public struct AppSettings: Sendable, Equatable, Codable {

    /// How often the background refresh runs, in minutes.
    ///
    /// The floor is what the platform will actually honour for periodic work; going below it
    /// buys nothing but a schedule the system quietly ignores.
    public var syncIntervalMinutes: Int

    public var notifications: NotificationSettings

    public static let minimumSyncIntervalMinutes = 15
    public static let defaultSyncIntervalMinutes = 30

    public init(
        syncIntervalMinutes: Int = AppSettings.defaultSyncIntervalMinutes,
        notifications: NotificationSettings = NotificationSettings()
    ) {
        self.syncIntervalMinutes = max(syncIntervalMinutes, AppSettings.minimumSyncIntervalMinutes)
        self.notifications = notifications
    }

    /// Decoded leniently, for the same reason as `NotificationSettings`: a missing key is a
    /// version difference, not a corrupt file, and treating it as corrupt resets everything the
    /// user chose. The floor is applied here too, so a hand-edited or downgraded file cannot
    /// install an interval the platform will silently ignore.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let minutes = try container.decodeIfPresent(Int.self, forKey: .syncIntervalMinutes)
            ?? AppSettings.defaultSyncIntervalMinutes
        self.init(
            syncIntervalMinutes: minutes,
            notifications: try container.decodeIfPresent(
                NotificationSettings.self, forKey: .notifications) ?? NotificationSettings())
    }

    /// How old a snapshot may be before this app stops trusting it.
    ///
    /// Derived rather than fixed — see `Severity.staleAfter(syncIntervalMinutes:)` for the
    /// setting that made a constant wrong.
    public var staleAfter: TimeInterval {
        Severity.staleAfter(syncIntervalMinutes: syncIntervalMinutes)
    }
}
