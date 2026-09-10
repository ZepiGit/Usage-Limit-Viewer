//
//  UsageWidget.swift
//  UsageLimitsWidget
//
//  Home Screen tiles (plus a Lock Screen accessory) summarising how much quota is
//  left on the user's AI subscriptions. Every number comes from the JSON snapshot
//  the main app writes into the shared App Group container; the widget itself never
//  fetches anything.
//
//  Two invariants govern everything below:
//
//  1. A widget cannot tick. It re-renders only when WidgetKit asks it to, so
//     nothing shown is phrased relative to "now": resets are absolute clock times,
//     and the data carries its own "As of" timestamp rather than an age.
//  2. The tile may state only what the cache states. An unknown percentage is an
//     em dash over an empty bar — never a 0 % or a 100 % invented for the occasion.
//

import Foundation
import SwiftUI
import WidgetKit
import UsageLimitsKit

// MARK: - Timeline entry

/// A single renderable moment in the widget's timeline.
///
/// `date` is the moment WidgetKit will *display* this entry, not the moment it was
/// built: every absolute time in the views below is formatted against it, so an
/// entry replayed from the timeline still names times that were true at that moment.
struct UsageEntry: TimelineEntry {
    let date: Date
    let snapshot: GlanceSnapshot
}

// MARK: - Timeline provider

/// Supplies the widget's timeline from the App Group cache.
struct UsageProvider: TimelineProvider {

    /// Routine reload cadence when nothing is about to change; comfortably inside
    /// the system's daily budget, so the greedy requests that get a widget
    /// throttled are never made in the first place.
    private static let routineReloadInterval: TimeInterval = 4 * 60 * 60

    /// A cache whose reset has already passed is wrong, not merely old, so it gets
    /// a prompt retry — prompt, not instant: WidgetKit throttles greed.
    private static let overdueReloadInterval: TimeInterval = 15 * 60

    /// Grace after a reset for the app to rewrite the cache before we reload.
    private static let postResetGrace: TimeInterval = 2 * 60

    func placeholder(in context: Context) -> UsageEntry {
        // The gallery placeholder is deliberately the empty state rather than
        // plausible-looking sample numbers: a preview that invents "12 %" invites
        // the user to trust a figure no app ever wrote. It is a worse advert than
        // a fabricated one — and the only one we can defend.
        UsageEntry(date: Date(), snapshot: .empty)
    }

