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

    /// How old a snapshot may be before this app stops trusting it.
    ///
    /// Derived rather than fixed — see `Severity.staleAfter(syncIntervalMinutes:)` for the
    /// setting that made a constant wrong.
    public var staleAfter: TimeInterval {
        Severity.staleAfter(syncIntervalMinutes: syncIntervalMinutes)
    }
}
