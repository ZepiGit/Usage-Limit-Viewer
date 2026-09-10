import Foundation

/// The providers this app can monitor.
///
/// `id` is persisted, so these string values are part of the on-disk contract and must not be
/// renamed without a migration.
public enum ProviderID: String, CaseIterable, Sendable, Codable {
    case codex
    case claude
    case antigravity
    case xai

    public var displayName: String {
        switch self {
        case .codex: return "OpenAI Codex"
        case .claude: return "Claude"
        case .antigravity: return "Antigravity"
        case .xai: return "Grok"
        }
    }
}

/// A logged-in account, normalised across providers.
///
/// Identity is `provider` + `externalAccountID`, never the address alone: providers let the
/// same address back several accounts, and an address can change while the account does not.
///
/// Carries no token. Credentials live only in the Keychain, addressed by
/// `credentialReference` — the same split the Android app uses, and the reason the widget
/// can read this type freely.
public struct ProviderAccount: Sendable, Codable, Identifiable, Equatable {
    public let id: String
    public let provider: ProviderID
    public let externalAccountID: String
    public let email: String?
    public let displayName: String?
    public let plan: String?
    public let credentialReference: String
    public let createdAt: Date
    public let lastSuccessfulSync: Date?
    /// Non-secret provider extras, e.g. the Antigravity GCP project id.
    public let attributes: [String: String]

    public init(
        id: String,
        provider: ProviderID,
        externalAccountID: String,
        email: String?,
        displayName: String?,
        plan: String?,
        credentialReference: String,
        createdAt: Date,
        lastSuccessfulSync: Date?,
        attributes: [String: String] = [:]
    ) {
        self.id = id
        self.provider = provider
        self.externalAccountID = externalAccountID
        self.email = email
        self.displayName = displayName
        self.plan = plan
        self.credentialReference = credentialReference
        self.createdAt = createdAt
        self.lastSuccessfulSync = lastSuccessfulSync
        self.attributes = attributes
    }

    /// `m***@example.com` — what the UI shows instead of the full address.
    public var maskedEmail: String? {
        guard let email, let at = email.firstIndex(of: "@"), at != email.startIndex else {
            return email
        }
        return "\(email[email.startIndex])***\(email[at...])"
    }

    public var label: String {
        if let displayName, !displayName.isEmpty { return displayName }
        if let maskedEmail { return maskedEmail }
        return String(externalAccountID.prefix(12))
    }
}

/// Why a snapshot looks the way it does. Drives the stale and failed banners.
public enum SnapshotStatus: String, Sendable, Codable {
    case ok
    case partial
    case failed
}

/// The result of one usage fetch for one account.
public struct UsageSnapshot: Sendable, Codable, Equatable {
    public let accountID: String
    public let fetchedAt: Date
    public let status: SnapshotStatus
    public let windows: [UsageWindow]
    public let resetCredits: [ResetCredit]
    /// Provider-reported count, authoritative over `resetCredits.count` when present: the list
    /// can be truncated or filtered while the count stays exact.
    public let resetCreditCount: Int?

    /// How many of those credits the provider says can be applied RIGHT NOW.
    ///
    /// A different question from how many are held, and the one a redeem control has to ask.
    /// Codex reports both: an account can hold three credits and be able to spend none of them,
    /// because none applies to the limit currently in force. Nil means the provider did not say.
    public let applicableResetCreditCount: Int?

    public let errorMessage: String?

    public init(
        accountID: String,
        fetchedAt: Date,
        status: SnapshotStatus,
        windows: [UsageWindow],
        resetCredits: [ResetCredit] = [],
        resetCreditCount: Int? = nil,
        applicableResetCreditCount: Int? = nil,
        errorMessage: String? = nil
    ) {
        self.accountID = accountID
        self.fetchedAt = fetchedAt
        self.status = status
        self.windows = windows
        self.resetCredits = resetCredits
        self.resetCreditCount = resetCreditCount
        self.applicableResetCreditCount = applicableResetCreditCount
        self.errorMessage = errorMessage
    }

