import Combine
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

    /// Writable, because the settings screen binds straight to it.
    ///
    /// Saved on every change rather than on leaving the screen: a toggle the user flips and then
    /// force-quits over must still be the setting they get back. `didSet` fires for a write
    /// through the binding chain too, since mutating a nested struct rewrites the whole value.
    @Published var settings = AppSettings() {
        didSet {
            guard settings != oldValue, !isLoadingSettings else { return }
            let value = settings
            // Inherits this actor, so everything below is already on the main actor and needs
            // no hop of its own.
            Task { [weak self, container] in
                guard let container else { return }
                // The stored value can differ from the one written — the sync interval is
                // floored at what the platform will honour — so the screen is corrected to
                // what will actually happen rather than left showing what was asked for.
                if let stored = try? await container.save(settings: value) {
                    self?.applyLoaded(stored)
                }
                BackgroundRefresh.schedule(after: value.syncIntervalMinutes)
            }
        }
    }

    /// Suppresses the save that would otherwise fire when the stored settings are read back in.
    private var isLoadingSettings = false
    @Published private(set) var isRefreshing = false
    @Published private(set) var lastError: String?

    /// Ticks so countdowns move without every view owning a timer.
    @Published private(set) var now = Date()

    private var clock: Task<Void, Never>?

    /// Built lazily and kept, because it owns the credential store and the account cache: a
    /// second one would be a second set of in-flight refresh locks, and the whole point of that
    /// lock is that there is exactly one per credential reference.
    private let container: UsageLimitsContainer?

    init(container: UsageLimitsContainer? = UsageStore.sharedContainer) {
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
    ///
    /// The staleness threshold follows the user's chosen sync interval rather than a constant:
    /// at the three-hour setting the settings screen offers, a fixed hour marks every account
    /// stale before the next refresh arrives, and a stale account sorts last — so the headline
    /// would come from whichever healthy account happened to sort first while a card at 3 % sat
    /// below it.
    var glance: GlanceSnapshot {
        GlanceModel.build(
            accounts, now: now, scope: .mostCritical, staleAfter: settings.staleAfter)
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
    nonisolated static let appGroupID = "group.com.usagelimits.shared"

    /// Loads whatever the last run left behind, before any network is touched.
    ///
    /// A cold launch that showed an empty screen until a refresh came back would look like "you
    /// have no accounts" for as long as the slowest provider took — on a bad connection, for
    /// ever. The cache is what the app knew when it was last open, which is the right thing to
    /// render while the real answer is on its way.
    func load() async {
        guard let container else {
            // Said here as well as in `refresh`, because a cold launch renders this screen before
            // anything is refreshed: without it the user sees an empty account list and the
            // explanation exists only behind a pull they have no reason to perform.
            lastError = "This build cannot reach its shared storage."
            return
        }
        applyLoaded(await container.settings())

        let cached = await container.usage()
        // A refresh that started while this was reading has newer accounts than the cache does,
        // and its result must not be overwritten by one that merely finished later.
        if !isRefreshing {
            accounts = cached
        }
    }

    /// Installs settings that came from storage, without treating the assignment as a change the
    /// user made — which would save them straight back and, worse, reschedule on every launch.
    private func applyLoaded(_ stored: AppSettings) {
        isLoadingSettings = true
        settings = stored
        isLoadingSettings = false
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

            // Posted from the foreground too, not only from the background task. A user who
            // opens the app and pulls to refresh should hear about a limit that has just run
            // out; the ledger is what stops them hearing it twice when the background task
            // reaches the same conclusion later.
            await NotificationScheduler.post(try await container.pendingNotifications())
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

        // Requested after every refresh, foreground ones included: a submitted request is
        // consumed when it runs, so an app that only asks from inside the background task gets
        // exactly one background refresh in its life.
        BackgroundRefresh.schedule(after: settings.syncIntervalMinutes)
    }

    /// Spends one Codex reset credit and republishes what the spend bought.
    ///
    /// Reported through `lastError` rather than thrown, because the only caller is a button: a
    /// spend the provider refuses has to say so on the screen the user is looking at.
    @Published private(set) var isRedeeming = false

    func redeemResetCredit(accountID: String) async {
        guard let container, !isRedeeming else { return }
        isRedeeming = true
        defer { isRedeeming = false }
        do {
            accounts = try await container.redeemResetCredit(accountID: accountID)
            lastError = nil
        } catch is CancellationError {
            // The screen was closed mid-spend. Whatever the provider answered will be reflected
            // by the next refresh.
        } catch {
            lastError = error.localizedDescription
        }
    }


    /// The one container this process uses, or nil when the App Group entitlement is missing
    /// and there is nowhere shared to write.
    ///
    /// A single shared instance, because the app builds one at launch for the background task
    /// and the store takes one as a default argument — and two would mean two credential stores
    /// and two sets of in-flight refresh locks. That lock exists precisely so one rotating
    /// refresh token cannot be spent twice; duplicating the object that holds it defeats it.
    ///
    /// `nonisolated`, because a default argument is evaluated at the call site and the call site
    /// is a `@StateObject` initialiser whose isolation is not this type's to assume. The
    /// container is an actor, so it is safe to reach from anywhere.
    nonisolated static let sharedContainer: UsageLimitsContainer? = {
        guard let directory = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupID) else { return nil }
        return UsageLimitsContainer(
            directory: directory, credentials: KeychainCredentialStore())
    }()
}
