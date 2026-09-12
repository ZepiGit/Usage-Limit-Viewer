import XCTest

/// Runs the production SwiftUI screens with normalized, synthetic quota records.
final class MarketingScreenshotTests: XCTestCase {
    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    func testCaptureIPhoneScreens() {
        let app = launchDemo()
        capture("iphone-overview", in: app)
        for screen in ["Accounts", "Resets", "Settings"] {
            let matches = app.buttons.matching(identifier: screen)
            let tab = matches.allElementsBoundByIndex.first { $0.isHittable } ?? matches.firstMatch
            XCTAssertTrue(tab.waitForExistence(timeout: 10))
            tab.tap()
            XCTAssertTrue(app.navigationBars[screen].waitForExistence(timeout: 10))
            capture("iphone-\(screen.lowercased())", in: app)
        }
    }

    func testCaptureIPadOverview() {
        let app = launchDemo()
        capture("ipad-overview", in: app)
    }

    private func launchDemo() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += [
            "-ui-testing", "-marketing-demo",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL"
        ]
        app.launch()
        XCTAssertTrue(app.navigationBars["Overview"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["3/5"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["marketing-demo-label"].firstMatch.exists)
        XCTAssertEqual(app.alerts.count, 0)
        return app
    }

    private func capture(_ name: String, in app: XCUIApplication) {
        XCTAssertTrue(app.staticTexts["marketing-demo-label"].firstMatch.exists)
        XCTAssertEqual(app.alerts.count, 0)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
