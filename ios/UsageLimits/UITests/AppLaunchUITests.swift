import XCTest

/// Does the app open?
///
/// Everything else in this repository is verified without ever starting the app: the kit's tests
/// run on Linux, and the macOS job compiled the SwiftUI and WidgetKit code without executing a
/// line of it. Compiling proves the types line up. It does not prove the app launches, that the
/// tab bar appears, or that the first screen renders rather than trapping on the first optional —
/// and those are the failures a user meets before any of the others.
///
/// This is the only test in the project that runs the real binary, on a real simulator, through
/// the real launch path.
final class AppLaunchUITests: XCTestCase {

    override func setUp() {
        super.setUp()
        // A failing assertion should stop the test at the point of failure rather than carrying
        // on and reporting a cascade of consequences.
        continueAfterFailure = false
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        // Read by the app to skip the notification-permission prompt, which is a system alert
        // that would otherwise sit over the UI and fail every query behind it.
        app.launchArguments += ["-ui-testing"]
        app.launch()
        return app
    }

    func testTheAppLaunches() {
        let app = launch()

        XCTAssertEqual(app.state, .runningForeground, "the app should still be running")
    }

    func testAllFourDestinationsExist() {
        // The shell from the reference design. If navigation failed to build, the tab bar is the
        // first thing missing, and every other query would fail for a reason that reads as
        // unrelated.
        let app = launch()

        for tab in ["Overview", "Accounts", "Resets", "Settings"] {
            XCTAssertTrue(
                app.buttons[tab].waitForExistence(timeout: 10),
                "the \(tab) tab should be reachable")
        }
    }

    func testEachScreenOpensWithNoAccountsConnected() {
        // The state every user sees first, on a fresh install with nothing signed in — and the
        // one most likely to divide by a count of zero or unwrap an empty list.
        let app = launch()

        for tab in ["Accounts", "Resets", "Settings", "Overview"] {
            let button = app.buttons[tab]
            XCTAssertTrue(button.waitForExistence(timeout: 10), "\(tab) should exist")
            button.tap()
            XCTAssertEqual(
                app.state, .runningForeground, "the app should survive opening \(tab)")
        }
    }

    func testTheAddAccountSheetOffersEveryProvider() {
        // All four can be signed into: two by device code, two by a loopback redirect the app
        // receives itself. The sheet must open and offer each of them rather than dead-end.
        let app = launch()

        let accounts = app.buttons["Accounts"]
        XCTAssertTrue(accounts.waitForExistence(timeout: 10))
        accounts.tap()

        let add = app.staticTexts["+ Add account"]
        XCTAssertTrue(add.waitForExistence(timeout: 10), "the add-account card should be there")
        add.tap()

        XCTAssertTrue(
            app.navigationBars["Add account"].waitForExistence(timeout: 10),
            "the add-account sheet should open")

        for provider in ["OpenAI Codex", "Claude", "Antigravity", "Grok"] {
            XCTAssertTrue(
                app.staticTexts[provider].waitForExistence(timeout: 5),
                "\(provider) should be offered")
        }
    }
}
