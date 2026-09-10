import Foundation
import UserNotifications
import UsageLimitsKit

/// Posts the alerts the kit has already decided on.
///
/// Deliberately thin. Every judgement — what counts as an edge, which tier speaks, what has
/// already been said — belongs to `NotificationEvaluator` and `NotificationLedger`, which run on
/// Linux and are tested there. This type knows only how to ask permission and how to hand a
/// string to the system, so the logic that decides whether a user hears anything is never in a
/// file that can only be exercised on a device.
@MainActor
enum NotificationScheduler {

    private static let categoryIdentifier = "com.usagelimits.quota"

    /// Asks once, and reports whether alerts may be posted at all.
    ///
    /// `.alert` and `.sound` only: this app has nothing to badge. A badge count on a quota
    /// monitor would be a number with no meaning — quota is a level, not a queue.
    static func requestAuthorization() async -> Bool {
        do {
            return try await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound])
        } catch {
            // A refusal and a failure to ask are the same thing from here: nothing can be
            // posted, and the app carries on showing the numbers on screen, which is its actual
            // job.
            return false
        }
    }

    /// Posts one notification per event.
    ///
    /// The caller has already claimed these, so posting is unconditional — no deduplication
    /// happens here, and none should: a second opinion about whether something has been said
    /// already is exactly how a duplicate or a silence gets introduced.
    ///
    /// Each request carries the event's own key as its identifier. Re-posting the same key
    /// replaces rather than stacks, so even a bug upstream cannot produce two copies of one
    /// alert sitting in Notification Centre.
    static func post(_ events: [NotificationEvaluator.Event]) async {
        let centre = UNUserNotificationCenter.current()
        for event in events {
            let content = UNMutableNotificationContent()
            content.title = "Usage Limits"
            content.body = event.line
            content.sound = .default
            content.categoryIdentifier = categoryIdentifier
            // Opens the account rather than the app's last tab: the alert names one account, so
            // the tap should land on it.
            content.userInfo = ["accountId": event.accountId]

            // No trigger: delivered immediately. These describe something that has already
            // happened, and a quota alert scheduled for later is a quota alert about the past.
            try? await centre.add(
                UNNotificationRequest(identifier: event.key, content: content, trigger: nil))
        }
    }
}
