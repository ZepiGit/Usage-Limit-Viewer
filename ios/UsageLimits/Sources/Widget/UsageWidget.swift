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
        // Annotated, because the literal mixes a Double expression with integer ones and Swift
        // then infers `[Any]` rather than complaining about the mix — which compiles as an array
        // and fails at the arithmetic.
        let leadTimes: [TimeInterval] = [6 * 60 * 60, 60 * 60, 15 * 60, 5 * 60, 60]
        for lead in leadTimes {
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
enum SnapshotCache {

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
        loadResult().snapshot
    }

    /// The read outcome, so the tile can say which of the several nothings it got.
    static func loadResult() -> GlanceSnapshotCodec.Load {
        guard let containerURL else { return .missing }
        return GlanceSnapshotCodec.load(fromDirectory: containerURL)
    }

}

// MARK: - Snapshot lead

/// The account and row the small tiles talk about — chosen by the kit, not here.
///
/// The widget used to re-rank accounts with a private urgency scale that disagreed with
/// `GlanceModel.urgency` (it put error and stale above healthy; the kit puts them below), and
/// then printed `snapshot.nextResetAt` — which the kit computed for ITS first account — next
/// to whichever account the local ranking had promoted. The medium tile read "Resets 14:05"
/// beside a Claude row when 14:05 was Codex's clock. One ranking, one owner: the lead is
/// `accounts.first`, and its headline rows and reset are the ones the kit paired with it.
private struct WidgetFocus {
    let account: GlanceAccount
    /// The kit's headline for the lead: the five-hour row when it has one, else the longer.
    let row: GlanceRow?

    init?(snapshot: GlanceSnapshot) {
        guard let account = snapshot.accounts.first else { return nil }
        self.account = account
        self.row = snapshot.widgetRow(for: account)
    }
}

private extension GlanceSnapshot {
    /// The row a tile shows for one account: the kit's headline for the LEAD, and every other
    /// account's own first row. Stated once — three views spelled it out separately, and the
    /// precedence decides which number a user sees.
    func widgetRow(for account: GlanceAccount) -> GlanceRow? {
        if account.id == accounts.first?.id {
            return headlineShort ?? headlineLong ?? account.rows.first
        }
        return account.rows.first
    }
}

private enum QuotaFormatting {

    /// A nil percentage is *unknown*, not zero. It renders as an em dash over an
    /// empty bar; coercing it to "0 %" would cry wolf, and to "100 %" would invent
    /// an all-clear the cache never made.
    static func percentText(_ percent: Double?) -> String {
        guard let percent else { return "—" }
        let value = Int((fraction(percent) * 100).rounded())
        return "\(value)%"
    }

    /// Normalises a 0…100 percentage to 0…1 for bar widths and labels.
    ///
    /// The previous version guessed the scale from the value — anything above 1 was
    /// treated as a percentage and anything at or below 1 as a fraction already. But the
    /// kit's `remainingPercent` is 0…100 everywhere (`UsageWindow.swift`, `min(max(100 -
    /// used, 0), 100)`), so a quota with 1 % left arrived as `1.0`, was taken to be a
    /// fraction, and rendered as "100%" with a completely full bar. The Android widget
    /// showed "1%" for the identical snapshot. A nearly-exhausted quota reading as nearly
    /// full is the single worst number this app can show, so the scale is no longer
    /// inferred: the input is always a percentage. Clamping still stops a corrupt cache
    /// value from overflowing the bar.
    static func fraction(_ percent: Double) -> Double {
        min(max(percent / 100, 0), 1)
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

/// Tap destinations. Lock Screen accessories ignore these; the app registers the scheme in its
/// `CFBundleURLTypes` and answers them in `onOpenURL`.
private enum DeepLink {
    static let glance = URL(string: "usagelimits://glance")

    /// Opens the app and refreshes there.
    ///
    /// Not a refresh performed inside the widget, and that is a design decision rather than a
    /// shortcut: the extension holds no credentials. Tokens live in the keychain and are read
    /// only by the app process — which is precisely what keeps them out of widget state — so an
    /// extension that fetched would have to be given them. A button that reloads the TIMELINE
    /// without fetching would be worse still: it would redraw the same cached numbers and look
    /// like a refresh that changed nothing.
    static let refresh = URL(string: "usagelimits://refresh")
}

// MARK: - Shared views

/// One quota bar.
///
/// A fixed height on a *bar* is legitimate — the Dynamic Type rule concerns text —
/// and `@ScaledMetric` grows the bar with the user's type size regardless.
private struct QuotaBar: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
        .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.24), value: remainingPercent)
        .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.24), value: severity)
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

