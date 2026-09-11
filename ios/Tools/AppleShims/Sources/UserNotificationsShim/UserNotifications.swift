@_exported import AppleShims
import Foundation

public final class UNNotificationSound {
    public static let `default` = UNNotificationSound()
}

public final class UNMutableNotificationContent {
    public init() {}
    public var title: String = ""
    public var body: String = ""
    public var sound: UNNotificationSound?
    public var threadIdentifier: String = ""
    public var categoryIdentifier: String = ""
    public var userInfo: [AnyHashable: Any] = [:]
}

public final class UNNotificationRequest {
    public let content = UNMutableNotificationContent()
    public init() {}
    public init(identifier: String, content: UNMutableNotificationContent, trigger: Any?) {}
}

public struct UNAuthorizationOptions: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public static let alert = UNAuthorizationOptions(rawValue: 1)
    public static let sound = UNAuthorizationOptions(rawValue: 2)
    public static let badge = UNAuthorizationOptions(rawValue: 4)
}

/// What the system hands back when someone acts on a delivered alert.
public final class UNNotificationResponse {
    public let notification = UNNotification()
    public init() {}
}

public final class UNNotification {
    public let request = UNNotificationRequest()
    public init() {}
}

public protocol UNUserNotificationCenterDelegate: AnyObject {}

public final class UNUserNotificationCenter {
    public static func current() -> UNUserNotificationCenter { UNUserNotificationCenter() }
    public func requestAuthorization(options: UNAuthorizationOptions) async throws -> Bool { true }
    public func add(_ request: UNNotificationRequest) async throws {}
    public func removeAllPendingNotificationRequests() {}
    public weak var delegate: (any UNUserNotificationCenterDelegate)?
}
