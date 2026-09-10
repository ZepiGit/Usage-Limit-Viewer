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
    }

    private let fileURL: URL
    private var records: [String: Stored] = [:]
    private var isLoaded = false

    /// Deliberately does no I/O.
    ///
    /// This actor is built during app launch, and an actor's initialiser is nonisolated: it runs
    /// inline on whichever thread constructed it, which here is the main thread before the first
    /// frame. Reading and decoding a file there is a stall the user watches. The read happens on
    /// this actor's own executor instead, at the first call that needs it.
    public init(directory: URL, fileName: String = "accounts.json") {
        self.fileURL = directory.appendingPathComponent(fileName)
    }

    private func ensureLoaded() {
        guard !isLoaded else { return }
        isLoaded = true
        records = Self.load(from: fileURL)
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

    /// Oldest first, so the list a user sees does not reshuffle because a dictionary rehashed.
    private func ordered() -> [Stored] {
        records.values.sorted {
            ($0.account.createdAt, $0.account.id) < ($1.account.createdAt, $1.account.id)
        }
    }

    // MARK: - Writing

    public func upsert(_ account: ProviderAccount) throws {
        ensureLoaded()
        // The snapshot is kept across an account being re-saved. Re-authenticating an account
        // updates its tokens, not its quota, and blanking the numbers would make a successful
        // sign-in look like a regression.
        records[account.id] = Stored(account: account, snapshot: records[account.id]?.snapshot)
        try persist()
    }

    public func remove(id: String) throws {
        ensureLoaded()
        records[id] = nil
        try persist()
    }

    /// Records the result of one sync.
    ///
    /// A failure keeps the previous windows and marks the snapshot failed, rather than replacing
    /// them with nothing. Showing yesterday's numbers with a staleness marker is far more useful
    /// than blanking a card because one refresh did not come back — and the caller can tell the
    /// two apart, which it could not if the windows were simply gone.
    public func record(_ outcome: SyncOutcome, at time: Date) throws {
        ensureLoaded()
        switch outcome {
        case .success(let accountID, let result):
            guard var stored = records[accountID] else { return }
            stored.snapshot = UsageSnapshot(
                accountID: accountID,
                fetchedAt: time,
                status: .ok,
                windows: result.windows,
                resetCredits: result.resetCredits,
                resetCreditCount: result.resetCreditCount,
                errorMessage: nil)
            if let plan = result.plan, plan != stored.account.plan {
                stored.account = stored.account.withPlan(plan)
            }
            records[accountID] = stored

        case .failure(let accountID, let message):
            guard var stored = records[accountID] else { return }
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
                errorMessage: message)
            records[accountID] = stored
        }

        try persist()
    }

    // MARK: - Persistence

    private func persist() throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]

        // Atomic, because the widget process can be reading the same container while this
        // writes. A half-written file decodes to nothing, and a list that empties itself
        // occasionally is worse than one that lags.
        try encoder
            .encode(ordered())
            .write(to: fileURL, options: ContainerFile.writingOptions)
    }

    private static func load(from url: URL) -> [String: Stored] {
        guard let data = try? Data(contentsOf: url) else { return [:] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601

        // A cache that cannot be read is treated as empty rather than fatal. It holds no
        // tokens, so nothing is lost that a sync cannot rebuild — whereas refusing to launch
        // over an unreadable cache would strand the user completely.
        guard let stored = try? decoder.decode([Stored].self, from: data) else { return [:] }
        return Dictionary(uniqueKeysWithValues: stored.map { ($0.account.id, $0) })
    }
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