    func getSnapshot(in context: Context, completion: @escaping (UsageEntry) -> Void) {
        // One synchronous read of a few hundred bytes: the user is staring at the
        // tile, so a fast honest answer beats a deferred one.
        completion(UsageEntry(date: Date(), snapshot: SnapshotCache.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<UsageEntry>) -> Void) {
        let snapshot = SnapshotCache.load()
        let now = Date()
        let entries = Self.entryDates(now: now, nextResetAt: snapshot.nextResetAt)
            .map { UsageEntry(date: $0, snapshot: snapshot) }
        completion(
            Timeline(
                entries: entries,
                policy: .after(Self.refreshDate(now: now, nextResetAt: snapshot.nextResetAt))
            )
        )
    }

    // MARK: Timeline maths

    /// Timeline policy, and why it has this shape.
    ///
    /// WidgetKit hands a widget a small reload budget (tens of system-scheduled
    /// loads per day) and throttles greed: requesting a reload every minute earns
    /// *fewer* reloads, not more. Replaying entries we have already supplied is
    /// free, by contrast — WidgetKit simply re-renders the existing view at each
    /// date, without loading the extension at all. The policy therefore:
    ///
    /// 1. Bakes in a handful of entries clustered around the next reset (6 h,
    ///    1 h, 15 min, 5 min and 1 min before it, then the boundary itself) so the
    ///    tile re-renders precisely when its display can change. What it shows are
    ///    absolute clock times, which stay correct between renders; the boundary
    ///    entry exists so the "Resets …" line flips to "Reset · refresh pending"
    ///    the instant that claim becomes untrue.
    /// 2. Spends exactly one budgeted reload `.after` the reset — two minutes into
    ///    the grace window in which the app rewrites the cache — because that is
    ///    the only moment at which the cached numbers are wrong rather than merely
    ///    old.
    /// 3. Falls back to a four-hourly reload when no reset is near, picking up
    ///    whatever the app has since written, still comfortably inside budget.
    private static func entryDates(now: Date, nextResetAt reset: Date?) -> [Date] {
        var dates = [now]
        guard let reset, reset > now else { return dates }
        for lead in [6.0 * 60 * 60, 60 * 60, 15 * 60, 5 * 60, 60] {
            let date = reset.addingTimeInterval(-lead)
            // Skip lead-ins already past, or so close to `now` they would merely duplicate it.
            if date > now.addingTimeInterval(2 * 60) {
                dates.append(date)
            }
        }
        // The boundary entry: crosses the reset so the view can stop claiming one is pending.
        dates.append(reset)
        return dates
    }

    /// The single budgeted reload point; see `entryDates(now:nextResetAt:)`'s
    /// discussion for the shape of the policy.
    private static func refreshDate(now: Date, nextResetAt reset: Date?) -> Date {
        guard let reset else {
            return now.addingTimeInterval(routineReloadInterval)
        }
        if reset <= now {
            return now.addingTimeInterval(overdueReloadInterval)
        }
        // Whichever comes first: just after the reset, or the routine check.
        return min(
            reset.addingTimeInterval(postResetGrace),
            now.addingTimeInterval(routineReloadInterval)
        )
    }
}
// MARK: - Snapshot cache

/// Reads the snapshot the main app writes into the shared App Group container.
///
/// The decoding itself lives in `UsageLimitsKit`, not here. A private wire format beside its
/// only reader looks tidier, and the first version of this file had one — but it recomputed the
/// snapshot's derived members on read, using rules that had drifted from the ones the app
/// applies. The same data then produced one verdict in the app and another on the home screen,
/// and the headline rows could not be shown at all because the format did not carry them.
///
/// The snapshot is already the reduced, decided view. It travels whole.
private enum SnapshotCache {

    /// The App Group identifier shared by the app and this extension. One named constant, so
    /// the container can never be opened under two spellings.
    static let appGroupID = "group.com.usagelimits.shared"

    private static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID)
    }

    /// Never throws and never returns nil: the caller always gets something renderable.
    ///
    /// Every failure — no entitlement, a missing file, a half-written one — funnels to the empty
    /// snapshot, which renders as stale rather than as an all-clear. A widget cannot open a
    /// dialogue or ask for a retry, so the pessimistic reading is the only honest one.
    static func load() -> GlanceSnapshot {
        guard let containerURL else { return .empty }
        return GlanceSnapshotCodec.read(fromDirectory: containerURL)
    }
}

// MARK: - Criticality ranking

private extension Severity {
    /// Ranking for "what deserves the tile", most urgent first.
    ///
    /// Concrete quota pressure outranks data-quality states: an account we can see
    /// is exhausted beats one we merely failed to read. Among the quality states a
    /// failed fetch (error) outranks an ageing one (stale) — silence is louder
    /// than delay — and healthy is least urgent, so an all-clear never displaces a
    /// warning.
    var urgency: Int {
        switch self {
        case .exhausted: return 0
        case .low: return 1
        case .medium: return 2
        case .error: return 3
        case .stale: return 4
        case .healthy: return 5
        }
    }
}

/// Chooses which account, and which row within it, earns the widget's scarce pixels.
private enum Criticality {

    /// The one account + row the small tile is allowed to talk about.
    struct Focus {
        let account: GlanceAccount
        let row: GlanceRow
    }

