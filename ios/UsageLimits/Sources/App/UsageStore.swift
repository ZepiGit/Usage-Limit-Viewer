import Foundation
import UsageLimitsKit

/// The app's single source of truth for what the screens render.
///
/// `@MainActor` on the whole type rather than on individual mutations: every property here is
/// read by SwiftUI during layout, so anything that publishes from a background thread is a
/// crash waiting for a slow network. The fetching itself is `async` and hops off the main
/// actor inside the provider clients.
@MainActor
final class UsageStore: ObservableObject {

    @Published private(set) var accounts: [AccountUsage] = []

    /// Writable, because the settings screen binds straight to it. Persisting it belongs to a
    /// store this class will own once there is anything to persist.
    @Published var settings = AppSettings()
    @Published private(set) var isRefreshing = false
    @Published private(set) var lastError: String?

    /// Ticks so countdowns move without every view owning a timer.
    @Published private(set) var now = Date()

    private var clock: Task<Void, Never>?

    /// Built lazily and kept, because it owns the credential store and the account cache: a
    /// second one would be a second set of in-flight refresh locks, and the whole point of that
    /// lock is that there is exactly one per credential reference.
    private let container: UsageLimitsContainer?

    init(container: UsageLimitsContainer? = UsageStore.makeContainer()) {
        self.container = container

        // A countdown that only moves when the data refreshes is worse than no countdown: it
        // reads as a live number while being up to half an hour stale.
        clock = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30 * NSEC_PER_SEC)
                await MainActor.run { self?.now = Date() }
            }
        }
    }

    deinit { clock?.cancel() }

    /// What the Overview screen leads with.
    var glance: GlanceSnapshot {
        GlanceModel.build(accounts, now: now, scope: .mostCritical)
    }

    /// One account, reduced for the notification evaluator.
    var summaries: [AccountSummary] { accounts.map(AccountSummary.init) }

    /// Every window that has a reset time, soonest first — the Resets screen.
    var upcomingResets: [(account: ProviderAccount, window: UsageWindow)] {
        accounts
            .flatMap { usage in
                (usage.snapshot?.windows ?? [])
                    .filter { $0.resetAt != nil }
                    .map { (usage.account, $0) }
            }
            .sorted { ($0.1.resetAt ?? .distantFuture) < ($1.1.resetAt ?? .distantFuture) }
    }

    /// Shared with the widget extension, which names the same group.
    ///
    /// The widget is a separate process with no access to the app's memory, so a file in this
    /// container is the entirety of what the home screen knows. `UsageLimitsContainer` writes it
    /// from the same `GlanceModel` call this screen renders — one derivation, one answer, rather
    /// than the app and the widget each deciding what "most critical" means.
    static let appGroupID = "group.com.usagelimits.shared"

    /// Loads whatever the last run left behind, before any network is touched.
    ///
    /// A cold launch that showed an empty screen until a refresh came back would look like "you
    /// have no accounts" for as long as the slowest provider took — on a bad connection, for
    /// ever. The cache is what the app knew when it was last open, which is the right thing to
    /// render while the real answer is on its way.
    func load() async {
        guard let container else { return }
        accounts = await container.usage()
    }

    func refresh() async {
        guard !isRefreshing else { return }
        guard let container else {
            // No shared container means the app group is missing from the entitlements, which is
            // a build fault rather than anything the user did — but it must still say so, since
            // the alternative is a screen that stays empty and explains nothing.
            lastError = "This build cannot reach its shared storage."
            return
        }

        isRefreshing = true
        defer { isRefreshing = false }

        now = Date()
        do {
            // The container returns the usage as it stands after the sync, rather than leaving
            // this to re-read it: re-reading races the write that just happened.
            accounts = try await container.refresh()
            lastError = nil
        } catch is CancellationError {
            // The screen was closed. Not a failure, and not something to paint red.
        } catch {
            // Per-account failures never reach here — the engine records those on the accounts
            // themselves, which is where a user can act on them. This is the whole run failing.
            accounts = await container.usage()
            lastError = error.localizedDescription
        }

        // The container publishes the widget snapshot itself as part of a refresh, so the app
        // does not write the same file a second time from a second process-shared path.
    }

    /// The shared container both this app and its widget name, or nil when the entitlement is
    /// missing and there is nowhere shared to write.
    private static func makeContainer() -> UsageLimitsContainer? {
        guard let directory = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupID) else { return nil }
        return UsageLimitsContainer(
            directory: directory, credentials: KeychainCredentialStore())
    }
}
