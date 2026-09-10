import SwiftUI
import UsageLimitsKit

@main
struct UsageLimitsApp: App {

    @StateObject private var store = UsageStore()

    init() {
        // Registration must happen before launch finishes; later is a programmer error the
        // system traps rather than reports, and the app then never refreshes in the background
        // again. The store's own container is handed over rather than a second one: two
        // containers mean two credential stores and two sets of in-flight refresh locks, and
        // that lock exists precisely so one rotating refresh token cannot be spent twice.
        if let container = UsageStore.sharedContainer {
            BackgroundRefresh.register(container: container)
        }
    }

    /// Whether the process was started by the UI test bundle.
    ///
    /// One flag, read in one place. It suppresses a system permission prompt and nothing else —
    /// it must never change what the app computes or displays, or the tests would be checking a
    /// different app from the one that ships.
    static var isUITesting: Bool {
        ProcessInfo.processInfo.arguments.contains("-ui-testing")
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
                // Cached first, then the network. A cold launch that waited for the slowest
                // provider before drawing anything would read as "you have no accounts" for as
                // long as that took — which on a bad connection is indefinitely.
                .task {
                    await store.load()
                    // Asked once, and only after the first screen is up: a permission sheet in
                    // front of an empty app asks the user to approve something they have not
                    // seen the point of yet.
                    //
                    // Never asked under UI test. The permission prompt is a SYSTEM alert, which
                    // sits above the app's own window and fails every query behind it — so a
                    // test written to check the tab bar would fail on the alert instead, for a
                    // reason that reads as unrelated to what it was checking.
                    if !Self.isUITesting {
                        _ = await NotificationScheduler.requestAuthorization()
                    }
                    await store.refresh()
                }
        }
    }
}

/// The four destinations from the reference design.
///
/// A `TabView` rather than a custom bar: iOS already adapts the tab bar for larger widths, for
/// Dynamic Type and for VoiceOver, and a hand-rolled bar would have to re-earn all three.
struct RootView: View {

    var body: some View {
        TabView {
            OverviewScreen()
                .tabItem { Label("Overview", systemImage: "gauge.with.dots.needle.33percent") }
            AccountsScreen()
                .tabItem { Label("Accounts", systemImage: "person.2") }
            ResetsScreen()
                .tabItem { Label("Resets", systemImage: "clock.arrow.circlepath") }
            SettingsScreen()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .tint(UsageColors.terracotta)
    }
}
