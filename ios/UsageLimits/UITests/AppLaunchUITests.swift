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
        XCUIDevice.shared.orientation = .portrait
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    /// Swipes until the element materialises, or gives up rather than swiping for ever.
    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        let identifiers = ["provider-icons-list", "provider-picker-scroll", "settings-form", "accounts-scroll"]
        let container = identifiers.map { app.descendants(matching: .any)[$0].firstMatch }
            .first { $0.exists && $0.isHittable } ?? app
        for _ in 0..<12 {
            if element.isHittable { return true }
            let frame = container.frame.intersection(app.frame)
            let reverse = element.exists && element.frame.maxY < frame.minY + 60
            // The failed movie shows a drag over Claude activating the card. Use the
            // visible gutter before the first card, inside the landscape safe area.
            let firstButton = container.buttons.firstMatch
            let gutterX = firstButton.exists ? max(frame.minX + 4, firstButton.frame.minX - 8) : frame.minX + 16
            let x = (gutterX - container.frame.minX) / container.frame.width
            let start = container.coordinate(withNormalizedOffset: CGVector(dx: x, dy: reverse ? 0.3 : 0.75))
            let end = container.coordinate(withNormalizedOffset: CGVector(dx: x, dy: reverse ? 0.75 : 0.3))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        return element.waitForExistence(timeout: 2) && element.isHittable
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        // Read by the app to skip the notification-permission prompt, which is a system alert
        // that would otherwise sit over the UI and fail every query behind it.
        app.launchArguments += ["-ui-testing", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL"]
        app.launch()
        waitForOrientation(app, landscape: false)
        return app
    }

    private func waitForOrientation(_ app: XCUIApplication, landscape: Bool) {
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            landscape ? app.frame.width > app.frame.height : app.frame.height > app.frame.width
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 10), .completed)
    }

    private func tabButton(_ label: String, in app: XCUIApplication) -> XCUIElement {
        // iPadOS exposes the floating tab cell and its child with the same label.
        // Select the hittable match, then assert the destination after tapping it.
        let matches = app.buttons.matching(identifier: label)
        return matches.allElementsBoundByIndex.first { $0.isHittable } ?? matches.firstMatch
    }

    func testTheAppLaunches() {
        let app = launch()

        XCTAssertEqual(app.state, .runningForeground, "the app should still be running")
    }

    func testProviderIconSelectionPersists() {
        var app = launch()
        tabButton("Settings", in: app).tap()
        XCTAssertTrue(scrollTo(app.buttons["Provider icons"], in: app))
        app.buttons["Provider icons"].tap()
        let alternative = app.buttons["Claude · Color"]
        XCTAssertTrue(scrollTo(alternative, in: app))
        alternative.tap()
        XCTAssertEqual(alternative.value as? String, "Selected")
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Provider icon choice"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
        app = launch()
        tabButton("Settings", in: app).tap()
        XCTAssertTrue(scrollTo(app.buttons["Provider icons"], in: app))
        app.buttons["Provider icons"].tap()
        let restored = app.buttons["Claude · Color"]
        XCTAssertTrue(scrollTo(restored, in: app))
        XCTAssertEqual(restored.value as? String, "Selected")
        let defaultIcon = app.buttons["Claude Code · Color"]
        XCTAssertTrue(scrollTo(defaultIcon, in: app))
        defaultIcon.tap()
    }

    func testAllFourDestinationsExist() {
        // The shell from the reference design. If navigation failed to build, the tab bar is the
        // first thing missing, and every other query would fail for a reason that reads as
        // unrelated.
        let app = launch()

        for tab in ["Overview", "Accounts", "Resets", "Settings"] {
            XCTAssertTrue(
                tabButton(tab, in: app).waitForExistence(timeout: 10),
                "the \(tab) tab should be reachable")
        }
    }

    func testEachScreenOpensWithNoAccountsConnected() {
        // The state every user sees first, on a fresh install with nothing signed in — and the
        // one most likely to divide by a count of zero or unwrap an empty list.
        let app = launch()

        for tab in ["Accounts", "Resets", "Settings", "Overview"] {
            let button = tabButton(tab, in: app)
            XCTAssertTrue(button.waitForExistence(timeout: 10), "\(tab) should exist")
            button.tap()
            XCTAssertTrue(app.navigationBars[tab].waitForExistence(timeout: 10))
            XCTAssertEqual(
                app.state, .runningForeground, "the app should survive opening \(tab)")
        }
    }

    func testTheSettingsScreenOffersTheDisplaySwitches() {
        // Two preferences that decide what the overview prints, and the only part of this
        // feature a simulator can check: the drag handle needs two accounts to appear and the
        // widget gallery is out of an XCUITest's reach, but a switch that failed to build is
        // visible here.
        let app = launch()

        let settings = tabButton("Settings", in: app)
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()

        for label in ["Show subscription tier", "Show renewal time"] {
            // Scrolled to rather than merely waited for. A Form is a lazy list, so a row below
            // the fold is absent from the hierarchy rather than present and off-screen — and
            // the Display section sits under six notification switches, which is below the fold
            // on the phone this runs on.
            XCTAssertTrue(scrollTo(app.switches[label], in: app), "\(label) should be offered")
        }
    }

    func testTheAddAccountSheetOffersEveryProvider() {
        // All five can be signed into: two by device code, two by a loopback redirect the app
        // receives itself, and one with a key the user pastes. The sheet must open and offer
        // each of them rather than dead-end.
        let app = launch()

        let accounts = tabButton("Accounts", in: app)
        XCTAssertTrue(accounts.waitForExistence(timeout: 10))
        accounts.tap()

        let add = app.staticTexts["+ Add account"]
        XCTAssertTrue(add.waitForExistence(timeout: 10), "the add-account card should be there")
        add.tap()

        XCTAssertTrue(
            app.navigationBars["Add account"].waitForExistence(timeout: 10),
            "the add-account sheet should open")

        for provider in ["OpenAI Codex", "Claude", "Antigravity", "Grok", "Kimi"] {
            XCTAssertTrue(
                scrollTo(app.staticTexts[provider], in: app),
                "\(provider) should be offered")
        }
    }

    func testLandscapeAndLargeTextKeepTheProviderPickerReachable() {
        let app = XCUIApplication()
        app.launchArguments += ["-ui-testing", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryXXXL"]
        app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForOrientation(app, landscape: true)
        defer { XCUIDevice.shared.orientation = .portrait }
        let accounts = tabButton("Accounts", in: app)
        XCTAssertTrue(accounts.waitForExistence(timeout: 10))
        accounts.tap()
        let add = app.staticTexts["+ Add account"]
        XCTAssertTrue(scrollTo(add, in: app))
        add.tap()
        XCTAssertTrue(app.navigationBars["Add account"].waitForExistence(timeout: 10))
        XCTAssertTrue(scrollTo(app.staticTexts["Kimi"], in: app))
        XCTAssertTrue(app.buttons["Cancel"].isHittable)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Landscape provider picker with large text"
        screenshot.lifetime = .keepAlways
        self.add(screenshot)
        app.buttons["Cancel"].tap()
        XCTAssertEqual(app.state, .runningForeground)
    }
}
