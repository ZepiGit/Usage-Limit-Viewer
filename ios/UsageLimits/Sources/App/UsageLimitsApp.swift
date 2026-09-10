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
                    _ = await NotificationScheduler.requestAuthorization()
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
