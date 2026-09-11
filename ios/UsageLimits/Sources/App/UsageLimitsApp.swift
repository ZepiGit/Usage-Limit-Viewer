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
        // Before launch finishes, for the same reason as the background registration: the
        // system delivers a launch tap to whoever is the delegate AT THAT MOMENT, so installing
        // it later means the first notification a user acts on is the one that does not route.
        NotificationRouter.shared.install()
    }

    /// Whether the process was started by the UI test bundle.
    ///
    /// One flag, read in one place. It suppresses a system permission prompt and nothing else —
    /// it must never change what the app computes or displays, or the tests would be checking a
    /// different app from the one that ships.
    static var isUITesting: Bool {
        ProcessInfo.processInfo.arguments.contains("-ui-testing")
    }

    /// Which tab is showing. Lifted out of `TabView`'s own state so a widget tap can select
    /// one: a deep link that opened the app on whatever tab was last used would make the
    /// tile's destination a coin toss.
    @State private var tab: RootView.Tab = .overview

    var body: some Scene {
        WindowGroup {
            RootView(tab: $tab)
                .environmentObject(store)
                // Six failure paths in the store wrote `lastError` and NOTHING read it. A spend
                // the provider refused dismissed its dialog, left the credit count unchanged and
                // said nothing — indistinguishable from a button that did nothing at all. A
                // failed disconnect left the row in place with no explanation, and a build with
                // no App Group entitlement showed a permanently empty account list while the
                // sentence explaining why sat in a property no view had ever read.
                //
                // One alert at the root rather than one per screen: these are failures of the
                // whole run, they are raised from tabs the user may have left by the time the
                // call returns, and a per-screen alert would miss exactly those.
                .alert(
                    "Something went wrong",
                    isPresented: Binding(
                        get: { store.lastError != nil },
                        set: { if !$0 { store.clearError() } })
                ) {
                    Button("OK", role: .cancel) { store.clearError() }
                } message: {
                    Text(verbatim: store.lastError ?? "")
                }
                // The widget's links. The scheme is declared in `CFBundleURLTypes`; without
                // that declaration the system cannot route these at all and every tap on a
                // tile does nothing, which is what it did before this existed.
                .onOpenURL { url in
                    Task { await handle(url) }
                }
                // A tapped alert names one account, so it lands on the screen where that
                // account's card, its error and its actions are.
                .task {
                    NotificationRouter.shared.onAccountTapped = { _ in tab = .accounts }
                }
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

    /// Answers one widget link.
    ///
    /// Both destinations land on the overview, which is the screen the tiles summarise; the
    /// refresh link additionally fetches. The fetch happens HERE rather than in the extension
    /// because the app process is the only one that can read the keychain — which is the
    /// arrangement that keeps tokens out of widget state, not an accident of layering.
    ///
    /// An unrecognised URL is ignored rather than treated as a refresh: a link this build does
    /// not know is a link from a future one, and guessing at its meaning is how a tap starts
    /// doing something the user did not ask for.
    private func handle(_ url: URL) async {
        guard url.scheme == "usagelimits" else { return }
        switch url.host {
        case "refresh":
            tab = .overview
            await store.refresh()
        case "glance":
            tab = .overview
        default:
            break
        }
    }
}

/// The four destinations from the reference design.
///
/// A `TabView` rather than a custom bar: iOS already adapts the tab bar for larger widths, for
/// Dynamic Type and for VoiceOver, and a hand-rolled bar would have to re-earn all three.
struct RootView: View {

    enum Tab: Hashable { case overview, accounts, resets, settings }

    @Binding var tab: Tab

    var body: some View {
        TabView(selection: $tab) {
            OverviewScreen()
                .tabItem { Label("Overview", systemImage: "gauge.with.dots.needle.33percent") }
                .tag(Tab.overview)
            AccountsScreen()
                .tabItem { Label("Accounts", systemImage: "person.2") }
                .tag(Tab.accounts)
            ResetsScreen()
                .tabItem { Label("Resets", systemImage: "clock.arrow.circlepath") }
                .tag(Tab.resets)
            SettingsScreen()
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .tint(UsageColors.terracotta)
    }
}
