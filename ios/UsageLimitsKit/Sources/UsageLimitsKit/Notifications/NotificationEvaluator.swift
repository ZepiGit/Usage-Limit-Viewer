import Foundation

// MARK: - Snapshot model

/// Extra quota the provider has granted but the user has not yet claimed.
/// One account, reduced to what the evaluator needs.
///
/// Deliberately not `AccountUsage`: the evaluator has no business knowing about credential
/// references or provider ids, and keeping the input this narrow is what stops a notification
/// ever interpolating something that should not appear on a lock screen. `label` is already
/// the masked form.
public struct AccountSummary: Sendable, Equatable {
    public let accountId: String
    public let label: String
    public let snapshot: UsageSnapshot?

    public init(accountId: String, label: String, snapshot: UsageSnapshot?) {
        self.accountId = accountId
        self.label = label
        self.snapshot = snapshot
    }

    public init(_ usage: AccountUsage) {
        self.init(
            accountId: usage.account.id,
            label: usage.account.label,
            snapshot: usage.snapshot)
    }
}

public struct NotificationSettings: Equatable, Sendable, Codable {
    public var notifyBelow20Percent = true
    public var notifyBelow10Percent = true
    public var notifyOnExhausted = true
    public var notifyOnResetCreditAvailable = true
    public var notifyOnAuthExpired = true
    public var notifyOnResetApproaching = false
    public var notifyOnResetCreditExpiring = true
    public var resetApproachingMinutes = 30
    public var resetCreditExpiryLeadMinutes = 1440

    /// Accounts that should never notify, by local id.
    ///
    /// A preference about an account rather than a fact about it, so it lives here with the
    /// other notification choices instead of on the account record. A stale id left behind by a
    /// deleted account is inert: ids are UUIDs, so re-adding the same provider account mints a
    /// new one and cannot inherit an old mute.
    public var mutedAccountIDs: Set<String> = []

    public init() {}

    /// Decoded field by field, each falling back to its default.
    ///
    /// Swift's synthesised decoder ignores property defaults and requires every key, so adding
    /// one toggle in a later version would make every stored settings file undecodable — and the
    /// store treats an undecodable file as "use defaults", which silently resets every
    /// preference the user had set. A settings file is exactly the place where forward
    /// compatibility has to be deliberate rather than synthesised.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        func flag(_ key: CodingKeys, _ fallback: Bool) throws -> Bool {
            try container.decodeIfPresent(Bool.self, forKey: key) ?? fallback
        }
        notifyBelow20Percent = try flag(.notifyBelow20Percent, true)
        notifyBelow10Percent = try flag(.notifyBelow10Percent, true)
        notifyOnExhausted = try flag(.notifyOnExhausted, true)
        notifyOnResetCreditAvailable = try flag(.notifyOnResetCreditAvailable, true)
        notifyOnAuthExpired = try flag(.notifyOnAuthExpired, true)
        notifyOnResetApproaching = try flag(.notifyOnResetApproaching, false)
        notifyOnResetCreditExpiring = try flag(.notifyOnResetCreditExpiring, true)
        resetApproachingMinutes =
            try container.decodeIfPresent(Int.self, forKey: .resetApproachingMinutes) ?? 30
        resetCreditExpiryLeadMinutes =
            try container.decodeIfPresent(Int.self, forKey: .resetCreditExpiryLeadMinutes) ?? 1440
        mutedAccountIDs =
            try container.decodeIfPresent(Set<String>.self, forKey: .mutedAccountIDs) ?? []
    }
}

// MARK: - Evaluator

/// Derives edge-triggered notifications from usage snapshots.
///
/// A snapshot can only state what is true now; a notification must state what *became* true
/// since the last look. The evaluator therefore takes prior per-account state in and hands
/// the updated state back, so its caller persists the state between syncs and feeds it in
/// again. Every edge carries a stable key: the caller remembers the keys it has already
/// acted on and swallows repeats. That memory is what lets the evaluator re-state a
/// still-true condition without notifying twice, and what lets an intentionally blank line
/// consume a key so a threshold cannot re-fire later within the same episode.
public enum NotificationEvaluator {

