@_exported import AppleShims
import Foundation

@_exported import UIKitShim

/// On iOS this is exactly `UIWindow`, and saying so is what lets the app's
/// `presentationAnchor(for:)` return a window the way the real API requires.
public typealias ASPresentationAnchor = UIWindow

public struct ASWebAuthenticationSessionError: Error {
    public enum Code: Int, Sendable {
        case canceledLogin = 1
        case presentationContextNotProvided = 2
        case presentationContextInvalid = 3
    }
    public let code: Code
    public static let canceledLogin = Code.canceledLogin
    public init(code: Code) { self.code = code }
}

/// `@MainActor`, as the real protocol is. Without it a perfectly correct main-actor
/// conformance in the app reports an isolation warning that exists only here.
@MainActor
public protocol ASWebAuthenticationPresentationContextProviding: AnyObject {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor
}

public final class ASWebAuthenticationSession {
    public init(url: URL, callbackURLScheme: String?,
                completionHandler: @escaping (URL?, Error?) -> Void) {}
    public weak var presentationContextProvider: (any ASWebAuthenticationPresentationContextProviding)?
    public var prefersEphemeralWebBrowserSession: Bool = false
    @discardableResult public func start() -> Bool { true }
    public func cancel() {}
}
