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
        verifyCustomLayout(in: app)
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
        XCTAssertTrue(app.staticTexts["5/5 connected"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["marketing-demo-label"].firstMatch.exists)
        XCTAssertEqual(app.alerts.count, 0)
        return app
    }

    private func reach(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 {
            if element.exists && element.isHittable { return }
            app.swipeUp()
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }

    private func verifyCustomLayout(in app: XCUIApplication) {
        let layouts = app.buttons["Widget layouts"]
        reach(layouts, in: app); layouts.tap()
        app.buttons["New custom layout"].tap()
        let name = app.textFields["Name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10)); name.tap()
        if let value = name.value as? String { name.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: value.count)) }
        name.typeText("QA layout")
        app.buttons["Show OpenAI Codex"].tap()
        app.buttons["Show Claude"].tap()
        let handles = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Reorder")).allElementsBoundByIndex.filter { $0.isHittable }
        XCTAssertGreaterThanOrEqual(handles.count, 2)
        handles[1].press(forDuration: 0.3, thenDragTo: handles[0])
        capture("iphone-custom-layout", in: app, requiresDemoLabel: false)
        app.buttons["Save"].tap()
        XCTAssertTrue(app.navigationBars["Widget layouts"].waitForExistence(timeout: 10))
        app.terminate()
        let restored = launchDemo()
        restored.buttons.matching(identifier: "Settings").allElementsBoundByIndex.first { $0.isHittable }!.tap()
        let savedLayouts = restored.buttons["Widget layouts"]
        reach(savedLayouts, in: restored); savedLayouts.tap()
        let preset = restored.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "QA layout")).firstMatch
        XCTAssertTrue(preset.waitForExistence(timeout: 10)); preset.tap()
        XCTAssertEqual(restored.buttons["Show Claude"].value as? String, "Selected")
        XCTAssertEqual(restored.buttons["Show OpenAI Codex"].value as? String, "Selected")
        XCTAssertLessThan(restored.buttons["Show Claude"].frame.minY, restored.buttons["Show OpenAI Codex"].frame.minY)
        capture("iphone-custom-layout-restored", in: restored, requiresDemoLabel: false)
    }

    private func capture(_ name: String, in app: XCUIApplication, requiresDemoLabel: Bool = true) {
        if requiresDemoLabel { XCTAssertTrue(app.staticTexts["marketing-demo-label"].firstMatch.exists) }
        XCTAssertEqual(app.alerts.count, 0)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
