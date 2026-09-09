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

    init() {
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

    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }

        // Deliberately left unimplemented until the provider clients land: showing invented
        // numbers would be worse than showing none, and this screen's whole job is to be
        // trusted about how much quota is left.
        lastError = nil
        now = Date()
    }
}