    /// The most critical account that actually has rows to show, with its own most
    /// critical row. An account without rows cannot contribute a number, so it
    /// cannot claim the small tile.
    static func focus(in snapshot: GlanceSnapshot) -> Focus? {
        let candidates = snapshot.accounts.filter { !$0.rows.isEmpty }
        guard let account = candidates.min(by: { isMoreCritical($0, $1) }) else { return nil }
        guard let row = criticalRow(in: account) else { return nil }
        return Focus(account: account, row: row)
    }

    /// The account's own most critical row.
    static func criticalRow(in account: GlanceAccount) -> GlanceRow? {
        account.rows.min { row($0, isMoreCriticalThan: $1) }
    }

    /// Most-critical-first display order for the medium tile.
    static func sortedAccounts(_ accounts: [GlanceAccount]) -> [GlanceAccount] {
        accounts.sorted { isMoreCritical($0, $1) }
    }

    private static func isMoreCritical(_ a: GlanceAccount, _ b: GlanceAccount) -> Bool {
        if a.severity.urgency != b.severity.urgency {
            return a.severity.urgency < b.severity.urgency
        }
        // Same account severity: let the tighter row decide, since an account that
        // can name its worst row says more than one that cannot.
        switch (criticalRow(in: a), criticalRow(in: b)) {
        case (let rowA?, let rowB?):
            return row(rowA, isMoreCriticalThan: rowB)
        case (_?, nil):
            return true
        case (nil, _?):
            return false
        default:
            return false
        }
    }

    private static func row(_ a: GlanceRow, isMoreCriticalThan b: GlanceRow) -> Bool {
        if a.severity.urgency != b.severity.urgency {
            return a.severity.urgency < b.severity.urgency
        }
        // Equal severity: the smaller *stated* percentage is the tighter limit. An
        // unknown percentage never outranks a stated one — we cannot rank what we
        // were not told — and equally-unknown rows fall through to their resets.
        switch (a.remainingPercent, b.remainingPercent) {
        case (let pa?, let pb?) where QuotaFormatting.fraction(pa) != QuotaFormatting.fraction(pb):
            return QuotaFormatting.fraction(pa) < QuotaFormatting.fraction(pb)
        case (_?, nil):
            return true
        case (nil, _?):
            return false
        default:
            return isEarlier(a.resetAt, b.resetAt)
        }
    }

    /// The earlier concrete reset wins; an unknown reset never does.
    private static func isEarlier(_ a: Date?, _ b: Date?) -> Bool {
        switch (a, b) {
        case (let a?, let b?):
            return a < b
        case (nil, nil):
            return false
        case (_?, nil):
            return true
        default:
            return false
        }
    }
}

// MARK: - Numbers and words

/// Quota number formatting. The rule that governs it: never invent a number.
private enum QuotaFormatting {

    /// A nil percentage is *unknown*, not zero. It renders as an em dash over an
    /// empty bar; coercing it to "0 %" would cry wolf, and to "100 %" would invent
    /// an all-clear the cache never made.
    static func percentText(_ percent: Double?) -> String {
        guard let percent else { return "—" }
        let value = Int((fraction(percent) * 100).rounded())
        return "\(value)%"
    }

    /// Normalises a percentage to 0…1 for bar widths. The kit's `remainingPercent`
    /// is a fraction, but a cache written with 0…100 values must not draw a bar
    /// forty times too wide: anything above 1 is rescaled, then clamped, so no bar
    /// can overflow and no label can read "4200 %".
    static func fraction(_ percent: Double) -> Double {
        let raw = percent > 1 ? percent / 100 : percent
        return min(max(raw, 0), 1)
    }
}

/// Builds every time string the tiles show. Everything here is *absolute*.
///
/// The kit offers `Countdown.format` ("2d 4h") and the widget deliberately refuses
/// it. A relative countdown is frozen at the moment WidgetKit last rendered, and it
/// goes wrong in the one direction that matters: the limit can already have reset
/// while the tile still insists "in 20m". A clock time ("Resets 14:05") was true
/// when it was written and stays true whatever the system does next. The same
/// argument forbids "5 min ago" styling — an age silently goes stale — so the data
/// instead carries its own timestamp: "As of 12:40".
private enum GlanceText {

