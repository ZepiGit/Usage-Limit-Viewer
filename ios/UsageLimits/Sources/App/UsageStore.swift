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
                // Applied only if no NEWER edit has landed meanwhile. Two toggles in quick
                // succession are two saves in flight; the first's acknowledgement arriving
                // second used to reinstall its older value over the second toggle — and the next
                // edit then copied that reverted value forward, so the loss became permanent.
                if let stored = try? await container.save(settings: value), self?.settings == value {
                    self?.applyLoaded(stored)
                }
                BackgroundRefresh.schedule(after: value.syncIntervalMinutes)
            }
        }
    }

    /// Suppresses the save that would otherwise fire when the stored settings are read back in.
    private var isLoadingSettings = false

    /// Dismisses whatever `lastError` is currently reporting.
    ///
    /// Needed because the alert that shows it has to be able to close: a binding derived from a
    /// non-nil check needs somewhere to write `false` back to.
    func clearError() { lastError = nil }
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

        #if DEBUG
        if MarketingDemo.isEnabled {
            now = MarketingDemo.now
            accounts = MarketingDemo.accounts
            return
        }
        #endif

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
            accounts, now: now, scope: .allAccounts, staleAfter: settings.staleAfter)
    }

    /// The accounts in the order the overview should show them.
    ///
    /// Two orders, and which one applies is a choice the user has already made. Until anyone
    /// drags a card the list is worst-first, which is what the app is for. Once someone has
    /// arranged their accounts by hand that order wins and nothing re-ranks it — the repository
    /// already returns them arranged, so the manual case is the list exactly as it arrived.
    ///
    /// Built separately from `glance` rather than reordering that, because the summary card's
    /// headline and next reset come from whichever account LEADS the urgency ranking, and a
    /// user dragging a healthy account to the top must not thereby change what the headline
    /// reports about the fleet.
    var orderedAccounts: [GlanceAccount] {
        // Repository order is shared by Overview and All accounts widgets.
        return GlanceModel.build(
            accounts, now: now, scope: .allAccounts, staleAfter: settings.staleAfter).accounts
    }

    /// Persists the order the user dragged the cards into.
    ///
    /// Latches `accountsManuallyOrdered` at the same time: writing the order without the flag
    /// would store an arrangement the overview then ignores, which reads as the drag having done
    /// nothing. The flag is set FIRST so the list renders in the new order even if the write
    /// fails — and a failed write says so rather than silently reverting on the next launch.
    func reorderAccounts(ids: [String]) async {
        guard let container else { return }
        settings.accountsManuallyOrdered = true
        do {
            accounts = try await container.reorder(ids: ids)
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Whether this account is allowed to notify.
    ///
    /// Phrased positively — the switch on screen reads "Notifications", not "Muted" — while the
    /// stored set names the muted ones, so that an account added later is not silent by default.
    func notificationsEnabled(accountID: String) -> Bool {
        !settings.notifications.mutedAccountIDs.contains(accountID)
    }

    func setNotifications(enabled: Bool, accountID: String) {
        // Assigning the whole struct, because `settings`' `didSet` is what saves: mutating the
        // nested set through a binding rewrites the whole value anyway, and doing it explicitly
        // keeps the one save path visible.
        var updated = settings
        if enabled {
            updated.notifications.mutedAccountIDs.remove(accountID)
        } else {
            updated.notifications.mutedAccountIDs.insert(accountID)
        }
        settings = updated
    }

    /// The plan tier for one account, as a label, or nil when the provider never stated one.
    ///
    /// Named `tierLabel` rather than `planLabel` so the call to the kit's free `planLabel(_:)`
    /// inside it cannot be misread as recursion.
    func tierLabel(accountID: String) -> String? {
        accounts.first { $0.account.id == accountID }.flatMap { planLabel($0.account.plan) }
    }

    /// When this account's LONGEST allowance comes back, when that is not simply the next reset.
    ///
    /// Suppressed when it coincides with the soonest reset, which the card already shows: two
    /// lines stating the same instant in different words is noise, and the one the user acts on
    /// is the sooner one. Same rule as Android.
    func renewalLabel(accountID: String, now: Date) -> String? {
        guard let usage = accounts.first(where: { $0.account.id == accountID }) else { return nil }
        let windows = usage.snapshot?.windows ?? []
        guard windows.count >= 2 else { return nil }
        // A provider that does not state a window's duration sorts BELOW every window that
        // does, rather than being taken for the longest.
        guard let longest = windows.max(by: { ($0.periodSeconds ?? -1) < ($1.periodSeconds ?? -1) })
        else { return nil }
        guard let renewsAt = longest.resetAt, renewsAt > now else { return nil }
        let soonest = windows.compactMap(\.resetAt).filter { $0 > now }.min()
        guard renewsAt != soonest else { return nil }
        return "\(longest.label) renews \(Countdown.format(until: renewsAt, from: now))"
    }

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
        #if DEBUG
        if MarketingDemo.isEnabled { return }
        #endif
        guard let container else {
            // Said here as well as in `refresh`, because a cold launch renders this screen before
            // anything is refreshed: without it the user sees an empty account list and the
            // explanation exists only behind a pull they have no reason to perform.
            lastError = "This build cannot reach its shared storage."
            return
        }
        // Before anything reads or writes an account. Keychain items outlive the app being
        // deleted, so a reinstall inherits every credential of the install before it; this is
        // where that is noticed and cleaned up. A failure here is not fatal — the check retries
        // on the next launch — and must not stop the cache from rendering.
        try? await container.prepareForUse()

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
        #if DEBUG
        if MarketingDemo.isEnabled { return }
        #endif
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


    /// Disconnects an account and deletes the credentials this device holds for it.
    ///
    /// The container has done this, and been tested doing it, since before there was a screen
    /// that could ask — which meant a user could connect an account and then had no way to
    /// disconnect it. Deleting the app was the only route, and that leaves keychain items
    /// behind, so it was not even a good one.
    ///
    /// Reported through `lastError` rather than thrown: the caller is a button, and an account
    /// that could not be removed has to stay on screen saying so, so the user can try again.
    /// This removes what is stored HERE; it is not an OAuth revocation at the provider, and the
    /// confirmation text says so rather than promising something this app cannot do.
    @Published private(set) var isRemoving = false

    func removeAccount(accountID: String) async {
        guard let container, !isRemoving else { return }
        isRemoving = true
        defer { isRemoving = false }
        do {
            try await container.remove(id: accountID)
            accounts = await container.usage()
            lastError = nil
        } catch {
            // Deliberately not optimistic: the row stays until the removal actually succeeded,
            // because a list that drops an account whose credentials are still on the device
            // tells the user something untrue.
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
        #if DEBUG
        // Screenshot launches must never open the keychain, cached accounts or saved settings.
        if MarketingDemo.isEnabled { return nil }
        #endif
        guard let directory = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupID) else { return nil }
        return UsageLimitsContainer(
            directory: directory, credentials: KeychainCredentialStore())
    }()
}
