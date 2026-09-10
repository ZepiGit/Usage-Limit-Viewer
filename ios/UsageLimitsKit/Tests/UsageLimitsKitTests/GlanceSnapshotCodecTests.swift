import XCTest
@testable import UsageLimitsKit

/// The contract between the app process and the widget process.
///
/// Neither can see the other's memory, so this file is the whole of what the home screen knows.
/// A mismatch does not raise anything — the widget simply renders empty — which is why the
/// round trip is pinned here rather than discovered on a device.
final class GlanceSnapshotCodecTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func snapshot() -> GlanceSnapshot {
        let short = GlanceRow(
            label: "5h limit", category: .fiveHour, remainingPercent: 12.5,
            resetAt: now.addingTimeInterval(3_600), severity: .low)
        let long = GlanceRow(
            label: "Weekly", category: .weekly, remainingPercent: nil,
            resetAt: nil, severity: .error)

        return GlanceSnapshot(
            accounts: [
                GlanceAccount(
                    id: "a", title: "OpenAI Codex Plus", subtitle: "a***@example.com",
                    rows: [short, long], severity: .low),
            ],
            accountCount: 1,
            updatedAt: now,
            nextResetAt: now.addingTimeInterval(3_600),
            overallSeverity: .error,
            headlineShort: short,
            headlineLong: long)
    }

    func testTheRoundTripPreservesEverything() throws {
        let original = snapshot()

        let decoded = GlanceSnapshotCodec.decode(try GlanceSnapshotCodec.encode(original))

        XCTAssertEqual(decoded, original)
    }

    func testAnUnknownPercentageSurvivesAsUnknown() throws {
        // The one value that must not be normalised in transit. A nil that arrives as 0 tells
        // the user a limit is spent; a nil that arrives as 100 tells them it is untouched.
        let decoded = GlanceSnapshotCodec.decode(try GlanceSnapshotCodec.encode(snapshot()))

        XCTAssertNil(decoded.headlineLong?.remainingPercent ?? nil)
    }

    func testTheOverallSeverityIsCarriedRatherThanRecomputed() throws {
        // The defect this codec replaced: the widget re-derived this from the accounts it had
        // decoded, and derived it differently from GlanceModel — so the same data produced one
        // verdict in the app and another on the home screen. Here the account is `.low` while
        // the snapshot as a whole is `.error`, which only survives if it is carried.
        let decoded = GlanceSnapshotCodec.decode(try GlanceSnapshotCodec.encode(snapshot()))

        XCTAssertEqual(decoded.overallSeverity, Severity.error)
        XCTAssertEqual(decoded.accounts.first?.severity, Severity.low)
    }

    func testAMissingFileIsEmptyRatherThanAnAllClear() {
        // A widget cannot ask for a retry, so every failure has the same available outcome. It
        // must be the pessimistic one: empty renders as stale, never as "everything is fine".
        XCTAssertEqual(GlanceSnapshotCodec.decode(nil), .empty)
        XCTAssertEqual(GlanceSnapshotCodec.decode(Data()), .empty)
        XCTAssertEqual(GlanceSnapshotCodec.empty(from: "not json at all"), .empty)
        XCTAssertEqual(GlanceSnapshot.empty.overallSeverity, Severity.stale)
    }

    func testAHalfWrittenFileDecodesToEmptyRatherThanToNonsense() throws {
        // The widget can be reading while the app writes, which is why the write is atomic —
        // but a truncated file must still be survivable rather than fatal.
        let complete = try GlanceSnapshotCodec.encode(snapshot())
        let truncated = complete.prefix(complete.count / 2)

        XCTAssertEqual(GlanceSnapshotCodec.decode(Data(truncated)), .empty)
    }

    func testDatesAreReadableInTheFile() throws {
        // This file is the first thing anyone opens when a widget shows the wrong time, and the
        // default strategy writes a floating-point count of seconds since 2001.
        let json = String(decoding: try GlanceSnapshotCodec.encode(snapshot()), as: UTF8.self)

        XCTAssertTrue(json.contains("2025-") || json.contains("2026-"), json.prefix(200).description)
    }

    func testWritingThenReadingADirectoryRoundTrips() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("glance-codec-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        try GlanceSnapshotCodec.write(snapshot(), toDirectory: directory)

        XCTAssertEqual(GlanceSnapshotCodec.read(fromDirectory: directory), snapshot())
    }

    func testReadingADirectoryWithNoSnapshotIsEmpty() {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("glance-codec-missing-\(UUID().uuidString)")

        XCTAssertEqual(GlanceSnapshotCodec.read(fromDirectory: directory), .empty)
    }

    // MARK: - Which nothing it is

    private func scratchDirectory() throws -> URL {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("glance-load-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    func testAReadableEmptySnapshotIsEmptyAndNotLocked() throws {
        // The regression this whole enum exists for. A tile that had only "does the file
        // exist" to go on called a perfectly readable empty snapshot a locked device, and
        // told the user to unlock a phone that was already unlocked. An empty snapshot is
        // the ORDINARY state as soon as the app refreshes with no accounts connected.
        let directory = try scratchDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try GlanceSnapshotCodec.write(.empty, toDirectory: directory)

        XCTAssertEqual(GlanceSnapshotCodec.load(fromDirectory: directory), .empty)
    }

    func testAMissingFileIsMissingRatherThanLocked() throws {
        let directory = try scratchDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }

        XCTAssertEqual(GlanceSnapshotCodec.load(fromDirectory: directory), .missing)
    }

    func testAFileWithAccountsLoads() throws {
        let directory = try scratchDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try GlanceSnapshotCodec.write(snapshot(), toDirectory: directory)

        XCTAssertEqual(GlanceSnapshotCodec.load(fromDirectory: directory), .loaded(snapshot()))
    }

    func testUndecodableContentIsCorruptRatherThanEmpty() throws {
        // A build whose format this one does not understand, or a truncated write. Reporting
        // it as "no accounts yet" invites the user to add accounts they already have.
        let directory = try scratchDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data("{\"nope\":1}".utf8).write(
            to: directory.appendingPathComponent(GlanceSnapshotCodec.fileName))

        XCTAssertEqual(GlanceSnapshotCodec.load(fromDirectory: directory), .corrupt)
    }

    func testAZeroByteFileIsCorruptRatherThanEmpty() throws {
        // The atomic writer caught mid-replace. Zero bytes is not a decodable empty snapshot,
        // and calling it one would render an all-clear built from nothing.
        let directory = try scratchDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data().write(to: directory.appendingPathComponent(GlanceSnapshotCodec.fileName))

        XCTAssertEqual(GlanceSnapshotCodec.load(fromDirectory: directory), .corrupt)
    }

    func testAnUnopenableFileIsUnreadableRatherThanMissing() throws {
        // The only case that unlocking actually fixes. A directory standing where the file
        // should be reproduces "exists, cannot be read as data" without needing a device.
        let directory = try scratchDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(
            at: directory.appendingPathComponent(GlanceSnapshotCodec.fileName),
            withIntermediateDirectories: true)

        XCTAssertEqual(GlanceSnapshotCodec.load(fromDirectory: directory), .unreadable)
    }

    func testEveryNonLoadedOutcomeStillRendersTheEmptySnapshot() throws {
        // Whatever went wrong, a widget must still have something to draw, and it must never
        // be an all-clear: `.empty` carries `.stale`.
        for outcome in [GlanceSnapshotCodec.Load.empty, .missing, .unreadable, .corrupt] {
            XCTAssertEqual(outcome.snapshot, .empty)
            XCTAssertEqual(outcome.snapshot.overallSeverity, .stale)
        }
    }
}

private extension GlanceSnapshotCodec {
    /// Test convenience: decode a string body.
    static func empty(from body: String) -> GlanceSnapshot {
        decode(Data(body.utf8))
    }
}
