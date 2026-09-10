import SwiftUI
import UsageLimitsKit

@main
struct UsageLimitsApp: App {

    @StateObject private var store = UsageStore()

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