    /// The exact sentence a rejected credential produces.
    ///
    /// Owned here, by the code that has to RECOGNISE it, and used by the producer — rather than
    /// each end spelling its own and the evaluator guessing with a substring.
    ///
    /// It guessed with `contains("expired")`, and on iOS nothing ever said "expired": a revoked
    /// grant produced "This account needs signing in again.", so the predicate was false for
    /// every account that had one and `notifyOnAuthExpired` was DEAD — the user whose sign-in
    /// had lapsed was simply never told, while their numbers quietly stopped moving. The tests
    /// missed it because they fed hand-written strings the real producer never emits.
    ///
    /// Matching one constant instead means a reword cannot silently kill the notification: it
    /// breaks `testTheMessageAFailedSignInProducesIsTheOneTheEvaluatorLooksFor` first.
    public static let signInExpiredMessage = "Sign-in expired — reconnect this account"

    /// Whether this failure is one the user has to fix by signing in again.
    ///
    /// The canonical sentence, or — for a snapshot written by an earlier build, which is cached
    /// and outlives the upgrade — the old wording it may still hold.
    public static func meansSignInExpired(_ message: String?) -> Bool {
        guard let message else { return false }
        if message == signInExpiredMessage { return true }
        return message.lowercased().contains("expired")
            || message == "This account needs signing in again."
    }

    /// Remaining percentage strictly below which the warning tier applies.
    public static let warningPercent = 20.0

    /// Remaining percentage strictly below which the critical tier applies.
    public static let criticalPercent = 10.0

    /// What the evaluator remembers between syncs — deliberately minimal, since anything
    /// richer drifts out of step with the snapshot history it summarises.
    /// `Codable` because it is the whole point of the evaluator that this survives between
    /// syncs: an episode counter that resets when the process does would mint fresh keys every
    /// launch, and every threshold would re-fire.
    public struct AccountState: Sendable, Equatable, Codable {
        public let accountId: String

        /// Numbers each dip below the warning threshold. Quota keys embed it, so a fresh
        /// episode mints fresh keys whose tiers may fairly re-fire, whilst a continuing
        /// episode reuses its keys and the caller's key memory keeps it quiet.
        public var lowQuotaEpisode: Int = 0

        /// True from the first below-threshold snapshot until a fully healthy one arrives.
        /// Without it the episode counter would advance on every sync whilst low, and every
        /// sync would look like a brand-new dip.
        public var lowQuotaActive: Bool = false

        /// The `fetchedAt` of the newest snapshot whose edges have fired. Server time, not
        /// local receive time, so a re-delivered or out-of-order snapshot is recognisable
        /// and cannot re-fire its edges.
        public var lastProcessedFetchedAt: Date? = nil

        /// The episode each WINDOW is in, keyed by `windowKey`.
        ///
        /// Per window, not per account, because the episode is the unit of deduplication and
        /// an account has several windows that run out independently. With one episode per
        /// account, the five-hour window running out claimed every tier — and the weekly
        /// window then crossing 20 %, 10 % and 0 % said nothing, because every key was
        /// already spent and the episode only ended when EVERY window had recovered. The
        /// account-level fields above are kept as a summary: any window active, highest
        /// episode reached.
        public var windows: [String: WindowState] = [:]

        public init(
            accountId: String,
            lowQuotaEpisode: Int = 0,
            lowQuotaActive: Bool = false,
            lastProcessedFetchedAt: Date? = nil,
            windows: [String: WindowState] = [:]
        ) {
            self.accountId = accountId
            self.lowQuotaEpisode = lowQuotaEpisode
            self.lowQuotaActive = lowQuotaActive
            self.lastProcessedFetchedAt = lastProcessedFetchedAt
            self.windows = windows
        }

        private enum CodingKeys: String, CodingKey {
            case accountId, lowQuotaEpisode, lowQuotaActive, lastProcessedFetchedAt, windows
        }