    /// "Resets Tue 09:00", or the boundary state once the reset has passed.
    static func resetLine(resetAt: Date?, now: Date) -> String? {
        guard let resetAt else { return nil }
        if resetAt <= now {
            // The cached numbers predate the reset, so the tile says so instead of
            // implying a reset is still pending. The timeline requests fresh data
            // moments after the boundary, so this state is short-lived.
            return "Reset · refresh pending"
        }
        return "Resets \(Countdown.absolute(resetAt, now: now))"
    }

    /// "As of 12:40" — the data's own timestamp, or nothing if the cache states none.
    static func asOfLine(updatedAt: Date?, now: Date) -> String? {
        guard let updatedAt else { return nil }
        return "As of \(Countdown.absolute(updatedAt, now: now))"
    }
}

/// Tap destinations. Lock Screen accessories ignore these; the app registers the scheme.
private enum DeepLink {
    static let glance = URL(string: "usagelimits://glance")
}

// MARK: - Shared views

/// One quota bar.
///
/// A fixed height on a *bar* is legitimate — the Dynamic Type rule concerns text —
/// and `@ScaledMetric` grows the bar with the user's type size regardless.
private struct QuotaBar: View {

    let remainingPercent: Double?
    let severity: Severity
    /// Spoken context ("Claude, Messages") so the label below reads as a sentence.
    let context: String

    @ScaledMetric(relativeTo: .caption) private var barHeight = 5

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(UsageColors.progressTrack)
                if let remainingPercent {
                    // Unknown percentages draw the empty track only — never a 0 % sliver.
                    Capsule()
                        .fill(SeverityPalette.bar(remainingPercent: remainingPercent, severity: severity))
                        .frame(width: proxy.size.width * QuotaFormatting.fraction(remainingPercent))
                }
            }
        }
        .frame(height: barHeight)
        // A bar shape tells VoiceOver nothing; state the number in words.
        .accessibilityElement()
        .accessibilityLabel(spokenText)
    }

    private var spokenText: String {
        guard let remainingPercent else {
            return "\(context): remaining unknown"
        }
        return "\(context): \(QuotaFormatting.percentText(remainingPercent)) remaining"
    }
}

/// Rendered whenever the cache is missing, unreadable or empty.
///
/// The widget cannot open a dialogue with the user, so its only honest message is
/// that the app has the numbers — never a fabricated percentage to look busy.
private struct WidgetEmptyStateView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: "gauge.with.needle")
                .font(.title3)
                .foregroundColor(UsageColors.textSecondary)
            Text("No accounts yet")
                .font(.headline)
                .foregroundColor(UsageColors.textPrimary)
            Text("Open UsageLimits to see how much quota is left")
                .font(.caption)
                .foregroundColor(UsageColors.textSecondary)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// One account line on the medium tile: title, its worst row's percentage, its bar.
private struct MediumAccountRow: View {

    let account: GlanceAccount

    var body: some View {
        let row = Criticality.criticalRow(in: account)
        return VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                // A severity dot: the colour-blind-readable twin of the bar colour.
                // Redundant to VoiceOver, which already hears the percentage.
                Circle()
                    .fill(SeverityPalette.accent(account.severity))
                    .frame(width: 6, height: 6)
                    .accessibilityHidden(true)
                Text(verbatim: account.title)
                    .font(.subheadline)
                    .foregroundColor(UsageColors.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(verbatim: QuotaFormatting.percentText(row?.remainingPercent))
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(SeverityPalette.text(row?.severity ?? account.severity))
                    .lineLimit(1)
            }
            QuotaBar(
                remainingPercent: row?.remainingPercent,
                severity: row?.severity ?? account.severity,
                context: row.map { "\(account.title), \($0.label)" } ?? account.title
            )
        }
    }
}

