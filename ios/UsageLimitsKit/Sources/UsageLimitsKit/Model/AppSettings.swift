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

    /// Whether the overview follows the order the user dragged the accounts into.
    ///
    /// False until they drag one. Until then the list is ranked by urgency, which is what the
    /// app is for — but once someone has arranged their accounts deliberately, re-ranking them
    /// on the next sync is the app overruling a choice they made by hand.
    public var accountsManuallyOrdered: Bool

    /// Show the plan tier — Plus, Pro, Max — beside the provider name.
    public var showSubscriptionTier: Bool

    /// Show when the long allowance renews, on the overview only.
    ///
    /// Off by default, and deliberately not in the widgets: the widget's job is the number you
    /// are about to run out of, and a second date competing with the next reset is the kind of
    /// detail that makes a glanceable tile unglanceable.
    public var showRenewalTime: Bool
    public var providerIcons: [String: String]

    public static let minimumSyncIntervalMinutes = 15
    public static let defaultSyncIntervalMinutes = 30

    public init(
        syncIntervalMinutes: Int = AppSettings.defaultSyncIntervalMinutes,
        notifications: NotificationSettings = NotificationSettings(),
        accountsManuallyOrdered: Bool = false,
        showSubscriptionTier: Bool = true,
        showRenewalTime: Bool = false,
        providerIcons: [String: String] = [:]
    ) {
        self.syncIntervalMinutes = max(syncIntervalMinutes, AppSettings.minimumSyncIntervalMinutes)
        self.notifications = notifications
        self.accountsManuallyOrdered = accountsManuallyOrdered
        self.showSubscriptionTier = showSubscriptionTier
        self.showRenewalTime = showRenewalTime
        self.providerIcons = providerIcons
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
                NotificationSettings.self, forKey: .notifications) ?? NotificationSettings(),
            accountsManuallyOrdered: try container.decodeIfPresent(
                Bool.self, forKey: .accountsManuallyOrdered) ?? false,
            showSubscriptionTier: try container.decodeIfPresent(
                Bool.self, forKey: .showSubscriptionTier) ?? true,
            showRenewalTime: try container.decodeIfPresent(
                Bool.self, forKey: .showRenewalTime) ?? false,
            providerIcons: try container.decodeIfPresent([String: String].self, forKey: .providerIcons) ?? [:])
    }

    /// This value with every rule the initialiser enforces applied to it.
    ///
    /// The store used to normalise by calling the initialiser with the fields it knew about,
    /// which silently DROPPED every field added afterwards: a value arriving with the tier
    /// switched off was written back with it on. Copying and adjusting means a new preference
    /// is carried by default and only a rule written here can change it.
    public func normalised() -> AppSettings {
        var copy = self
        copy.syncIntervalMinutes = max(syncIntervalMinutes, AppSettings.minimumSyncIntervalMinutes)
        return copy
    }

    /// How old a snapshot may be before this app stops trusting it.
    ///
    /// Derived rather than fixed — see `Severity.staleAfter(syncIntervalMinutes:)` for the
    /// setting that made a constant wrong.
    public var staleAfter: TimeInterval {
        Severity.staleAfter(syncIntervalMinutes: syncIntervalMinutes)
    }
}