        /// Lenient on the new key: a ledger written before per-window state has no `windows`
        /// and must still load, or every account would restart from nothing on upgrade.
        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            accountId = try c.decode(String.self, forKey: .accountId)
            lowQuotaEpisode = try c.decodeIfPresent(Int.self, forKey: .lowQuotaEpisode) ?? 0
            lowQuotaActive = try c.decodeIfPresent(Bool.self, forKey: .lowQuotaActive) ?? false
            lastProcessedFetchedAt = try c.decodeIfPresent(Date.self, forKey: .lastProcessedFetchedAt)
            windows = try c.decodeIfPresent([String: WindowState].self, forKey: .windows) ?? [:]
        }
    }

    /// One window's place in the dedup cycle.
    public struct WindowState: Sendable, Equatable, Codable {
        public var episode: Int
        public var active: Bool
        public init(episode: Int = 0, active: Bool = false) {
            self.episode = episode
            self.active = active
        }
    }

    /// One edge that became true during this evaluation.
    ///
    /// `key` is the caller's de-duplication handle. `line` is empty when the key must still
    /// be consumed — a superseded tier, a disabled setting, or a credit already counted — so
    /// that nothing is said now and nothing can be said later for the same key. `accountId`
    /// is carried separately so consumers never have to parse it back out of the key.
    public struct Event: Sendable, Equatable {
        public let accountId: String
        public let key: String
        public let line: String

        public init(accountId: String, key: String, line: String) {
            self.accountId = accountId
            self.key = key
            self.line = line
        }
    }

    /// The result of one pass: updated state to persist, edges to consider, and still-true
    /// facts to display.
    ///
    /// `states` covers every account processed this pass plus any carried over from before,
    /// sorted by account id because dictionary order is unstable. `events` follow the
    /// supplied account order, each account contributing its quota tiers, then
    /// reset-approaching, then credit-expiring edges. `standingFindings` state levels, not
    /// transitions, so the caller renders them rather than notifying them.
    public struct Outcome: Sendable, Equatable {
        public let states: [AccountState]
        public let events: [Event]
        public let standingFindings: [String]

        public init(states: [AccountState], events: [Event], standingFindings: [String]) {
            self.states = states
            self.events = events
            self.standingFindings = standingFindings
        }
    }

    /// Evaluates one sync.
    ///
    /// Failed and missing snapshots are transparent: state rides through untouched and no
    /// events fire, so a network blip can neither end a low-quota episode nor manufacture an
    /// edge. A snapshot no newer than the last one processed is a replay and is equally
    /// silent. Standing findings survive both guards because they state present facts, not
    /// transitions, and a fact half-mentioned looks like it stopped being true.
    public static func evaluate(
        accounts: [AccountSummary],
        settings: NotificationSettings,
        states: [String: AccountState],
        now: Date
    ) -> Outcome {
        var carried = states
        var events: [Event] = []
        var findings: [String] = []

        for account in accounts {
            let id = account.accountId
            var state = carried[id] ?? AccountState(accountId: id)
            // Computed once per account and consulted at each place that would SAY something.
            // Deliberately not a `continue` at the top of the loop: a muted account still has
            // its episodes and its last-processed instant advanced, so unmuting it resumes from
            // where it is now rather than replaying every edge it passed while silent.
            let muted = settings.mutedAccountIDs.contains(id)

            guard let snapshot = account.snapshot else {
                // No data at all, so no conclusions: the account is neither better nor worse.
                continue
            }

            if snapshot.failed {
                // The payload of a failed fetch cannot be trusted for positive findings,
                // so only the failure itself is interpreted. Expired credentials are the
                // one failure the user must personally fix, and that is a standing fact.
                if !muted,
                    settings.notifyOnAuthExpired,
                    meansSignInExpired(snapshot.errorMessage)
                {
                    // No separator, unlike every other line: this one reads as a sentence, and
                    // it is worded identically on Android. Two platforms phrasing the same
                    // condition differently is the divergence this shared kit exists to stop.
                    findings.append("\(account.label) needs to be reconnected")
                }
                continue
            }

            if let lastProcessed = state.lastProcessedFetchedAt,
               snapshot.fetchedAt <= lastProcessed {
                // Already fired once: re-delivering the same snapshot must not repeat its
                // QUOTA edges, but the facts it states are as true as they were — and its
                // deadlines are a property of the clock, not of the fetch. A reset two hours
                // away at fetch time with a thirty-minute lead was never announced when the
                // next fetch came three hours later. The keys carry the instant, so this
                // cannot say anything twice. Same rule as Android.
                if !muted {
                    appendCreditsAvailableFinding(
                        accountLabel: account.label,
                        settings: settings,
                        snapshot: snapshot,
                        now: now,
                        into: &findings
                    )
                }
                appendResetApproachingEvents(
                    accountId: id, accountLabel: account.label, windows: snapshot.windows,
                    settings: settings, now: now, into: &events)
                appendCreditExpiringEvents(
                    accountId: id, accountLabel: account.label, snapshot: snapshot,
                    settings: settings, now: now, into: &events)
                continue
            }

            // Each window decides for itself — see `AccountState.windows` for what judging the
            // account by its worst window cost.
            let keys = windowKeys(snapshot.windows)
            for window in snapshot.windows {
                guard let id = keys[window.id] else { continue }
                var windowState = state.windows[id] ?? WindowState()
                let remaining = window.remainingPercent
                // The window's own severity, which counts a known 0 % as exhausted whether or
                // not the provider also set its flag. Reading the flag alone reached only the
                // warning and critical tiers for a window at 0 %, so a user with just the
                // exhausted alert switched on heard nothing when a limit ran out. Android reads
                // the severity, and both must say the same thing about the same payload.
                let exhausted = window.severity == .exhausted

                // An unknown percentage does not hold a window low: unknown is an absence of
                // evidence, and the account already reads as error on screen.
                let recovered = !exhausted && (remaining ?? warningPercent) >= warningPercent
                if recovered {
                    if windowState.active {
                        windowState.active = false
                        state.windows[id] = windowState
                    }
                    continue
                }

                let reached: [Tier]
                if exhausted {
                    reached = [.warning, .critical, .exhausted]
                } else if let remaining, remaining < criticalPercent {
                    reached = [.warning, .critical]
                } else if let remaining, remaining < warningPercent {
                    reached = [.warning]
                } else {
                    continue
                }

                // An episode is the unit of deduplication. Starting one here — rather than at
                // the first notification — keeps the keys stable even while every setting is off.
                if !windowState.active {
                    windowState = WindowState(episode: windowState.episode + 1, active: true)
                }
                state.windows[id] = windowState

                appendQuotaEvents(
                    accountId: account.accountId,
                    accountLabel: account.label,
                    windowKey: id,
                    windowLabel: window.label,
                    episode: windowState.episode,
                    reached: reached,
                    settings: settings,
                    into: &events
                )
            }
            state.lowQuotaActive = state.windows.values.contains { $0.active }
            state.lowQuotaEpisode = max(state.lowQuotaEpisode, state.windows.values.map(\.episode).max() ?? 0)

            appendResetApproachingEvents(
                accountId: id,
                accountLabel: account.label,
                windows: snapshot.windows,
                settings: settings,
                now: now,
                into: &events
            )
            appendCreditExpiringEvents(
                accountId: id,
                accountLabel: account.label,
                snapshot: snapshot,
                settings: settings,
                now: now,
                into: &events
            )
            if !muted {
                appendCreditsAvailableFinding(
                    accountLabel: account.label,
                    settings: settings,
                    snapshot: snapshot,
                    now: now,
                    into: &findings
                )
            }

            state.lastProcessedFetchedAt = snapshot.fetchedAt
            carried[id] = state
        }

        let orderedStates = carried.values.sorted { $0.accountId < $1.accountId }
        return Outcome(
            states: orderedStates,
            // Silenced here rather than at each `append`, as on Android: an emit site added
            // later is covered by this without anyone remembering to guard it.
            //
            // Silenced by BLANKING the line, not by dropping the event. A mute is a disabled
            // setting scoped to one account, and it follows the rule every disabled setting
            // here follows: the key is still claimed, so switching back on delivers what
            // happens NEXT rather than a threshold crossed while the user had asked not to
            // hear about it. Dropping the events left those keys unclaimed; the first sync
            // after unmuting then found them, with text, and announced a dip from days ago.
            events: events.map { event in
                settings.mutedAccountIDs.contains(event.accountId)
                    ? Event(accountId: event.accountId, key: event.key, line: "")
                    : event
            },
            standingFindings: findings)
    }

    // MARK: Quota tiers

    /// The three low-quota tiers.
    ///
    /// A tier is a deduplication key, not a message. Whether the user hears anything depends
    /// on their settings; *what* they hear describes the condition actually reached, so an
    /// exhausted limit is never announced as "less than 20 % remaining".
    private enum Tier: CaseIterable {
        case warning, critical, exhausted

        var key: String {
            switch self {
            case .warning: return "warning"
            case .critical: return "critical"
            case .exhausted: return "exhausted"
            }
        }

        func enabled(_ settings: NotificationSettings) -> Bool {
            switch self {
            case .warning: return settings.notifyBelow20Percent
            case .critical: return settings.notifyBelow10Percent
            case .exhausted: return settings.notifyOnExhausted
            }
        }

        func message(_ name: String, _ label: String) -> String {
            switch self {
            case .warning: return "\(name) · \(label): less than 20% remaining"
            case .critical: return "\(name) · \(label): less than 10% remaining"
            case .exhausted: return "\(name) · \(label) exhausted"
            }
        }
    }

    /// Emits the quota-tier edges for one active episode.
    ///
    /// Reaching a tier consumes every weaker one too, so a fast burn produces one line rather
    /// than a stack of them, and a later partial recovery cannot warn about a limit the user
    /// has already watched run out.
    ///
    /// Two rules here exist because getting them wrong produced silence, which is the worst
    /// failure a quota alert can have. Both were found by auditing the Kotlin original against
    /// settings combinations rather than the happy path.
    ///
    /// The message describes the strongest tier REACHED and is spoken if ANY reached tier is
    /// enabled. Routing each message to its own tier's setting instead meant a user with only
    /// the 20 % alert on heard nothing when quota crashed straight past 10 % — the urgent case
    /// was the silent one — and a user with the exhausted alert off heard nothing at all when
    /// a limit ran out.
    ///
    /// Every reached tier is always claimed, even when nothing is spoken. Dropping the claim
    /// would deliver a stale alert the moment the setting was switched on, which is the
    /// backlog this design exists to prevent.
    private static func appendQuotaEvents(
        accountId: String,
        accountLabel: String,
        windowKey: String,
        windowLabel: String,
        episode: Int,
        reached: [Tier],
        settings: NotificationSettings,
        into events: inout [Event]
    ) {
        guard let condition = reached.last else { return }

        let line = reached.contains(where: { $0.enabled(settings) })
            ? condition.message(accountLabel, windowLabel)
            : ""

        for tier in reached {
            events.append(Event(
                accountId: accountId,
                // The window's identity is part of the key, as it is on Android:
                // "<account>|<category>:<label>|<episode>|<tier>".
                key: "\(accountId)|\(windowKey)|\(episode)|\(tier.key)",
                // Only the strongest reached tier carries the text: the weaker ones exist to be
                // consumed so a later dip cannot re-announce a threshold already passed.
                line: tier == condition ? line : ""))
        }
    }

    /// Identities for every window in one snapshot, keyed by the window's own id.
    ///
    /// Category and label together, as on Android, because ids are provider-assigned and a
    /// provider that renumbers them would re-arm every reset alert it has already sent.
    ///
    /// That pair is not guaranteed unique, though, and nothing upstream promises it is. Two
    /// windows of one account sharing a category and a label collapsed to ONE identity, so
    /// they shared an episode and its tier keys — the bug per-window episodes exist to fix,
    /// reappearing inside an aliasing group: the exhausted window claimed the warning key
    /// silently, and the other window's real warning had nothing left to say.
    ///
    /// So the id is used, but only where it must be. A pair naming exactly one window keeps
    /// the stable spelling; only a colliding group appends the id. Aliased windows may re-arm
    /// if the provider renumbers them, which is a repeated notification — where not
    /// disambiguating them is silence about an exhausted limit.
    private static func windowKeys(_ windows: [UsageWindow]) -> [String: String] {
        var counts: [String: Int] = [:]
        for window in windows {
            counts["\(window.category.rawValue):\(window.label.canonical)", default: 0] += 1
        }
        var keys: [String: String] = [:]
        for window in windows {
            let base = "\(window.category.rawValue):\(window.label.canonical)"
            keys[window.id] = counts[base] == 1 ? base : "\(base)#\(window.id)"
        }
        return keys
    }

    // MARK: Resets and credits

    /// Emits reset-approaching edges.
    ///
    /// Keyed on the reset timestamp, because that is the only cycle identifier the providers
    /// give. A provider correcting the timestamp therefore looks like a new cycle and can
    /// notify twice — the honest limit of what these payloads support, and better than keying
    /// on the window alone, which would go silent for every later reset.
    ///
    /// Claimed even while the setting is off, so switching it on delivers what happens next
    /// rather than a heads-up for a reset that has been approaching since the last sync. Only
    /// the text is withheld.
    ///
    /// The lead interval is measured against `now`, not the snapshot's own `fetchedAt`. When
    /// evaluation runs late those diverge, and reading from `fetchedAt` would announce "resets
    /// in about 20 minutes" for a reset that happened an hour ago. A heads-up that arrives
    /// late and wrong is worse than one that does not arrive.
    private static func appendResetApproachingEvents(
        accountId: String,
        accountLabel: String,
        windows: [UsageWindow],
        settings: NotificationSettings,
        now: Date,
        into events: inout [Event]
    ) {
        let lead = TimeInterval(settings.resetApproachingMinutes) * 60
        let keys = windowKeys(windows)

        for window in windows {
            guard let resetAt = window.resetAt else { continue }
            let remaining = resetAt.timeIntervalSince(now)
            guard remaining > 0, remaining <= lead else { continue }

            let minutes = Int(ceil(remaining / 60))
            events.append(Event(
                accountId: accountId,
                // Milliseconds as an integer, which is what Kotlin's `resetAt.toString()`
                // spells — its resetAt is already a Long of epoch milliseconds. This used to
                // interpolate a `TimeInterval`, so the same reset produced
                // "…|1757003600.0" here and "…|1757003600000" there. The keys are per
                // platform, so that divergence notified nobody twice; it did mean the two
                // ledgers could never be compared, and a shared fixture could not pin either.
                key: "\(accountId)|\(keys[window.id] ?? "")"
                    + "|reset-approaching|\(Int((resetAt.timeIntervalSince1970 * 1000).rounded()))",
                line: settings.notifyOnResetApproaching
                    ? "\(accountLabel) · \(window.label) resets in about \(minutes) minutes"
                    : ""))
        }
    }

    /// Emits one edge per reset credit about to lapse, each naming its own deadline.
    ///
    /// A counted summary cannot be made exactly-once by a pure evaluator. Hanging it on the
    /// first expiring credit meant a second credit entering the window later found that key
    /// already claimed, so the only line carrying text was discarded and the new credit lapsed
    /// in silence. Keying it by the whole set instead re-announces the moment the set shrinks.
    /// A credit's own id is the only key meaning exactly "this credit, once".
    ///
    /// The cost is two lines when two credits lapse together, and they are not redundant: they
    /// carry different deadlines, which is the fact the user needs in order to act.
    ///
    /// Only credits with a real expiry and an available status qualify. A count without rows
    /// cannot support this at all — a number says nothing about when anything expires, and
    /// guessing would put a deadline on screen the provider never stated.
    private static func appendCreditExpiringEvents(
        accountId: String,
        accountLabel: String,
        snapshot: UsageSnapshot,
        settings: NotificationSettings,
        now: Date,
        into events: inout [Event]
    ) {
        let lead = TimeInterval(settings.resetCreditExpiryLeadMinutes) * 60

        let expiring = snapshot.resetCredits.filter { credit in
            guard let expiresAt = credit.expiresAt else { return false }
            let remaining = expiresAt.timeIntervalSince(now)
            guard remaining > 0, remaining <= lead else { return false }
            guard credit.status.meansAvailable else { return false }
            if let grantedAt = credit.grantedAt, grantedAt > now { return false }
            return true
        }

        for credit in expiring.sorted(by: { ($0.expiresAt ?? now) < ($1.expiresAt ?? now) }) {
            let remaining = (credit.expiresAt ?? now).timeIntervalSince(now)
            events.append(Event(
                accountId: accountId,
                key: "\(accountId)|\(credit.id)|credit-expiring",
                line: settings.notifyOnResetCreditExpiring
                    ? "\(accountLabel) · a reset credit expires in "
                        + Countdown.format(seconds: Int(remaining))
                    : ""))
        }
    }

    /// A still-true fact rather than an edge.
    ///
    /// It repeats on every sync by design: "you hold two credits" stays true until one is
    /// spent, and the notification is replaced rather than stacked, so a standing fact stays
    /// visible without alerting again.
    private static func appendCreditsAvailableFinding(
        accountLabel: String,
        settings: NotificationSettings,
        snapshot: UsageSnapshot,
        now: Date,
        into findings: inout [String]
    ) {
        guard settings.notifyOnResetCreditAvailable else { return }

        // The snapshot's own spendable figure, not a count of rows re-judged here.
        //
        // Two reasons, and each one alone is sufficient. Codex reports a COUNT and no rows —
        // that is the shape of the copy embedded in its usage payload — so counting rows told a
        // user holding two credits that they had none. And "spendable" already encodes which
        // credits actually apply to the limit in force; re-deciding it at this call site with a
        // cruder predicate meant announcing credits the provider would refuse to apply.
        let count = snapshot.spendableResetCredits(at: now)
        guard count > 0 else { return }

        let noun = count == 1 ? "reset credit" : "reset credits"
        findings.append("\(accountLabel) · \(count) \(noun) available")
    }
}
