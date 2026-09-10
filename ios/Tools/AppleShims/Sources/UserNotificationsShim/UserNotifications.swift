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
    public init(identifier: String, content: UNMutableNotificationContent, trigger: Any?) {}
}

public struct UNAuthorizationOptions: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public static let alert = UNAuthorizationOptions(rawValue: 1)
    public static let sound = UNAuthorizationOptions(rawValue: 2)
    public static let badge = UNAuthorizationOptions(rawValue: 4)
}

public final class UNUserNotificationCenter {
    public static func current() -> UNUserNotificationCenter { UNUserNotificationCenter() }
    public func requestAuthorization(options: UNAuthorizationOptions) async throws -> Bool { true }
    public func add(_ request: UNNotificationRequest) async throws {}
    public func removeAllPendingNotificationRequests() {}
}