    /// Decoded field by field so a cache written before `applicableResetCreditCount` existed
    /// still reads. The synthesised decoder demands every key, and an undecodable cache is
    /// treated as empty — which would silently drop every account the user had connected.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            accountID: try container.decode(String.self, forKey: .accountID),
            fetchedAt: try container.decode(Date.self, forKey: .fetchedAt),
            status: try container.decode(SnapshotStatus.self, forKey: .status),
            windows: try container.decodeIfPresent([UsageWindow].self, forKey: .windows) ?? [],
            resetCredits: try container.decodeIfPresent(
                [ResetCredit].self, forKey: .resetCredits) ?? [],
            resetCreditCount: try container.decodeIfPresent(
                Int.self, forKey: .resetCreditCount),
            applicableResetCreditCount: try container.decodeIfPresent(
                Int.self, forKey: .applicableResetCreditCount),
            errorMessage: try container.decodeIfPresent(String.self, forKey: .errorMessage))
    }

    /// Whether the last fetch failed.
    ///
    /// A named property rather than a status comparison at each call site, because "did this
    /// refresh work" is asked from several places and each one spelling it out invites one of
    /// them to spell it differently.
    public var failed: Bool { status == .failed }

    /// How many credits the account HOLDS — what a balance line shows.
    ///
    /// The provider's count is authoritative when it states one, because the row list can be
    /// truncated or filtered while the count stays exact. Falling back to the rows counts only
    /// the AVAILABLE ones: a spent credit is still listed, and counting it told a user with one
    /// consumed credit and nothing else that they held one to spend.
    public var heldResetCredits: Int {
        resetCreditCount
            ?? resetCredits.filter { $0.status.meansAvailable }.count
    }

    /// How many can be spent right now — what a redeem control is gated on.
    ///
    /// Deliberately separate from `heldResetCredits`, and this distinction is the whole reason
    /// the applicable count is fetched at all. An account can hold three credits and be able to
    /// apply none of them; gating the button on the held count offers a spend that the provider
    /// will refuse, against a balance the user watches go down.
    ///
    /// It falls back to the held count only when the provider stated no applicable figure, which
    /// is the older shape of the payload — there, held is the best evidence available.
    public var spendableResetCredits: Int { spendableResetCredits(at: Date()) }

    /// Credits available AND not past their own expiry at `now`. A snapshot is a photograph:
    /// a credit that read "available, expires at 14:00" is still in it at 15:00, and counting
    /// it offered a spend the provider would refuse. A provider count is authoritative as of
    /// the fetch and is left alone — subtracting from it with a possibly truncated row list
    /// would invent a number. Same rule as Android.
    public func spendableResetCredits(at now: Date) -> Int {
        if let applicable = applicableResetCreditCount { return applicable }
        if let count = resetCreditCount, resetCredits.isEmpty { return count }
        return resetCredits.filter {
            $0.status.meansAvailable && ($0.expiresAt.map { $0 > now } ?? true)
        }.count
    }

    /// The window closest to running out — what a summary leads with.
    public var mostCritical: UsageWindow? {
        windows.min { ($0.remainingPercent ?? .greatestFiniteMagnitude)
                    < ($1.remainingPercent ?? .greatestFiniteMagnitude) }
    }

    public var nextReset: Date? { windows.compactMap(\.resetAt).min() }

    /// Severity ignoring age. Prefer `severity(at:)` wherever a clock is available.
    public var severity: Severity {
        if status == .failed { return .error }
        return windows.map(\.severity).max() ?? .error
    }

    /// Severity including staleness.
    ///
    /// Age has to be part of the verdict. Without it, a snapshot that stopped refreshing keeps
    /// whatever status it had when it last succeeded, so day-old numbers still read healthy —
    /// which is the exact failure this app exists to prevent.
    /// - Parameter staleAfter: how old this snapshot may be before it reads as stale. Passed in
    ///   rather than assumed, because the threshold follows the user's chosen sync interval: at
    ///   the three-hour setting the app offers, a fixed hour marks every account stale
    ///   permanently. The default is only for callers that have no settings to hand.
    public func severity(at now: Date, staleAfter: TimeInterval = Severity.staleAfter) -> Severity {
        let base = severity
        if base == .error { return base }
        return isStale(at: now, staleAfter: staleAfter) ? .stale : base
    }

    public func isStale(at now: Date, staleAfter: TimeInterval = Severity.staleAfter) -> Bool {
        now.timeIntervalSince(fetchedAt) >= staleAfter
    }
}

extension String {
    /// Whether a provider's status string means the credit can be spent.
    ///
    /// Trimmed and lowercased, matching Kotlin's `meansAvailable` exactly. Kotlin used to ask
    /// `equals(ignoreCase = true)`, which folds a dotless "\u{0131}" onto "i" where
    /// `lowercased()` does not — so "ava\u{0131}lable" counted as available on one platform
    /// and not the other. One rule now, and the stricter one: a string that is not
    /// "available" in some case is not available.
    var meansAvailable: Bool {
        trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "available"
    }
}

extension String {
    /// Composed form, matching Kotlin's `canonical()`.
    ///
    /// Swift already compares strings by canonical equivalence and Kotlin does not, so
    /// normalising is a no-op for comparison here — it is done anyway so the KEY BYTES are the
    /// same on both platforms. A key is written to a ledger and read back; two spellings of
    /// one label must not produce two ledger entries on one platform and one on the other.
    var canonical: String { precomposedStringWithCanonicalMapping }
}

/// A provider's raw plan string, as a subscriber would recognise it.
///
/// Providers disagree about case. Anthropic hands back `default_claude_max_5x`; OpenAI hands
/// back a bare `plus`. The Codex path used to pass its value straight through, so an account
/// read "OpenAI Codex plus" while the Claude beside it read "Claude Max 5×".
///
/// Read structurally rather than from a table of known tiers, for the reason
/// `ClaudeUsageParser.planFromTier` already gives: vendors add tiers, and a table renders a new
/// one as no plan at all — which looks exactly like an account with no subscription.
///
/// Separators are `_` and space, matching the Kotlin twin and `planFromTier` below it. A
/// hyphen is deliberately NOT a separator: no vendor issues a hyphenated tier id, and guessing
/// one would only make the two apps print different things for the same account.
public func planLabel(_ raw: String?) -> String? {
    let parts = (raw ?? "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
        .split(whereSeparator: { $0 == "_" || $0 == " " })
        .map(String.init)
    guard !parts.isEmpty else { return nil }

    // A trailing `5x` multiplies the tier — but only when there is a tier for it to multiply.
    // `claude_20x` strips to a bare `20x`, which is the whole name, and reading it as a
    // multiplier of nothing yields no plan at all.
    var words = parts
    var multiplier: String?
    if parts.count > 1, let last = parts.last, last.hasSuffix("x") {
        let digits = String(last.dropLast())
        // ASCII digits only. `isNumber` accepts every Unicode numeric category, so an
        // Arabic-Indic digit would become a multiplier here and not on Android.
        if !digits.isEmpty, digits.allSatisfy({ $0.isASCII && $0.isNumber }) {
            multiplier = digits
            words.removeLast()
        }
    }

    let name = words.map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " ")
    guard let multiplier else { return name }
    return "\(name) \(multiplier)×"
}
