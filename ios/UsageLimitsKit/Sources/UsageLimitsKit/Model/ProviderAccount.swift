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
    public var heldResetCredits: Int { resetCreditCount ?? resetCredits.count }

    /// How many can be spent right now — what a redeem control is gated on.
    ///
    /// Deliberately separate from `heldResetCredits`, and this distinction is the whole reason
    /// the applicable count is fetched at all. An account can hold three credits and be able to
    /// apply none of them; gating the button on the held count offers a spend that the provider
    /// will refuse, against a balance the user watches go down.
    ///
    /// It falls back to the held count only when the provider stated no applicable figure, which
    /// is the older shape of the payload — there, held is the best evidence available.
    public var spendableResetCredits: Int { applicableResetCreditCount ?? heldResetCredits }

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
    public func severity(at now: Date) -> Severity {
        let base = severity
        if base == .error { return base }
        return now.timeIntervalSince(fetchedAt) >= Severity.staleAfter ? .stale : base
    }

    public func isStale(at now: Date) -> Bool {
        now.timeIntervalSince(fetchedAt) >= Severity.staleAfter
    }
}
