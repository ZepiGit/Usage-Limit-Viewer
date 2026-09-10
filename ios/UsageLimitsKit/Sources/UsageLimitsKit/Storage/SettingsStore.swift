import Foundation

/// What the user chose, kept across launches.
///
/// In the shared container rather than `UserDefaults` for one reason that matters: a background
/// task and the widget's extension are separate processes, and the sync interval and the
/// staleness threshold derived from it have to mean the same thing in all three. A suite-scoped
/// `UserDefaults` would work too; a file keeps one storage story with the account cache beside
/// it rather than two.
///
/// Nothing here is a secret, so unlike the credential store this needs no protection class of
/// its own beyond the container's.
public actor SettingsStore {

    private let fileURL: URL
    private var current: AppSettings

    public init(directory: URL, fileName: String = "settings.json") {
        self.fileURL = directory.appendingPathComponent(fileName)
        self.current = Self.load(from: fileURL)
    }

    public func settings() -> AppSettings { current }

    /// Saves, and reports what was actually stored.
    ///
    /// `AppSettings.init` floors the sync interval at what the platform will honour, so the
    /// value that comes back can differ from the one that went in. Returning it means the
    /// settings screen shows what will happen rather than what was asked for.
    @discardableResult
    public func save(_ settings: AppSettings) throws -> AppSettings {
        // Round-tripped through the initialiser so the floor is applied to a value arriving from
        // anywhere — a decoded file included, since a hand-edited or downgraded file could
        // otherwise install an interval the system silently ignores.
        let normalised = AppSettings(
            syncIntervalMinutes: settings.syncIntervalMinutes,
            notifications: settings.notifications)

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        try encoder.encode(normalised).write(to: fileURL, options: .atomic)

        current = normalised
        return normalised
    }

    private static func load(from url: URL) -> AppSettings {
        guard
            let data = try? Data(contentsOf: url),
            let stored = try? JSONDecoder().decode(AppSettings.self, from: data)
        else {
            // Defaults rather than a failure. These are preferences: losing them costs the user
            // a trip back to the settings screen, where refusing to launch over an unreadable
            // preferences file costs them the app.
            return AppSettings()
        }
        // Through the initialiser again, for the same reason as `save`.
        return AppSettings(
            syncIntervalMinutes: stored.syncIntervalMinutes,
            notifications: stored.notifications)
    }
}
