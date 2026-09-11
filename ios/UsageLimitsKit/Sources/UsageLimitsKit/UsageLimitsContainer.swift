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
    private let http: UsageHTTPClient
    private let logins: [ProviderID: any DeviceLoginProvider]
    /// Kept rather than handed only to the engine: the pasted-key sign-in proves a key by
    /// calling the provider's own usage endpoint before any account exists.
    private let providers: [String: any SyncProvider]
    private let settingsStore: SettingsStore
    private let ledger: NotificationLedger
    private let containerDirectory: URL
    private let now: @Sendable () -> Date

    /// The one-time install check, kept so concurrent callers join it rather than repeat it.
    private var preparation: Task<Void, any Error>?

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
        self.http = http
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
            ProviderID.kimi.rawValue: KimiClient(httpClient: http),
        ]
        self.providers = providers

        self.logins = [
            .codex: CodexDeviceLogin(httpClient: http, now: now),
            .xai: XaiDeviceLogin(httpClient: http, now: now),
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
        await publishCurrent(usage)
        return usage
    }

    public func usage() async -> [AccountUsage] {
        await repository.usage()
    }

    // MARK: - Signing in

    /// Asks a provider for a code to show the user.
    ///
    /// Throws `unsupportedOnThisPlatform` for the two providers whose sign-in a phone cannot
    /// complete, carrying the reason — so a caller renders an explanation rather than a control
    /// that starts something which cannot finish.
    public func beginLogin(provider: ProviderID) async throws -> DeviceLoginChallenge {
        guard let login = logins[provider] else {
            throw DeviceLoginError.unsupportedOnThisPlatform(
                DeviceLoginSupport.unsupportedReason(for: provider)
                    ?? "This provider cannot be signed into here.")
        }
        return try await login.begin()
    }

    /// Waits for the user to approve, then stores the account and its credentials.
    ///
    /// The order is credentials first, account second. An account whose credentials failed to
    /// save is a row that can never sync and offers no way to repair itself; a credential with
    /// no account is invisible but harmless, and the next sign-in overwrites it.
    ///
    /// The account id is derived from the provider and the provider's own account identifier,
    /// so signing in again UPDATES the existing account rather than adding a second copy of it —
    /// and the repository keeps the previous usage across that, because re-authenticating
    /// changes the tokens, not the quota.
    @discardableResult
    public func completeLogin(
        provider: ProviderID,
        challenge: DeviceLoginChallenge
    ) async throws -> ProviderAccount {
        guard let login = logins[provider] else {
            throw DeviceLoginError.unsupportedOnThisPlatform(
                DeviceLoginSupport.unsupportedReason(for: provider)
                    ?? "This provider cannot be signed into here.")
        }

        let credentials = try await login.complete(challenge)
        let profile = try await login.profile(credentials)
        return try await store(profile: profile, credentials: credentials, provider: provider)
    }

    /// Completes the one sign-in that has no flow: a key the user pasted.
    ///
    /// Kimi Code only. The key is proved against the usage endpoint BEFORE an account row
    /// exists — a key that cannot read usage is not a connected account, and storing it would
    /// leave a permanently failing row the user then has to work out how to remove.
    ///
    /// Identity is whatever the response names, and otherwise a truncated digest of the key:
    /// one way, never the key itself, and stable, so pasting the same key again updates that
    /// account instead of adding a second one beside it.
    public func completePastedKeyLogin(
        provider: ProviderID,
        key: String
    ) async throws -> ProviderAccount {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw DeviceLoginError.unsupportedOnThisPlatform("No key was entered.")
        }
        guard let client = providers[provider.rawValue] else {
            throw DeviceLoginError.unsupportedOnThisPlatform(
                "This provider cannot be signed into here.")
        }

        let credentials = OAuthCredentials(
            accessToken: trimmed,
            refreshToken: nil,
            idToken: nil,
            expiresAt: nil)

        // Throws if the key is not usable, which is the whole point of doing it here.
        _ = try await client.fetchUsage(credentials: credentials, attributes: [:])

        let profile = ProviderProfile(
            externalAccountID: Self.pastedKeyIdentity(trimmed),
            email: nil,
            displayName: nil,
            plan: nil)
        return try await store(profile: profile, credentials: credentials, provider: provider)
    }

    /// A stable, one-way identity for a pasted key.
    ///
    /// Not the key, and not reversible into it. It only has to be stable and unique so that the
    /// same key re-entered lands on the same account.
    static func pastedKeyIdentity(_ key: String) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in Array(key.utf8) {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01B3
        }
        return "key-" + String(hash, radix: 16)
    }

    /// Saves the credentials and the account they belong to.
    ///
    /// Shared by both sign-in styles, because the part that can go wrong — the ORDER, and the
    /// identity the account is filed under — is the same either way and must not be written
    /// twice.
    private func store(
        profile: ProviderProfile,
        credentials: OAuthCredentials,
        provider: ProviderID
    ) async throws -> ProviderAccount {
        let identifier = "\(provider.rawValue):\(profile.externalAccountID)"
        try await self.credentials.save(credentials, reference: identifier)

        let existing = await repository.accounts().first { $0.id == identifier }
        let account = ProviderAccount(
            id: identifier,
            provider: provider,
            externalAccountID: profile.externalAccountID,
            email: profile.email,
            displayName: profile.displayName,
            plan: profile.plan,
            credentialReference: identifier,
            // Kept from the original sign-in, so re-authenticating does not move the account to
            // the bottom of a list ordered by when it was added.
            createdAt: existing?.createdAt ?? now(),
            lastSuccessfulSync: existing?.lastSuccessfulSync,
            attributes: profile.attributes)

        try await add(account)
        return account
    }

    /// Starts a loopback sign-in and returns what the app needs to present it.
    ///
    /// The caller opens `challenge.url` in `ASWebAuthenticationSession` — which presents in
    /// process, so the app stays foregrounded and the listener stays alive — and then calls
    /// `completeLoopbackLogin`.
    public func beginLoopbackLogin(provider: ProviderID) throws -> LoopbackChallenge {
        try LoopbackLogin(provider: provider, httpClient: http, now: now).begin()
    }

    /// Finishes a loopback sign-in from the code the listener received.
    ///
    /// Deliberately takes the code rather than waiting for it: the socket exists only where
    /// `Network` does, so keeping it out of here is what lets everything either side of it be
    /// exercised on Linux.
    @discardableResult
    public func completeLoopbackLogin(
        code: String,
        challenge: LoopbackChallenge
    ) async throws -> ProviderAccount {
        let login = LoopbackLogin(
            provider: challenge.provider, httpClient: http, now: now)
        let credentials = try await login.exchange(code: code, challenge: challenge)
        let profile = try await login.profile(credentials)
        return try await store(profile: profile, credentials: credentials,
                               provider: challenge.provider)
    }

    // MARK: - Spending a reset credit

    /// Spends one Codex reset credit for an account, then refreshes so the screen shows what it
    /// bought.
    ///
    /// The only thing this app does that changes state at a provider. It is therefore never part
    /// of a sync, never retried, and gated here as well as in the UI: the caller's own check can
    /// be stale by the time the tap lands, and a spend the provider refuses still looks to the
    /// user like a credit gone.
    ///
    /// The gate is the APPLICABLE count, not the held one. An account can hold three credits and
    /// be able to apply none of them.
    /// Accounts with a credit spend in flight. See `redeemResetCredit`.
    private var redeeming: Set<String> = []

    @discardableResult
    public func redeemResetCredit(accountID: String) async throws -> [AccountUsage] {
        // One spend in flight per account. A redemption is a network round-trip that consumes
        // a real credit, and a button can be tapped twice before the first answer lands; two
        // requests in flight are two credits spent for one reset. The guard lives here, in
        // the actor, rather than only in the button, because the widget and a shortcut can
        // reach this too.
        guard !redeeming.contains(accountID) else { throw ResetCreditError.alreadyRedeeming }
        redeeming.insert(accountID)
        defer { redeeming.remove(accountID) }

        guard let usage = await repository.usage().first(where: { $0.account.id == accountID }),
              usage.account.provider == .codex
        else {
            throw ResetCreditError.notAvailable
        }
        guard (usage.snapshot?.spendableResetCredits ?? 0) > 0 else {
            throw ResetCreditError.noneApplicable
        }
        // Renewed if need be, through the same coordinator a sync uses, rather than sent as
        // stored. Loading and sending straight away meant that leaving the app open past the
        // access token's expiry made a perfectly legitimate redemption fail with 401 — with a
        // usable refresh token in the store the whole time. The user was told their credit
        // could not be spent for a reason entirely inside this app, and a spend is exactly the
        // action where that is least acceptable.
        let usable: OAuthCredentials
        do {
            usable = try await engine.usableCredentials(
                reference: usage.account.credentialReference,
                provider: usage.account.provider.rawValue)
        } catch {
            throw ResetCreditError.notAvailable
        }

        try await CodexClient(httpClient: http, now: now)
            .redeemResetCredit(credentials: usable, attributes: usage.account.attributes)

        // Refreshed straight away, because the whole point of the spend is the new limit — and
        // because the credit count the screen is showing is now certainly wrong.
        return try await refresh()
    }

    /// Why a redemption did not happen. Deliberately not `ProviderError`: none of these is a
    /// provider fault, and all three are things the user can be told plainly.
    public enum ResetCreditError: Error, LocalizedError, Equatable {
        case notAvailable
        case noneApplicable
        /// A spend for this account is already in flight; the second request is refused.
        case alreadyRedeeming

        public var errorDescription: String? {
            switch self {
            case .notAvailable:
                return "This account cannot use reset credits."
            case .noneApplicable:
                return "No reset credit can be applied to this limit right now."
            case .alreadyRedeeming:
                return "A reset is already being requested for this account."
            }
        }
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
        await publishCurrent(await repository.usage())
    }

    /// Stores the order the user dragged the accounts into, and reports the list as it now reads.
    ///
    /// Republishes the widget snapshot, because the home screen tiles pick the accounts they
    /// show from this order: a rearrangement the app honoured and the widget did not would put
    /// two different answers to "which accounts matter most" on one device.
    @discardableResult
    public func reorder(ids: [String]) async throws -> [AccountUsage] {
        try await repository.reorder(ids: ids)
        let usage = await repository.usage()
        await publishCurrent(usage)
        return usage
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

        await publishCurrent(await repository.usage())
    }

    /// Called once per install, when the app finds no marker of its own in its container.
    ///
    /// Keychain items outlive the app being deleted while everything in the container does not,
    /// so a user who deletes the app to revoke its access and later reinstalls would otherwise
    /// find every paid account still connected, with no sign-in.
    public func purgeCredentialsFromPreviousInstall() async throws {
        try await credentials.removeAll()
    }

    /// Runs the install check, at most once, and waits for any run already under way.
    ///
    /// `purgeCredentialsFromPreviousInstall` existed for a while with no caller at all, which
    /// meant the guarantee above was written down and not implemented: deleting the app and
    /// reinstalling left every account connected. Everything that reads or writes an account
    /// now waits on this, so a purge can never run underneath a login that is already storing
    /// a credential.
    ///
    /// The marker cannot simply mean "purge whenever absent". Every installation that predates
    /// it also lacks one, and treating those as fresh would sign out every existing user on the
    /// upgrade that introduced the marker. An existing `accounts.json` is what tells the two
    /// apart: a container carrying accounts belongs to an install that is already running, so it
    /// adopts the marker without purging. Only a container with neither is genuinely new, and
    /// that is exactly the reinstall case.
    ///
    /// The marker is written after the purge succeeds, never before, so a cleanup interrupted
    /// half way is retried on the next launch rather than recorded as done.
    public func prepareForUse() async throws {
        if let preparation { return try await preparation.value }
        let task = Task<Void, any Error> { [containerDirectory, credentials, repository] in
            let marker = containerDirectory.appendingPathComponent(Self.installMarkerName)
            let fileManager = FileManager.default

            // `fileExists` and not a read that could throw: an unreadable marker is present,
            // and treating a read failure as absence would purge a working install.
            if fileManager.fileExists(atPath: marker.path) { return }

            let accountsFile = containerDirectory
                .appendingPathComponent(AccountRepository.fileName)
            // The file first, because it answers without decoding; the loaded accounts as a
            // fallback in case a caller pointed the repository at a different name.
            var isExistingInstall = fileManager.fileExists(atPath: accountsFile.path)
            if !isExistingInstall {
                isExistingInstall = await !repository.accounts().isEmpty
            }

            if !isExistingInstall {
                try await credentials.removeAll()
            }

            try Data().write(to: marker, options: ContainerFile.writingOptions)
        }
        preparation = task
        do {
            try await task.value
        } catch {
            // A failed preparation is not remembered as done: the next launch tries again.
            preparation = nil
            throw error
        }
    }

    /// The empty file whose presence means "this install has already been checked".
    static let installMarkerName = "install-marker"

    /// Publishes with the threshold the user's own sync interval implies.
    ///
    /// Read here rather than defaulted, because a fixed hour is wrong at the three-hour interval
    /// the settings screen offers: every snapshot would be older than the threshold before the
    /// next arrived, so every account would read stale permanently and the tile would lead with
    /// whichever healthy account happened to sort first.
    private func publishCurrent(_ usage: [AccountUsage]) async {
        publish(usage, staleAfter: await settingsStore.settings().staleAfter)
    }

    /// Hands the widget what the app would render.
    ///
    /// Built from the same `GlanceModel` call the app's own screen uses, so the two surfaces
    /// cannot disagree — one derivation, one answer. Best-effort: failing to update a tile must
    /// never fail the refresh the user is watching.
    private func publish(_ usage: [AccountUsage], staleAfter: TimeInterval) {
        try? GlanceSnapshotCodec.write(
            GlanceModel.build(
                usage, now: now(), scope: .mostCritical, staleAfter: staleAfter),
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
