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

    /// Set by the app at launch. Held rather than published because there is exactly one
    /// consumer and a tap is an event, not a state.
    var onAccountTapped: ((String) -> Void)?

    private override init() { super.init() }

    /// Installs this as the notification delegate.
    ///
    /// Must happen before launch finishes, or the system delivers the launch tap to nobody and
    /// the first notification a user acts on is the one that does not route.
    func install() {
        UNUserNotificationCenter.current().delegate = self
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let identifier = response.notification.request.content.userInfo["accountId"] as? String
        Task { @MainActor in
            if let identifier, !identifier.isEmpty {
                NotificationRouter.shared.onAccountTapped?(identifier)
            }
            completionHandler()
        }
    }
}
