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
                .task { await store.refresh() }
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
