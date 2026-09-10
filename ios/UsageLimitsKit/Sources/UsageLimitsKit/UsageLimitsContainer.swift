import Foundation

#if canImport(WidgetKit)
import WidgetKit
#endif

/// Builds the object graph, and owns the one refresh path.
///
/// In the kit rather than in the app for the same reason as everything else here: it can be
/// exercised on Linux with a fake transport and an in-memory credential store, which is where
/// the wiring mistakes actually live. The app supplies a directory and gets a working system.
///
/// Deliberately hand-wired rather than a dependency-injection framework. The graph is small,
/// entirely singleton-scoped, and constructed exactly once — a framework would add a build step
/// and a class of runtime failure without removing any code worth removing.
public actor UsageLimitsContainer {

    public let repository: AccountRepository
    private let credentials: any CredentialStore
    private let engine: SyncEngine
    private let settingsStore: SettingsStore
    private let ledger: NotificationLedger
    private let containerDirectory: URL
    private let now: @Sendable () -> Date

    /// - Parameters:
    ///   - directory: the shared container. Both the account cache and the widget snapshot are
    ///     written here, so the widget process reads what the app last knew.
    ///   - credentials: the keychain on a device; an in-memory store in a test.
    ///   - transport: injected so a test can answer without a network.
    public init(
        directory: URL,
        credentials: any CredentialStore,
        transport: any HTTPTransport = URLSessionTransport(),
        now: @Sendable @escaping () -> Date = { Date() }
    ) {
        let repository = AccountRepository(directory: directory)
        let http = UsageHTTPClient(transport: transport, now: now)

        self.containerDirectory = directory
        self.repository = repository
        self.credentials = credentials
        self.settingsStore = SettingsStore(directory: directory)
        self.ledger = NotificationLedger(directory: directory)
        self.now = now
        let providers: [String: any SyncProvider] = [
            ProviderID.codex.rawValue: CodexClient(httpClient: http),
            ProviderID.claude.rawValue: ClaudeClient(httpClient: http),
            ProviderID.antigravity.rawValue: AntigravityClient(httpClient: http),
            ProviderID.xai.rawValue: XaiClient(httpClient: http),
        ]

        self.engine = SyncEngine(
            providers: providers,
            credentials: credentials,
            sink: RepositorySink(repository: repository),
            now: now)
    }

    /// Refreshes every connected account and republishes what the widget reads.
    ///
    /// Returns the usage as it stands afterwards, so a caller renders the result of this sync
    /// rather than re-reading and racing it.
    @discardableResult
    public func refresh() async throws -> [AccountUsage] {
        let accounts = await repository.accounts()

        // An empty account list is not an error and not a reason to skip the publish: the
        // widget must be told the list is empty, or it goes on showing accounts that were
        // removed.
        if !accounts.isEmpty {
            _ = try await engine.sync(accounts: accounts)
        }

        let usage = await repository.usage()
        publish(usage)
        return usage
    }

    public func usage() async -> [AccountUsage] {
        await repository.usage()
    }

    // MARK: - Settings

    public func settings() async -> AppSettings {
        await settingsStore.settings()
    }

    /// Stores the user's choices and reports what was actually stored, since the sync interval
    /// is floored at what the platform will honour.
    @discardableResult
    public func save(settings: AppSettings) async throws -> AppSettings {
        try await settingsStore.save(settings)
    }

    // MARK: - Notifications

    /// Decides what to say about the current usage, and claims it before anybody says it.
    ///
    /// Returns only the edges not previously delivered, so the caller can post every event it
    /// receives without deduplicating anything itself. The claim happens here, before the
    /// caller posts: a file write cannot commit atomically with a notification being scheduled,
    /// and losing one alert in that window beats repeating an alert on every sync — which is
    /// what makes people switch notifications off.
    ///
    /// Deliberately separate from `refresh`, because a background refresh and a user pulling to
    /// refresh want the same sync and different notification behaviour, and because a failure to
    /// persist the ledger must not fail the refresh the user is watching.
    public func pendingNotifications() async throws -> [NotificationEvaluator.Event] {
        let outcome = NotificationEvaluator.evaluate(
            accounts: await repository.usage().map(AccountSummary.init),
            settings: await settingsStore.settings().notifications,
            states: await ledger.states(),
            now: now())

        // State first. If the claim fails after this, the worst case is an edge announced twice;
        // if the state were saved last and failed, every episode would restart on the next sync
        // and every threshold would fire again.
        try await ledger.save(states: outcome.states)

        // EVERY edge is claimed, including the ones carrying no text. A blank line means the
        // evaluator reached a threshold the user has switched off, or a weaker tier consumed by
        // a stronger one — and the claim is what stops it arriving later as a stale alert the
        // moment that setting is switched back on. Only the ones with something to say come
        // back, so the caller can post each of them without inspecting anything.
        let claimed = try await ledger.claim(outcome.events, at: now())
        return claimed.filter { !$0.line.isEmpty }
    }

    public func add(_ account: ProviderAccount) async throws {
        try await repository.upsert(account)
        publish(await repository.usage())
    }

    /// Forgets an account and the credentials behind it.
    ///
    /// The credential is deleted first. If that fails the account stays visible, which is
    /// recoverable; the other order can leave a keychain entry nothing references — invisible,
    /// unreachable, and still granting access to a paid account.
    public func remove(id: String) async throws {
        let accounts = await repository.accounts()
        if let account = accounts.first(where: { $0.id == id }) {
            try await credentials.delete(reference: account.credentialReference)
        }
        try await repository.remove(id: id)

        // The ledger is cleared too. Its keys embed the account id, so an account removed and
        // added back under the same id would inherit records saying every one of its edges had
        // already been announced — and go silent while genuinely low. Best-effort: a ledger that
        // will not write must not leave the account half-removed.
        try? await ledger.forget(accountID: id)

        publish(await repository.usage())
    }

    /// Called once per install, when the app finds no marker of its own in its container.
    ///
    /// Keychain items outlive the app being deleted while everything in the container does not,
    /// so a user who deletes the app to revoke its access and later reinstalls would otherwise
    /// find every paid account still connected, with no sign-in.
    public func purgeCredentialsFromPreviousInstall() async throws {
        try await credentials.removeAll()
    }

    /// Hands the widget what the app would render.
    ///
    /// Built from the same `GlanceModel` call the app's own screen uses, so the two surfaces
    /// cannot disagree — one derivation, one answer. Best-effort: failing to update a tile must
    /// never fail the refresh the user is watching.
    private func publish(_ usage: [AccountUsage]) {
        try? GlanceSnapshotCodec.write(
            GlanceModel.build(usage, now: now(), scope: .mostCritical),
            toDirectory: containerDirectory)

        // Writing the file is only half of it. A widget extension does not watch the container,
        // and WidgetKit reloads a timeline on its own budget — hours apart when nothing asks it
        // otherwise. Without this the tile goes on showing whatever it last rendered however
        // often the app syncs, which is the failure the whole snapshot mechanism exists to
        // avoid. The reload is a request, not a command: WidgetKit still decides when, and
        // throttles an app that asks too often — which is why it is asked exactly once per
        // publish rather than per account.
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadAllTimelines()
        #endif
    }
}
