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

    func testEachRequestSeesTheLatestPresetRevision() async throws {
        // A widget request captures its inputs, but the capture must never outlive the
        // request: the NEXT request reloads the store and adopts the edited preset. Two
        // loads with a rewrite in between have to disagree — freezing across requests was
        // the risk the capture mechanism was designed against.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let store = WidgetPresetStore(fileURL: url)

        try await store.save([WidgetPreset(id: "layout", name: "Work", accountIDs: ["one"])])
        let first = try await store.load()
        XCTAssertEqual(first.first?.accountIDs, ["one"])

        try await store.save([WidgetPreset(id: "layout", name: "Work", accountIDs: ["two"])])
        let second = try await store.load()
        XCTAssertEqual(second.first?.accountIDs, ["two"])
    }
}
