import XCTest
@testable import UsageLimitsKit

final class WidgetPresetStoreTests: XCTestCase {
    func testIndependentSelectionsAndOrderSurviveAReopen() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("widgets.json")
        let presets = [WidgetPreset(id: "a", name: "Work", accountIDs: ["third", "first"]),
            WidgetPreset(id: "b", name: "Personal", accountIDs: ["second"])]
        try await WidgetPresetStore(fileURL: url).save(presets)
        let restored = try await WidgetPresetStore(fileURL: url).load()
        XCTAssertEqual(restored, presets)
    }

    func testUnreadablePresetFileIsNotTreatedAsAnotherSelection() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        try Data("broken".utf8).write(to: url)
        do {
            _ = try await WidgetPresetStore(fileURL: url).load()
            XCTFail("Corrupt configuration must not silently choose other accounts")
        } catch { }
    }
}