// MARK: - Tiles

/// systemSmall: one thing, said well.
///
/// The single most critical account — title, one percentage, one bar, the next
/// reset time, and the "As of" line that dates the claim. Nothing else competes
/// for the tile: a small surface that tries to say two things says neither.
struct UsageWidgetSmallView: View {

    let entry: UsageEntry

    var body: some View {
        Group {
            if let focus = Criticality.focus(in: entry.snapshot) {
                content(for: focus)
            } else {
                WidgetEmptyStateView()
            }
        }
        .widgetBackground(UsageColors.background)
        .widgetURL(DeepLink.glance)
    }

    private func content(for focus: Criticality.Focus) -> some View {
        // The row's own reset when the cache states one, otherwise the snapshot's
        // next reset — the nearest reset we can actually name, never a countdown.
        let resetAt = focus.row.resetAt ?? entry.snapshot.nextResetAt
        return VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: focus.account.title)
                .font(.headline)
                .foregroundColor(UsageColors.textPrimary)
                .lineLimit(1)

            Text(verbatim: focus.row.label)
                .font(.caption)
                .foregroundColor(UsageColors.textSecondary)
                .lineLimit(1)

            Spacer(minLength: 4)

            Text(verbatim: QuotaFormatting.percentText(focus.row.remainingPercent))
                .font(.system(.title2, design: .rounded).weight(.semibold))
                .foregroundColor(SeverityPalette.text(focus.row.severity))
                .lineLimit(1)

            QuotaBar(
                remainingPercent: focus.row.remainingPercent,
                severity: focus.row.severity,
                context: "\(focus.account.title), \(focus.row.label)"
            )

            if let reset = GlanceText.resetLine(resetAt: resetAt, now: entry.date) {
                Text(verbatim: reset)
                    .font(.caption2)
                    .foregroundColor(UsageColors.textSecondary)
                    .lineLimit(1)
            }

