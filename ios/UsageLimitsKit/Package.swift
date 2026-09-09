// swift-tools-version: 5.9
import PackageDescription

// Everything in this package depends on Foundation and nothing else — no SwiftUI, no UIKit,
// no WidgetKit, no Security. That is what lets the whole quota engine be built and tested on
// Linux, where the Apple SDKs do not exist, and it is where the Android port proved the bugs
// actually live: every real defect found there was in a parser, not in a view.
//
// The iOS app target depends on this package and adds only the platform layer that genuinely
// needs Apple frameworks: Keychain, ASWebAuthenticationSession, SwiftUI, WidgetKit.
let package = Package(
    name: "UsageLimitsKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "UsageLimitsKit", targets: ["UsageLimitsKit"]),
    ],
    targets: [
        .target(name: "UsageLimitsKit"),
        .testTarget(name: "UsageLimitsKitTests", dependencies: ["UsageLimitsKit"]),
    ]
)