/// What both tiles show when there is nothing to show.
///
/// Deliberately not a blank tile, and deliberately not one message for every cause.
///
/// A tile with nothing on it has several quite different causes, and each wants a different
/// sentence. The message is taken from the read outcome the codec reports, never inferred
/// here: an earlier version guessed from whether the file existed, and so told anyone with a
/// readable but empty snapshot — the ordinary state with no accounts connected — that their
/// device was locked, which unlocking could not fix. None of these says "you have quota left".
private struct WidgetEmptyStateView: View {

    var outcome: GlanceSnapshotCodec.Load = SnapshotCache.loadResult()

    private var icon: String {
        switch outcome {
        case .unreadable: return "lock"
        case .corrupt: return "exclamationmark.triangle"
        default: return "gauge.with.needle"
        }
    }

    private var title: String {
        switch outcome {
        case .unreadable: return "Locked"
        case .corrupt: return "Can't read usage"
        default: return "No accounts yet"
        }
    }

    private var detail: String {
        switch outcome {
        case .unreadable: return "Unlock this device to see how much quota is left"
        case .corrupt: return "Open UsageLimits to rebuild the data"
        default: return "Open UsageLimits to see how much quota is left"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundColor(UsageColors.textSecondary)
            Text(title)
                .font(.headline)
                .foregroundColor(UsageColors.textPrimary)
            Text(detail)
                .font(.caption)
                .foregroundColor(UsageColors.textSecondary)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// One account line on the medium tile: title, one row's percentage, its bar.
///
/// The row is handed in rather than chosen here, so the lead shows the kit's headline and
/// every other account shows its first row in the kit's order.
private struct MediumAccountRow: View {

    let account: GlanceAccount
    let row: GlanceRow?
    /// The date of the timeline entry this row is being drawn for, so freshness is judged
    /// there rather than at the moment the app wrote the snapshot.
    let now: Date
    let staleAfter: TimeInterval

    /// The row's own severity while the account is healthy, and the account's otherwise.
    ///
    /// A row's severity comes from its percentage and knows nothing about age, so letting it
    /// speak unconditionally painted a stale or failed account in the colour of its last good
    /// number — the one reading a quota widget must never give.
    private var rowSeverity: Severity {
        let aged = account.severity(at: now, staleAfter: staleAfter)
        return aged == .healthy ? (row?.severity ?? aged) : aged
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                // A severity dot: the colour-blind-readable twin of the bar colour.
                // Redundant to VoiceOver, which already hears the percentage.
                Circle()
                    .fill(SeverityPalette.accent(account.severity(at: now, staleAfter: staleAfter)))
                    .frame(width: 6, height: 6)
                    .accessibilityHidden(true)
                Text(verbatim: account.title)
                    .font(.subheadline)
                    .foregroundColor(UsageColors.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 8)
                WidgetPercentage(remainingPercent: row?.remainingPercent)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(SeverityPalette.text(rowSeverity))
                    .lineLimit(1)
            }
            QuotaBar(
                remainingPercent: row?.remainingPercent,
                severity: rowSeverity,
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
    var transparent: Bool = false

    var body: some View {
        Group {
            if let focus = WidgetFocus(snapshot: entry.snapshot) {
                content(for: focus)
            } else {
                WidgetEmptyStateView()
            }
        }
        .widgetBackground(transparent ? nil : UsageColors.background)
        .widgetURL(DeepLink.glance)
    }

    private func content(for focus: WidgetFocus) -> some View {
        // The snapshot's next reset is the LEAD's soonest reset — the kit scoped it that way
        // — so it is the right clock for this tile, and never a countdown.
        let resetAt = entry.snapshot.nextResetAt
        // Aged to THIS entry's date, not to the moment the app wrote the snapshot. A timeline
        // holds several entries built from one snapshot, so a frozen verdict means a tile that
        // was healthy at write time still reads healthy hours later with nothing behind it.
        let aged = focus.account.severity(at: entry.date, staleAfter: entry.snapshot.staleAfter)
        // The row's severity comes from its percentage alone and knows nothing about age, so
        // it may only speak while the account itself is healthy. Otherwise a stale or failed
        // account was painted in the colour of its last good number.
        let severity = aged == .healthy ? (focus.row?.severity ?? aged) : aged
        return VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: focus.account.title)
                .font(.headline)
                .foregroundColor(UsageColors.textPrimary)
                .lineLimit(1)

            if let row = focus.row {
                Text(verbatim: row.label)
                    .font(.caption)
                    .foregroundColor(UsageColors.textSecondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)

            WidgetPercentage(remainingPercent: focus.row?.remainingPercent)
                .font(.system(.title2, design: .rounded).weight(.semibold))
                .foregroundColor(SeverityPalette.text(severity))
                .lineLimit(1)

            QuotaBar(
                remainingPercent: focus.row?.remainingPercent,
                severity: severity,
                context: focus.row.map { "\(focus.account.title), \($0.label)" } ?? focus.account.title
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
/// In the kit's order — most urgent first, as GlanceModel ranks them — everything past the
/// third is counted ("+N more"),
/// not drawn — a fourth cramped row helps nobody. At larger type sizes even three
/// rows will not fit, so the limit drops to two; and no text anywhere is given a
/// fixed height, so rows grow with the user's chosen size instead of being
/// clipped to one.
struct UsageWidgetMediumView: View {

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let entry: UsageEntry
    var transparent: Bool = false

    /// The kit's order, untouched. See `WidgetFocus` for what re-ranking here cost.
    private var rankedAccounts: [GlanceAccount] {
        entry.snapshot.accounts
    }

    /// The lead shows the kit's headline row; every other account its own first row.
    private func row(for account: GlanceAccount) -> GlanceRow? {
        entry.snapshot.widgetRow(for: account)
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
        .widgetBackground(transparent ? nil : UsageColors.background)
        .widgetURL(DeepLink.glance)
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 5) {
            WidgetHeader(entry: entry)

            ForEach(visibleAccounts) { account in
                MediumAccountRow(
                    account: account,
                    row: row(for: account),
                    now: entry.date,
                    staleAfter: entry.snapshot.staleAfter)
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
        if let focus = WidgetFocus(snapshot: entry.snapshot) {
            content(for: focus)
        } else {
            emptyContent
        }
    }

    private func content(for focus: WidgetFocus) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(verbatim: focus.account.title)
                .font(.headline)
                .lineLimit(1)

            HStack(spacing: 4) {
                WidgetPercentage(remainingPercent: focus.row?.remainingPercent)
                    .font(.title3.weight(.semibold))
                    .lineLimit(1)
                // The ACCOUNT's severity, which is the one that carries staleness. The row's
                // is computed from the percentage alone, so the lock screen wrote "Healthy"
                // over numbers fetched a day ago. Now it says "Stale", which is one property
                // access away and the truth.
                Text(verbatim: SeverityPalette.label(
                    focus.account.severity(at: entry.date, staleAfter: entry.snapshot.staleAfter)))
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }

            if let reset = GlanceText.resetLine(resetAt: entry.snapshot.nextResetAt, now: entry.date) {
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

/// A quota as a ring: the arc is what is LEFT, and the middle names what of.
///
/// The one shape that survives being small. A bar has to be as wide as the tile to be
/// readable, so a tile holding several of them holds little else; a ring carries the same
/// number in a square and leaves its own middle free for the two facts that qualify it — which
/// limit, and when it comes back.
private struct UsageRing: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let remainingPercent: Double?
    let severity: Severity
    /// `Double`, not `CGFloat`: this file imports Foundation, where corelibs declares its own
    /// CGFloat, so the name is ambiguous on the Linux typecheck. Swift converts between the two
    /// implicitly (SE-0307), so `StrokeStyle` takes it unchanged on the real SDK.
    let lineWidth: Double

    var body: some View {
        ZStack {
            Circle()
                .stroke(UsageColors.progressTrack, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            if let remainingPercent {
                // Unknown draws the track alone. A full ring for "the provider did not say"
                // would be an all-clear nothing reported, which is the one direction a quota
                // display must never round in.
                Circle()
                    .trim(from: 0, to: QuotaFormatting.fraction(remainingPercent))
                    .stroke(
                        SeverityPalette.bar(remainingPercent: remainingPercent, severity: severity),
                        style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    // From twelve o'clock, clockwise. `trim` starts at three o'clock.
                    .rotationEffect(Angle.degrees(-90))
                    .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.24), value: remainingPercent)
                    .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.24), value: severity)
            }
        }
    }
}

/// systemSmall, transparent: one ring, the percentage inside it, the limit and its reset below.
///
/// The tile the feature list asked for — "usage as a circle with the reset time and the model
/// inside, so many accounts fit a small area". Several of these side by side is how a home
/// screen shows five subscriptions without five list tiles.
struct UsageRingWidgetView: View {

    let entry: UsageEntry

    var body: some View {
        Group {
            if let focus = WidgetFocus(snapshot: entry.snapshot) {
                content(for: focus)
            } else {
                WidgetEmptyStateView()
            }
        }
        // Transparent: the wallpaper shows through the system's widget material. A ring reads
        // perfectly well against it, which a list of bars would not.
        .widgetBackground(nil)
        .widgetURL(DeepLink.glance)
    }

    private func content(for focus: WidgetFocus) -> some View {
        let aged = focus.account.severity(at: entry.date, staleAfter: entry.snapshot.staleAfter)
        let severity = aged == .healthy ? (focus.row?.severity ?? aged) : aged
        return VStack(spacing: 6) {
            ZStack {
                UsageRing(
                    remainingPercent: focus.row?.remainingPercent,
                    severity: severity,
                    lineWidth: 9)
                VStack(spacing: 1) {
                    WidgetPercentage(remainingPercent: focus.row?.remainingPercent)
                        .font(.system(.title2, design: .rounded).weight(.semibold))
                        .foregroundColor(SeverityPalette.text(severity))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    if let row = focus.row {
                        Text(verbatim: row.label)
                            .font(.caption2)
                            .foregroundColor(UsageColors.textSecondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                }
                .padding(10)
            }
            .accessibilityElement()
            .accessibilityLabel(spoken(focus))

            Text(verbatim: focus.account.title)
                .font(.caption2.weight(.semibold))
                .foregroundColor(UsageColors.textPrimary)
                .lineLimit(1)
            if let reset = GlanceText.resetLine(resetAt: entry.snapshot.nextResetAt, now: entry.date) {
                Text(verbatim: reset)
                    .font(.caption2)
                    .foregroundColor(UsageColors.textSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        }
        .padding(10)
    }

    private func spoken(_ focus: WidgetFocus) -> String {
        let limit = focus.row.map { ", \($0.label)" } ?? ""
        guard let remaining = focus.row?.remainingPercent else {
            return "\(focus.account.title)\(limit): remaining unknown"
        }
        return "\(focus.account.title)\(limit): \(QuotaFormatting.percentText(remaining)) remaining"
    }
}

/// accessoryCircular (Lock Screen, StandBy): the same ring, in the system's own tint.
///
/// No app colour at all, for the reason `UsageAccessoryRectangularView` gives: accessories are
/// re-tinted against arbitrary backgrounds and a custom palette collapses into an unreadable
/// grey. The arc carries the number and the middle carries it again in digits, because at this
/// size an arc alone is a guess.
struct UsageAccessoryCircularView: View {

    let entry: UsageEntry

    var body: some View {
        let focus = WidgetFocus(snapshot: entry.snapshot)
        let remaining = focus?.row?.remainingPercent
        return ZStack {
            Circle()
                .stroke(.tertiary, style: StrokeStyle(lineWidth: 5, lineCap: .round))
            if let remaining {
                Circle()
                    .trim(from: 0, to: QuotaFormatting.fraction(remaining))
                    .stroke(.primary, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                    .rotationEffect(Angle.degrees(-90))
            }
            WidgetPercentage(remainingPercent: remaining)
                .font(.caption.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .accessibilityElement()
        .accessibilityLabel(
            focus.map { "\($0.title(for: remaining))" } ?? "Open the app to see your quota")
    }
}

private extension WidgetFocus {
    func title(for remaining: Double?) -> String {
        guard let remaining else { return "\(account.title): remaining unknown" }
        return "\(account.title): \(QuotaFormatting.percentText(remaining)) remaining"
    }
}

/// systemLarge: every account that fits, one dense row each.
///
/// The medium tile stops at three and counts the rest, which is right for its height. A large
/// tile has room for the whole fleet, and seeing all of it at once is the thing the app is for
/// — the feature list asked for exactly this, "eight accounts in one tile". Eight is the cap:
/// past that the rows are thinner than the text in them, and an honest "+N more" beats a row
/// nobody can read.
struct UsageWidgetLargeView: View {

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let entry: UsageEntry
    let transparent: Bool

    /// Eight at regular sizes, five once the type is large enough that eight would clip.
    private var visibleLimit: Int { dynamicTypeSize >= .xLarge ? 5 : 8 }

    private var visibleAccounts: [GlanceAccount] {
        Array(entry.snapshot.accounts.prefix(visibleLimit))
    }

    private var hiddenCount: Int {
        max(entry.snapshot.accounts.count - visibleAccounts.count, 0)
    }

    /// The lead shows the kit's headline row; every other account its own first row. Same rule
    /// as the medium tile, and for the same reason — see `WidgetFocus`.
    private func row(for account: GlanceAccount) -> GlanceRow? {
        entry.snapshot.widgetRow(for: account)
    }

    var body: some View {
        Group {
            if entry.snapshot.accounts.isEmpty {
                WidgetEmptyStateView()
            } else {
                content
            }
        }
        .widgetBackground(transparent ? nil : UsageColors.background)
        .widgetURL(DeepLink.glance)
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 8) {
            WidgetHeader(entry: entry)

            ForEach(visibleAccounts) { account in
                MediumAccountRow(
                    account: account,
                    row: row(for: account),
                    now: entry.date,
                    staleAfter: entry.snapshot.staleAfter)
            }

            if hiddenCount > 0 {
                Text(verbatim: "+\(hiddenCount) more")
                    .font(.caption2)
                    .foregroundColor(UsageColors.textTertiary)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 1)
                    .background(Capsule().fill(UsageColors.surface))
            }

            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// The line both list tiles carry: when the numbers were read, when the next limit rolls over,
/// and the way to ask for fresher ones.
private struct WidgetHeader: View {

    let entry: UsageEntry

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            if let asOf = GlanceText.asOfLine(updatedAt: entry.snapshot.updatedAt, now: entry.date) {
                Text(verbatim: asOf)
                    .font(.caption2)
                    .foregroundColor(UsageColors.textTertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if let reset = GlanceText.resetLine(resetAt: entry.snapshot.nextResetAt, now: entry.date) {
                Text(verbatim: reset)
                    .font(.caption2)
                    .foregroundColor(UsageColors.textSecondary)
                    .lineLimit(1)
            }
            if let refresh = DeepLink.refresh {
                // A `Link` rather than the tile-wide `widgetURL`, so this one glyph opens the
                // app on a refresh while the rest of the tile still opens the overview. The
                // system honours `Link` on medium and large only, which is why the small tile
                // has no such button rather than a decorative one that does nothing.
                Link(destination: refresh) {
                    Image(systemName: "arrow.clockwise")
                        .font(.caption2.weight(.semibold))
                        .foregroundColor(UsageColors.textSecondary)
                }
                .accessibilityLabel("Refresh in the app")
            }
        }
    }
}

/// Routes each render to the view for the family the system asked for.
struct UsageWidgetEntryView: View {

    @Environment(\.widgetFamily) private var family

    let entry: UsageEntry
    /// Whether this gallery entry is the transparent one. Carried rather than read from a
    /// setting, because a widget's look is chosen per placed tile and two tiles of the same
    /// kind may legitimately differ.
    var transparent: Bool = false

    var body: some View {
        switch family {
        case .systemLarge:
            UsageWidgetLargeView(entry: entry, transparent: transparent)
        case .systemMedium:
            UsageWidgetMediumView(entry: entry, transparent: transparent)
        case .accessoryRectangular:
            UsageAccessoryRectangularView(entry: entry)
        default:
            UsageWidgetSmallView(entry: entry, transparent: transparent)
        }
    }
}

// MARK: - Widget

private extension View {
    /// Tile background that survives both generations of widget chrome: iOS 17
    /// requires `containerBackground` for full-bleed colour, and iOS 16 never
    /// heard of it. One wrapper keeps the call sites honest on either.
    @ViewBuilder
    func widgetBackground(_ color: Color?) -> some View {
        if #available(iOS 17.0, *) {
            // `Color.clear` rather than no container background at all. On iOS 17 a widget that
            // declares none is rejected from the Home Screen entirely; declaring a clear one is
            // what "transparent" actually means there — the system's own widget material shows
            // the wallpaper through it.
            containerBackground(for: .widget) { color ?? Color.clear }
        } else {
            background(color ?? Color.clear)
        }
    }
}

/// The subscription-quota widget.
struct UsageWidget: Widget {

    /// Reverse-DNS kind, unique among the app's widgets.
    ///
    /// Never changed once shipped: the kind is how the system identifies a PLACED tile, so a
    /// rename silently orphans every widget the user has already put on their home screen.
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
        .supportedFamilies([
            .systemSmall, .systemMedium, .systemLarge, .accessoryRectangular,
        ])
    }
}

/// The same tile, with the wallpaper showing through it.
///
/// A separate gallery entry rather than a switch inside one, which is what Android offers.
/// The difference is not a preference: a per-tile option needs a configurable widget, and
/// configuration means `AppIntentConfiguration` — iOS 17, where this app supports 16. Two
/// entries cost the user one extra row in the gallery and work on every version the app runs
/// on; the alternative would be a checkbox that half the installed base could not reach.
struct UsageClearWidget: Widget {

    private static let kind = "com.usagelimits.widget.usage.clear"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: UsageProvider()) { entry in
            UsageWidgetEntryView(entry: entry, transparent: true)
        }
        .configurationDisplayName("Usage Limits (Clear)")
        .description("The same tile with a transparent background.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

/// One account as a ring, small enough that several fit where one list tile would.
struct UsageRingWidget: Widget {

    private static let kind = "com.usagelimits.widget.ring"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: UsageProvider()) { entry in
            UsageRingEntryView(entry: entry)
        }
        .configurationDisplayName("Usage Ring")
        .description("What is left on one subscription, as a ring, with its reset time.")
        .supportedFamilies([.systemSmall, .accessoryCircular])
    }
}

/// Routes the ring widget's two families.
///
/// Separate from `UsageWidgetEntryView` rather than another case in it, because the two widgets
/// support disjoint families and a shared router would silently fall through to the small list
/// view if a family were ever added to one of them.
struct UsageRingEntryView: View {

    @Environment(\.widgetFamily) private var family

    let entry: UsageEntry

    var body: some View {
        switch family {
        case .accessoryCircular:
            UsageAccessoryCircularView(entry: entry)
        default:
            UsageRingWidgetView(entry: entry)
        }
    }
}

@main
struct UsageWidgets: WidgetBundle {
    var body: some Widget {
        #if canImport(AppIntents)
        if #available(iOS 17.0, *) {
            ConfiguredUsageWidget()
            ConfiguredRingWidget(mini: false)
            ConfiguredRingWidget(mini: true)
        }
        #endif
        UsageWidget()
        UsageClearWidget()
        UsageRingWidget()
    }
}
/// WidgetKit animates only when a timeline value changes; no timer or background task is added.
private struct WidgetPercentage: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let remainingPercent: Double?

    var body: some View {
        if #available(iOS 17.0, *), !reduceMotion {
            Text(verbatim: QuotaFormatting.percentText(remainingPercent))
                .contentTransition(ContentTransition.numericText())
        } else {
            Text(verbatim: QuotaFormatting.percentText(remainingPercent))
                .transaction { if reduceMotion { $0.animation = nil; $0.disablesAnimations = true } }
        }
    }
}