            if let asOf = GlanceText.asOfLine(updatedAt: entry.snapshot.updatedAt, now: entry.date) {
                Text(verbatim: asOf)
                    .font(.caption2)
                    .foregroundColor(UsageColors.textTertiary)
                    .lineLimit(1)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// systemMedium: the accounts that most need attention, at most three of them.
///
/// Ranked most-critical-first; everything past the third is counted ("+N more"),
/// not drawn — a fourth cramped row helps nobody. At larger type sizes even three
/// rows will not fit, so the limit drops to two; and no text anywhere is given a
/// fixed height, so rows grow with the user's chosen size instead of being
/// clipped to one.
struct UsageWidgetMediumView: View {

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let entry: UsageEntry

    private var rankedAccounts: [GlanceAccount] {
        Criticality.sortedAccounts(entry.snapshot.accounts)
    }

    /// Three rows fit at regular sizes; from xLarge upwards two fill the tile, and
    /// a third clipped mid-bar would be worse than an honest "+N more".
    private var visibleLimit: Int {
        dynamicTypeSize >= .xLarge ? 2 : 3
    }

    private var visibleAccounts: [GlanceAccount] {
        Array(rankedAccounts.prefix(visibleLimit))
    }

    private var hiddenCount: Int {
        max(rankedAccounts.count - visibleAccounts.count, 0)
    }

    var body: some View {
        Group {
            if entry.snapshot.accounts.isEmpty {
                WidgetEmptyStateView()
            } else {
                content
            }
        }
        .widgetBackground(UsageColors.background)
        .widgetURL(DeepLink.glance)
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 5) {
            header

            ForEach(visibleAccounts) { account in
                MediumAccountRow(account: account)
            }

            if hiddenCount > 0 {
                // Counted, not drawn: the reader learns the rest exist without
                // being shown three unreadable half-rows.
                Text(verbatim: "+\(hiddenCount) more")
                    .font(.caption2)
                    .foregroundColor(UsageColors.textTertiary)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 1)
                    .background(Capsule().fill(UsageColors.surface))
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    /// "As of …" dates the numbers; the reset line names the nearest upcoming
    /// reset. Both are absolute strings, for the reasons in `GlanceText`'s discussion.
    @ViewBuilder
    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            if let asOf = GlanceText.asOfLine(updatedAt: entry.snapshot.updatedAt, now: entry.date) {
                Text(verbatim: asOf)
                    .font(.caption2)
                    .foregroundColor(UsageColors.textTertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if let reset = GlanceText.resetLine(resetAt: entry.snapshot.nextResetAt, now: entry.date) {
                Text(verbatim: reset)
                    .font(.caption2)
                    .foregroundColor(UsageColors.textSecondary)
                    .lineLimit(1)
            }
        }
    }
}

/// accessoryRectangular (Lock Screen, StandBy): three short lines, system colours.
///
/// Accessories are re-tinted by the system against arbitrary backgrounds — the
/// app palette would collapse into an unreadable mid-grey — so this view spends
/// nothing on custom colour and states severity in words instead. There is no
/// room for an "As of" line, so the tighter trade is made: the reset line, the
/// claim most likely to go wrong, wins the space.
struct UsageAccessoryRectangularView: View {

    let entry: UsageEntry

    var body: some View {
        if let focus = Criticality.focus(in: entry.snapshot) {
            content(for: focus)
        } else {
            emptyContent
        }
    }

    private func content(for focus: Criticality.Focus) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(verbatim: focus.account.title)
                .font(.headline)
                .lineLimit(1)

            HStack(spacing: 4) {
                Text(verbatim: QuotaFormatting.percentText(focus.row.remainingPercent))
                    .font(.title3.weight(.semibold))
                    .lineLimit(1)
                Text(verbatim: SeverityPalette.label(focus.row.severity))
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }

            if let reset = GlanceText.resetLine(resetAt: focus.row.resetAt, now: entry.date) {
                Text(verbatim: reset)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
        }
    }

    private var emptyContent: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text("UsageLimits")
                .font(.headline)
            Text("Open the app to see your quota")
                .font(.caption2)
                .foregroundColor(.secondary)
        }
    }
}

/// Routes each render to the view for the family the system asked for.
struct UsageWidgetEntryView: View {

    @Environment(\.widgetFamily) private var family

    let entry: UsageEntry

    var body: some View {
        switch family {
        case .systemMedium:
            UsageWidgetMediumView(entry: entry)
        case .accessoryRectangular:
            UsageAccessoryRectangularView(entry: entry)
        default:
            UsageWidgetSmallView(entry: entry)
        }
    }
}

// MARK: - Widget

private extension View {
    /// Tile background that survives both generations of widget chrome: iOS 17
    /// requires `containerBackground` for full-bleed colour, and iOS 16 never
    /// heard of it. One wrapper keeps the call sites honest on either.
    @ViewBuilder
    func widgetBackground(_ color: Color) -> some View {
        if #available(iOS 17.0, *) {
            containerBackground(for: .widget) { color }
        } else {
            background(color)
        }
    }
}

/// The subscription-quota widget.
struct UsageWidget: Widget {

    /// Reverse-DNS kind, unique among the app's widgets.
    private static let kind = "com.usagelimits.widget.usage"

    var body: some WidgetConfiguration {
        // An instance, not `UsageProvider.self`. `StaticConfiguration` takes a provider VALUE;
        // a metatype does not conform to `TimelineProvider`, so the metatype spelling does not
        // compile — and this file had never been compiled anywhere.
        StaticConfiguration(kind: Self.kind, provider: UsageProvider()) { entry in
            UsageWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Usage Limits")
        .description("Quota left on your AI subscriptions, at a glance.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular])
    }
}

@main
struct UsageWidgets: WidgetBundle {
    var body: some Widget {
        UsageWidget()
    }
}