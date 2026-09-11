import Foundation
import UserNotifications

/// Where a tapped alert lands.
///
/// `NotificationScheduler` has always put the account's id in the payload, and a comment there
/// said the tap "should land on it". Nothing read it: no delegate was ever set, so every alert
/// opened the app on whichever tab happened to be showing — the promise existed only in the
/// comment. This is the half that makes it true.
///
/// The Accounts tab rather than the Overview, because the alert names ONE account and that is
/// the screen where its card, its error and its actions are; the Overview is a summary of the
/// fleet, which is what the user was already not looking at.
///
/// A note on how far this is verified: it type-checks, and the delegate is installed before the
/// app finishes launching, which is the requirement the system actually enforces. Nothing in
/// this project can deliver a real notification and tap it — XCUITest cannot — so the routing
/// itself is checked by reading, not by a test. That is stated here rather than left for
/// someone to assume otherwise.
@MainActor
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate {

    static let shared = NotificationRouter()

    /// Set by the app once its first view is up. Held rather than published because there is
    /// exactly one consumer and a tap is an event, not a state.
    ///
    /// A tap that arrives BEFORE the consumer is installed — a cold launch from the alert
    /// itself, where the delegate fires ahead of the root view's `.task` — is kept and handed
    /// over the moment the consumer appears. Dropped, the one tap most worth routing was the
    /// one that never was.
    var onAccountTapped: ((String) -> Void)? {
        didSet {
            guard let handler = onAccountTapped, let identifier = pendingAccountID else { return }
            pendingAccountID = nil
            handler(identifier)
        }
    }

    private var pendingAccountID: String?

    private func route(_ identifier: String) {
        if let handler = onAccountTapped {
            handler(identifier)
        } else {
            pendingAccountID = identifier
        }
    }

    private override init() { super.init() }

    /// Installs this as the notification delegate.
    ///
    /// Must happen before launch finishes, or the system delivers the launch tap to nobody and
    /// the first notification a user acts on is the one that does not route.
    func install() {
        UNUserNotificationCenter.current().delegate = self
    }

    /// The `async` form of the delegate method, not the completion-handler one.
    ///
    /// Under Swift 6 strict concurrency the completion-handler form cannot be written from a
    /// main-actor class: the handler is not `Sendable`, so hopping to the main actor to read
    /// `onAccountTapped` and then calling it is "sending 'completionHandler' risks causing data
    /// races" — the exact error the real SDK build produced, which the Linux shim could not.
    /// The `async` form has no handler to send. Only the extracted id, a `String`, crosses the
    /// isolation boundary; `response` itself is read here and never escapes.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let identifier = response.notification.request.content.userInfo["accountId"] as? String
        guard let identifier, !identifier.isEmpty else { return }
        await MainActor.run {
            NotificationRouter.shared.route(identifier)
        }
    }

    /// Shows an alert that arrives while the app is in the foreground.
    ///
    /// Without this method the system suppresses foreground presentation entirely, and the
    /// scheduler posts from the foreground on purpose — a user who opens the app and pulls to
    /// refresh is meant to hear about a limit that just ran out. The sound the scheduler sets
    /// was inaudible for the same reason.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}
