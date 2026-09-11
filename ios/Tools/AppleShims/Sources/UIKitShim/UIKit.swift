@_exported import AppleShims
import Foundation

open class UIResponder {
    public init() {}
}

open class UIWindow: UIResponder {}

public enum UISceneActivationState {
    case foregroundActive, foregroundInactive, background, unattached
}

/// `Hashable`, because the real `connectedScenes` is a `Set<UIScene>` and the app filters it
/// with `compactMap { $0 as? UIWindowScene }`. Modelling it as a set of some other element
/// type made that cast "always fails", which is a warning about the shim and not the app.
open class UIScene: UIResponder, Hashable {
    public var activationState: UISceneActivationState { .foregroundActive }
    public static func == (a: UIScene, b: UIScene) -> Bool { a === b }
    public func hash(into hasher: inout Hasher) { hasher.combine(ObjectIdentifier(self)) }
}

open class UIWindowScene: UIScene {
    public var windows: [UIWindow] { [] }
    public var keyWindow: UIWindow? { nil }
}

public final class UIApplication {
    public static let shared = UIApplication()
    public var connectedScenes: Set<UIScene> { [] }
}

/// The clipboard, for the device-code sign-in.
///
/// A device code has to travel from this app to a browser on the same phone, and reading it off
/// the screen to retype it is where that flow was breaking down. SwiftUI has no clipboard of its
/// own on iOS, so the copy goes through UIKit and therefore has to exist here too.
public final class UIPasteboard {
    public static let general = UIPasteboard()
    public var string: String?
}
