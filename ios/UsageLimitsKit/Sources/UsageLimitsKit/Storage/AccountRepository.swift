import Foundation

/// The accounts the user has connected, and the last usage read for each.
///
/// A JSON file rather than a database, because this holds a handful of records with no queries
/// worth indexing — and because the widget process already reads a file from the same shared
/// container, so there is one storage story rather than two.
///
/// It holds **no token material of any kind**. An account carries a `credentialReference`, which
/// names an entry in the keychain; the tokens themselves never enter this file. That separation
/// is the structural reason the cache can be written into a container the widget can read.
public actor AccountRepository {

    /// The last usage read for one account, or nothing if it has never synced.
    private struct Stored: Codable, Sendable {
        var account: ProviderAccount
        var snapshot: UsageSnapshot?

        /// Where the user dragged this account, or nil if they never have.
        ///
        /// Stored beside the account rather than on it, because it is a fact about this list
        /// and not about the account: the widget decodes `ProviderAccount` and has no business
        /// being handed a field it cannot act on. Nil rather than 0 so an account added after a
        /// reorder joins the end of the list instead of tying for the front.
        var sortOrder: Int?

        private enum CodingKeys: String, CodingKey { case account, snapshot, sortOrder }

        init(account: ProviderAccount, snapshot: UsageSnapshot?, sortOrder: Int? = nil) {
            self.account = account
            self.snapshot = snapshot
            self.sortOrder = sortOrder
        }

        /// Lenient on the new key, for the reason spelled out on `load`: a register written
        /// before manual ordering existed has no `sortOrder`, and the synthesised decoder would
        /// reject the whole file — which this type treats as a register it must not overwrite,
        /// stranding every account the user had connected.
        init(from decoder: any Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            account = try c.decode(ProviderAccount.self, forKey: .account)
            snapshot = try c.decodeIfPresent(UsageSnapshot.self, forKey: .snapshot)
            sortOrder = try c.decodeIfPresent(Int.self, forKey: .sortOrder)
        }
    }

    private let fileURL: URL
    private var records: [String: Stored] = [:]
    private var isLoaded = false
    /// Set while the file exists and could not be read or decoded. Cleared by a successful load.
    private var lastLoadFailure: AccountStoreError?

    /// Deliberately does no I/O.
    ///
    /// This actor is built during app launch, and an actor's initialiser is nonisolated: it runs
    /// inline on whichever thread constructed it, which here is the main thread before the first
    /// frame. Reading and decoding a file there is a stall the user watches. The read happens on
    /// this actor's own executor instead, at the first call that needs it.
    /// The file this repository writes, named once so a caller that needs to ask whether it
    /// exists — the install check does — cannot spell it differently.
    public static let fileName = "accounts.json"

    public init(directory: URL, fileName: String = AccountRepository.fileName) {
        self.fileURL = directory.appendingPathComponent(fileName)
    }

    /// Loads once, and treats "could not read" as unfinished rather than as done.
    ///
    /// This used to set `isLoaded = true` before knowing the outcome, while `load` turned every
    /// failure into an empty dictionary. One transient read error therefore emptied the list for
    /// the lifetime of the process and never tried again — and the file is not merely a usage
    /// cache that a sync could rebuild. It is the ACCOUNT REGISTER: it names the credential
    /// reference for each account, so losing it strands keychain entries no row points at any
    /// more. Worse, the next `upsert` would then persist a candidate built on an empty base and
    /// overwrite the real register with one account.
    ///
    /// A missing file is a genuine empty state and completes the load. A file that exists and
    /// will not read, or will not decode, leaves this unloaded so the next call retries, and
    /// arms the guard that stops a write from destroying what could not be read.
    private func ensureLoaded() {
        guard !isLoaded else { return }
        switch Self.load(from: fileURL) {
        case .empty:
            records = [:]
            isLoaded = true
            lastLoadFailure = nil
        case .loaded(let stored):
            records = stored
            isLoaded = true
            lastLoadFailure = nil
        case .unreadable(let reason):
            lastLoadFailure = reason
        }
    }

    /// Refuses a mutation while the existing register is unaccounted for.
    ///
    /// Writing persists the whole file, so a write on top of a failed read replaces a register
    /// that may hold several accounts with whatever this one call knows about. Throwing keeps
    /// the file intact and surfaces the storage fault, which is recoverable; the overwrite is
    /// not.
    private func requireLoaded() throws {
        ensureLoaded()
        if let reason = lastLoadFailure { throw reason }
    }

    // MARK: - Reading

    public func accounts() -> [ProviderAccount] {
        ensureLoaded()
        return ordered().map(\.account)
    }

    public func usage() -> [AccountUsage] {
        ensureLoaded()
        return ordered().map { AccountUsage(account: $0.account, snapshot: $0.snapshot) }
    }

    /// The user's own order where they have one, oldest first everywhere else.
    ///
    /// Accounts never dragged sort after every dragged one and keep their age order, so adding
    /// an account to a hand-arranged list appends it rather than dropping it into the middle.
    /// The id is the final tiebreak so the list a user sees cannot reshuffle because a
    /// dictionary rehashed.
    private func ordered() -> [Stored] {
        records.values.sorted { Self.sortKey($0) < Self.sortKey($1) }
    }

    private static func sortKey(_ stored: Stored) -> (Int, Date, String) {
        (stored.sortOrder ?? Int.max, stored.account.createdAt, stored.account.id)
    }

    // MARK: - Writing

    public func upsert(_ account: ProviderAccount) throws {
        try requireLoaded()
        // The snapshot is kept across an account being re-saved. Re-authenticating an account
        // updates its tokens, not its quota, and blanking the numbers would make a successful
        // sign-in look like a regression.
        var updated = records
        // The snapshot AND the place in the list survive a re-save: re-authenticating an
        // account changes its tokens, not where the user put it.
        updated[account.id] = Stored(
            account: account,
            snapshot: records[account.id]?.snapshot,
            sortOrder: records[account.id]?.sortOrder)
        try persist(updated)
    }

    public func remove(id: String) throws {
        try requireLoaded()
        var updated = records
        updated[id] = nil
        try persist(updated)
    }

    /// Records the result of one sync.
    ///
    /// A failure keeps the previous windows and marks the snapshot failed, rather than replacing
    /// them with nothing. Showing yesterday's numbers with a staleness marker is far more useful
    /// than blanking a card because one refresh did not come back — and the caller can tell the
    /// two apart, which it could not if the windows were simply gone.
    public func record(_ outcome: SyncOutcome, at time: Date) throws {
        try requireLoaded()
        var updated = records
        switch outcome {
        case .success(let accountID, let result):
            guard var stored = updated[accountID] else { return }
            stored.snapshot = UsageSnapshot(
                accountID: accountID,
                fetchedAt: time,
                status: .ok,
                windows: result.windows,
                resetCredits: result.resetCredits,
                resetCreditCount: result.resetCreditCount,
                // Carried through rather than dropped. The client goes to the trouble of reading
                // this off the one source that reports it; losing it here would leave the redeem
                // control gated on the held count, which is the exact bug that reading it was
                // meant to fix.
                applicableResetCreditCount: result.applicableResetCreditCount,
                errorMessage: nil)
            if let plan = result.plan, plan != stored.account.plan {
                stored.account = stored.account.withPlan(plan)
            }
            updated[accountID] = stored

        case .failure(let accountID, let message):
            guard var stored = updated[accountID] else { return }
            let previous = stored.snapshot
            stored.snapshot = UsageSnapshot(
                accountID: accountID,
                // The original fetch time is kept: the data is exactly as old as it was, and the
                // failure is carried separately. Stamping it with now would make stale numbers
                // look freshly confirmed.
                fetchedAt: previous?.fetchedAt ?? time,
                status: .failed,
                windows: previous?.windows ?? [],
                resetCredits: previous?.resetCredits ?? [],
                resetCreditCount: previous?.resetCreditCount,
                applicableResetCreditCount: previous?.applicableResetCreditCount,
                errorMessage: message)
            updated[accountID] = stored
        }

        try persist(updated)
    }

    /// Writes the order the user dragged the accounts into.
    ///
    /// The whole list is renumbered rather than two entries swapped, because a partial write
    /// leaves an order that is neither the old one nor the new one — and the list is short
    /// enough that renumbering it costs nothing.
    ///
    /// Ids this caller does not know about are left alone: an account added on another screen
    /// while the overview was open keeps whatever place it had rather than being renumbered to
    /// the front. Same rule as the Android repository.
    public func reorder(ids: [String]) throws {
        try requireLoaded()
        var updated = records
        for (index, id) in ids.enumerated() {
            guard var stored = updated[id] else { continue }
            stored.sortOrder = index
            updated[id] = stored
        }
        try persist(updated)
    }

    // MARK: - Persistence

    /// Writes a candidate state, and only then makes it the state readers see.
    ///
    /// Mutating `records` first and writing afterwards meant a failed write left the change
    /// live in memory: the UI showed an account as removed, or a snapshot as updated, while
    /// the file on disk still said otherwise — and the next launch reverted it. Atomic file
    /// replacement protects the FILE from a half-write; it does nothing for the actor's own
    /// dictionary. Committing last is what keeps the two agreeing.
    ///
    /// There is deliberately no suspension point between the write and the commit, so no other
    /// call on this actor can observe the interval between them.
    private func persist(_ updated: [String: Stored]) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]

        let ordered = updated.values.sorted { Self.sortKey($0) < Self.sortKey($1) }

        // Atomic, because the widget process can be reading the same container while this
        // writes. A half-written file decodes to nothing, and a list that empties itself
        // occasionally is worse than one that lags.
        try encoder
            .encode(ordered)
            .write(to: fileURL, options: ContainerFile.writingOptions)

        records = updated
    }

    /// What a read attempt found.
    private enum Loaded {
        /// No file. A first launch, or a container that has never been written.
        case empty
        case loaded([String: Stored])
        /// The file is there and this process could not turn it into records.
        case unreadable(AccountStoreError)
    }

    private static func load(from url: URL) -> Loaded {
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            // `fileExists` answers even when the contents cannot be opened, so a protected or
            // briefly unavailable file is not mistaken for one that was never written.
            return FileManager.default.fileExists(atPath: url.path)
                ? .unreadable(.unreadable)
                : .empty
        }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let stored = try? decoder.decode([Stored].self, from: data) else {
            // Deliberately NOT empty. Undecodable content is a register this build cannot
            // read, not an absence of accounts, and the difference decides whether the next
            // write preserves it or destroys it.
            return .unreadable(.corrupt)
        }
        // A duplicate id would silently drop an account through `uniqueKeysWithValues`, and
        // uniquing keeps the later record rather than trapping.
        return .loaded(Dictionary(stored.map { ($0.account.id, $0) }, uniquingKeysWith: { _, later in later }))
    }
}

/// Why the account register could not be used.
public enum AccountStoreError: Error, Equatable, Sendable {
    /// The file is present and could not be opened — data protection, or a transient I/O fault.
    case unreadable
    /// The file was read and could not be decoded.
    case corrupt
}

/// Adapts the repository to the sync engine's sink.
///
/// Separate from the repository so the engine depends on the narrow protocol rather than on a
/// storage type, and so a failure to persist an outcome can be swallowed here — the sink is
/// best-effort by contract, and one unwritable file must not fail the account it belongs to.
public struct RepositorySink: SyncSink {
    private let repository: AccountRepository

    public init(repository: AccountRepository) {
        self.repository = repository
    }

    public func record(_ outcome: SyncOutcome, at time: Date) async {
        try? await repository.record(outcome, at: time)
    }
}

private extension ProviderAccount {
    /// The provider's own name for the plan, once a sync has reported one.
    func withPlan(_ plan: String) -> ProviderAccount {
        ProviderAccount(
            id: id,
            provider: provider,
            externalAccountID: externalAccountID,
            email: email,
            displayName: displayName,
            plan: plan,
            credentialReference: credentialReference,
            createdAt: createdAt,
            lastSuccessfulSync: lastSuccessfulSync,
            attributes: attributes)
    }
}
