@_exported import AppleShims
import Foundation

public class BGTaskRequest {
    public var earliestBeginDate: Date?
}

public final class BGAppRefreshTaskRequest: BGTaskRequest {
    public init(identifier: String) {}
}

public class BGTask {
    public var expirationHandler: (() -> Void)?
    public func setTaskCompleted(success: Bool) {}
}

public final class BGAppRefreshTask: BGTask {}

public final class BGTaskScheduler {
    public static let shared = BGTaskScheduler()
    public func register(forTaskWithIdentifier identifier: String, using queue: Any?,
                         launchHandler: @escaping (BGTask) -> Void) -> Bool { true }
    public func submit(_ request: BGTaskRequest) throws {}
    public func cancelAllTaskRequests() {}
}
