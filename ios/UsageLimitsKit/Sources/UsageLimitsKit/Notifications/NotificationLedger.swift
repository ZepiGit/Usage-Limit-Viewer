import Foundation

/// What has already been said, and what each account was last known to be doing.
///
/// The evaluator is edge-triggered: it decides that an account has *become* low, not that it
/// *is* low. That only works if two things outlive the process — the per-account state the
/// evaluator carries, and the set of edge keys already delivered. Without the second, a crash
/// between deciding and notifying, or two syncs racing, repeats an alert; without the first,
/// every launch looks like a fresh dip and every threshold fires again.
///
/// A key is claimed BEFORE the notification is posted, never after. A file write cannot commit
/// atomically with a notification being scheduled, so one of the two orders has to lose: losing
/// an alert to a crash in that window is a single missed message, where the other order repeats
/// an alert on every sync until the write lands — which is what makes people turn notifications
/// off altogether.
public actor NotificationLedger {

    /// How long a delivered key is remembered.
    ///
    /// Long enough that no real episode can outlive its own key — a monthly window's exhaustion
    /// must not be re-announced because the record aged out mid-month — and bounded so the file
    /// cannot grow without limit on an install that runs for years.
    public static let retention: TimeInterval = 90 * 24 * 60 * 60

    /// One delivered edge. The account id is stored beside the timestamp rather than parsed
    /// back out of the key: keys are built by joining fields with `|`, and an account id is a
    /// provider-supplied string, so recovering the id by matching a prefix is a decision about
    /// somebody else's identifier format. Recording the id is one field and cannot be wrong.
    private struct Delivery: Codable, Sendable {
        let accountId: String
        let at: Date
    }

    private struct Stored: Codable, Sendable {
        var states: [NotificationEvaluator.AccountState]
        var delivered: [String: Delivery]
    }

    private let fileURL: URL
    private var loaded: Stored?
    private var stored: Stored {
        get {
            if let loaded { return loaded }
            let value = Self.load(from: fileURL)
            // A read that populates the cache: `stored` is only reached from this actor, so the
            // mutation cannot race.
            loaded = value
            return value
        }
        set { loaded = newValue }
    }

    /// No I/O here, for the reason given on `AccountRepository.init`: this is constructed on the
    /// launch path, and an actor initialiser runs inline on the thread that built it.
    public init(directory: URL, fileName: String = "notifications.json") {
        self.fileURL = directory.appendingPathComponent(fileName)
    }

    // MARK: - Evaluator state

    public func states() -> [String: NotificationEvaluator.AccountState] {
        Dictionary(uniqueKeysWithValues: stored.states.map { ($0.accountId, $0) })
    }

    public func save(states: [NotificationEvaluator.AccountState]) throws {
        stored.states = states
        try persist()
    }

    // MARK: - Delivered keys

    /// Claims the events not yet delivered, and returns exactly those.
    ///
    /// One call rather than a check followed by a record, because the gap between them is where
    /// a duplicate is born. Claiming is idempotent: a key already present keeps its original
    /// timestamp, so re-claiming cannot extend its retention and quietly keep an ancient episode
    /// alive.
    ///
    /// The events themselves come back, not the keys, because the caller is about to post them:
    /// handing back keys would make it re-associate each one with its line.
    public func claim(
        _ events: [NotificationEvaluator.Event],
        at time: Date
    ) throws -> [NotificationEvaluator.Event] {
        var newlyClaimed: [NotificationEvaluator.Event] = []
        for event in events where stored.delivered[event.key] == nil {
            stored.delivered[event.key] = Delivery(accountId: event.accountId, at: time)
            newlyClaimed.append(event)
        }
        prune(before: time.addingTimeInterval(-Self.retention))

        // Persisted even when nothing was claimed, because pruning may still have changed the
        // file — and if it did not, an atomic rewrite of a few hundred bytes costs nothing worth
        // branching on.
        try persist()
        return newlyClaimed
    }

    public func hasDelivered(_ key: String) -> Bool {
        stored.delivered[key] != nil
    }

    /// Forgets an account's state and every key it minted.
    ///
    /// Called when an account is removed. Leaving the keys behind would mean a user who removed
    /// an account and added it back heard nothing about a limit that was already low: the keys
    /// embed the account id, so a re-added account under the same id inherits records saying
    /// every one of its edges has already been announced.
    public func forget(accountID: String) throws {
        stored.states.removeAll { $0.accountId == accountID }
        stored.delivered = stored.delivered.filter { $0.value.accountId != accountID }
        try persist()
    }

    // MARK: - Persistence

    private func prune(before cutoff: Date) {
        stored.delivered = stored.delivered.filter { $0.value.at >= cutoff }
    }

    private func persist() throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        try encoder.encode(stored).write(to: fileURL, options: ContainerFile.writingOptions)
    }

    private static func load(from url: URL) -> Stored {
        let empty = Stored(states: [], delivered: [:])
        guard let data = try? Data(contentsOf: url) else { return empty }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601

        // An unreadable ledger is treated as empty. That risks re-announcing an edge already
        // delivered, which is a repeated message; refusing to run risks announcing nothing at
        // all, which is the failure the user cannot detect.
        return (try? decoder.decode(Stored.self, from: data)) ?? empty
    }
}
