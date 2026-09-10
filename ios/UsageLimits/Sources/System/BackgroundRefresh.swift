import BackgroundTasks
import Foundation
import UsageLimitsKit

/// Keeps the numbers current while the app is closed.
///
/// The app's whole promise is that a glance answers the question, and a glance means the widget
/// and a notification — both of which need a refresh nobody asked for. iOS grants that through
/// `BGAppRefreshTask`, on its own schedule: the interval below is the earliest the system will
/// consider running, never a guarantee, which is why nothing in the UI is phrased as "updates
/// every 30 minutes".
@MainActor
enum BackgroundRefresh {

    /// Must also appear in `BGTaskSchedulerPermittedIdentifiers` in Info.plist. A mismatch is
    /// not an error the system reports — registration simply throws at launch, and the app never
    /// refreshes in the background again.
    static let taskIdentifier = "com.usagelimits.app.refresh"

    /// Registers the handler. Must be called before the app finishes launching; later is too
    /// late, and iOS treats it as a programmer error.
    /// Returns whether the system accepted the registration.
    ///
    /// The result used to be dropped. It is the only signal there is: a `taskIdentifier` that
    /// does not appear in `BGTaskSchedulerPermittedIdentifiers` is refused here and nowhere
    /// else, and the consequence is that the app never refreshes in the background again —
    /// which looks to the user exactly like limits that quietly stop updating. Silently
    /// discarding the one indication of that is the worst available option.
    ///
    /// Deliberately not fatal. A build without the entitlement still works in the foreground,
    /// and refusing to launch over it would turn a degraded app into no app.
    @discardableResult
    static func register(container: UsageLimitsContainer) -> Bool {
        let registered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: taskIdentifier, using: nil
        ) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            Task { @MainActor in await run(task, container: container) }
        }
        if !registered {
            // No token material here, and nothing user-specific: just the identifier that the
            // Info.plist and this constant have to agree on.
            print("[UsageLimits] background refresh registration refused for \(taskIdentifier)")
        }
        return registered
    }

    /// Asks for the next opportunity.
    ///
    /// Called after every refresh, foreground ones included, because a submitted request is
    /// consumed when it runs: not resubmitting means exactly one background refresh ever
    /// happens. Submitting while one is already pending simply replaces it.
    static func schedule(after minutes: Int) {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(minutes) * 60)
        // Throws in the simulator and when the entitlement is missing. Neither is worth failing
        // a foreground refresh over, and neither is something the user can act on.
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func run(_ task: BGAppRefreshTask, container: UsageLimitsContainer) async {
        // The next one is requested FIRST. The system can suspend this task at any point, and a
        // reschedule that only happens on the success path means one timeout ends background
        // refresh permanently.
        let minutes = await container.settings().syncIntervalMinutes
        schedule(after: minutes)

        let work = Task {
            try await container.refresh()
            let pending = try await container.pendingNotifications()
            await NotificationScheduler.post(pending)
        }

        // The system's warning that time is up. Cancelling lets the sync engine unwind — with
        // the deliberate exception of a token exchange in flight, which is never cancelled
        // because abandoning one between the rotation and the save loses the account.
        task.expirationHandler = { work.cancel() }

        let succeeded = (try? await work.value) != nil
        // Reporting honestly matters: iOS uses the success rate to decide how often to run this
        // app again, so claiming success after a failed refresh buys nothing and claiming
        // failure after a good one costs future runs.
        task.setTaskCompleted(success: succeeded)
    }
}
