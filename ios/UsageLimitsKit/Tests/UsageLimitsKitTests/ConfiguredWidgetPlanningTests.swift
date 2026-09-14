import XCTest
@testable import UsageLimitsKit

/// The planning seam the configured providers run through (WGT-008, WGT-010).
///
/// A timeline request captures its inputs once and derives every entry from that capture;
/// the outcome — including WHY a selection came up empty — is decided where the inputs are
/// resolved, not re-guessed by a view while being archived.
final class ConfiguredWidgetPlanningTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func account(_ id: String, used: Double, fetchedAt: Date? = nil) -> AccountUsage {
        let when = fetchedAt ?? now
        return AccountUsage(
            account: ProviderAccount(id: id, provider: .codex, externalAccountID: id,
                email: "\(id)@example.com", displayName: id, plan: "Plus", credentialReference: id,
                createdAt: now, lastSuccessfulSync: when),
            snapshot: UsageSnapshot(accountID: id, fetchedAt: when, status: .ok,
                windows: [UsageWindow(id: "short", label: "5h limit", category: .fiveHour,
                    usedPercent: used, periodSeconds: 18_000, resetAt: nil, exhausted: used >= 100)]))
    }

    private func loadedSnapshot() -> GlanceSnapshot {
        GlanceModel.build([account("a", used: 10), account("b", used: 50)], now: now, scope: .allAccounts)
    }

    private func capture(_ source: GlanceSnapshotCodec.Load,
        preset: ConfiguredWidgetCapture.PresetResolution = .notRead,
        scope: GlanceScope = .allAccounts, accountID: String? = nil) -> ConfiguredWidgetCapture {
        ConfiguredWidgetCapture(source: source, preset: preset, scope: scope,
            accountID: accountID, providerID: nil, transparent: false)
    }

    // MARK: WGT-008 — one capture drives many entries

    func testEntriesDeriveFromTheOneCapturedGeneration() {
        let request = capture(.loaded(loadedSnapshot()))
        let entries = stride(from: 0, through: 2700, by: 900).map {
            request.selectedSnapshot(at: now.addingTimeInterval(TimeInterval($0)))
        }
        XCTAssertEqual(entries.count, 4)
        // Same capture, same result at every date inside the freshness window — no entry can
        // carry a generation the request did not capture.
        XCTAssertTrue(entries.allSatisfy { $0 == entries[0] })
        XCTAssertEqual(entries[0].accounts.map(\.id), ["a", "b"])
    }

    func testNonCustomRequestNeverResolvesAPreset() {
        // ACCOUNT selection is exact, and a request that never consulted the preset store
        // cannot be poisoned by one.
        let request = capture(.loaded(loadedSnapshot()), scope: .account, accountID: "b")
        XCTAssertEqual(request.outcome(at: now), .ready)
        XCTAssertEqual(request.selectedSnapshot(at: now).accounts.map(\.id), ["b"])
    }

    // MARK: WGT-010 — an empty selection keeps its reason

    func testMissingPresetIsNotNoAccounts() {
        // The preset file failed to resolve while other accounts exist. The old behaviour
        // reported "No accounts yet" for this — a sentence that sent the user to add
        // accounts they already have.
        let request = capture(.loaded(loadedSnapshot()), preset: .missing, scope: .custom)
        XCTAssertEqual(request.outcome(at: now), .missingPreset)
    }

    func testMissingAccountIsNotNoAccounts() {
        // A preset whose accounts all vanished, and a direct account selection that no
        // longer resolves, both mean "edit this widget" — not "no accounts yet".
        let vanished = capture(.loaded(loadedSnapshot()), preset: .matched(accountIDs: ["ghost"]), scope: .custom)
        XCTAssertEqual(vanished.outcome(at: now), .missingAccount)
        let direct = capture(.loaded(loadedSnapshot()), scope: .account, accountID: "ghost")
        XCTAssertEqual(direct.outcome(at: now), .missingAccount)
    }

    func testSourceFailureStatesKeepTheirReasons() {
        // A missing file is "no accounts yet" — the app has not published anything, and it
        // is NOT a locked device (inferring lock from file presence was the old bug).
        XCTAssertEqual(capture(.missing).outcome(at: now), .noAccounts)
        XCTAssertEqual(capture(.empty).outcome(at: now), .noAccounts)
        // An existing file that cannot be opened is the protected/unreadable case.
        XCTAssertEqual(capture(.unreadable).outcome(at: now), .sourceUnavailable)
        XCTAssertEqual(capture(.corrupt).outcome(at: now), .corruptSource)
    }

    // MARK: WGT-005 — age transitions are planned from the captured cache

    func testStalenessBoundaryIsPlannedFromTheCapture() {
        let fetchedEarlier = GlanceModel.build(
            [account("a", used: 10, fetchedAt: now.addingTimeInterval(-40 * 60))], now: now, scope: .allAccounts)
        let request = capture(.loaded(fetchedEarlier))
        let boundaries = request.presentationBoundaries(after: now)
        XCTAssertEqual(boundaries.count, 1)
        XCTAssertEqual(boundaries[0], now.addingTimeInterval(20 * 60))
    }

    func testAlreadyStaleAccountPlansNoBoundary() {
        let longAgo = GlanceModel.build(
            [account("a", used: 10, fetchedAt: now.addingTimeInterval(-5 * 3600))], now: now, scope: .allAccounts)
        XCTAssertTrue(capture(.loaded(longAgo)).presentationBoundaries(after: now).isEmpty)
    }

    // MARK: WGT-007 — a reading that predates its own reset

    func testResetPendingDistinguishesPreResetReadingsFromFreshOnes() {
        let row = GlanceRow(label: "5h limit", category: .fiveHour, remainingPercent: 0,
            resetAt: now, severity: .exhausted)

        // The snapshot predates the reset: the reading is invalidated, pending fresh data.
        XCTAssertTrue(row.resetPending(at: now, fetchedAt: now.addingTimeInterval(-100)))
        // A post-reset fetch confirmed the state: nothing pending.
        XCTAssertFalse(row.resetPending(at: now, fetchedAt: now.addingTimeInterval(100)))
        // No fetch instant known: a passed reset on a cached reading can only be pending.
        XCTAssertTrue(row.resetPending(at: now, fetchedAt: nil))
        // Future reset, or no reset at all: never pending.
        XCTAssertFalse(row.resetPending(at: now.addingTimeInterval(-100), fetchedAt: now.addingTimeInterval(-200)))
        let noReset = GlanceRow(label: "5h limit", category: .fiveHour, remainingPercent: 50,
            resetAt: nil, severity: .healthy)
        XCTAssertFalse(noReset.resetPending(at: now, fetchedAt: now.addingTimeInterval(-100)))
    }
}
