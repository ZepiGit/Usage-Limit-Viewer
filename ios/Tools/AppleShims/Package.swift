// swift-tools-version: 5.9
import PackageDescription

// One module per Apple framework the app imports, because `-module-alias` refuses to point
// two names at the same module. Each is a thin shell over `AppleShims`, which holds the
// shared vocabulary — `View`, the property wrappers, the primitives — plus whatever types
// belong to that framework alone.
let frameworks = [
    "SwiftUIShim", "WidgetKitShim", "UIKitShim", "AuthenticationServicesShim",
    "BackgroundTasksShim", "UserNotificationsShim", "CombineShim",
    "SecurityShim", "NetworkShim",
]

let package = Package(
    name: "AppleShims",
    platforms: [.macOS(.v13)],
    products: [.library(name: "AppleShimsAll", targets: ["AppleShims"] + frameworks)],
    targets: [.target(name: "AppleShims")]
        + frameworks.map { name in
        // AuthenticationServices vends `ASPresentationAnchor`, which on iOS IS `UIWindow`,
        // so its shim has to see the UIKit one or the app's anchor code cannot type-check.
        .target(
            name: name,
            dependencies: ["AppleShims"] + (name == "AuthenticationServicesShim" ? ["UIKitShim"] : []))
    }
)
