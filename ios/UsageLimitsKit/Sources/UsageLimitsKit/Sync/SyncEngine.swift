import Foundation

/// What a provider can do for one account.
public protocol SyncProvider: Sendable {

    /// The id this provider is registered under. Accounts name the provider they want through an
    /// registered provider, so a provider the engine was never
    /// given fails that one account, never the whole batch.
    var providerID: String { get }

    /// Reads the account's current usage, using the credentials the provider itself issued.
    func fetchUsage(credentials: OAuthCredentials, attributes: [String: String]) async throws -> UsageResult

    /// Exchanges a refresh token for a new set. Throws if it cannot.
    ///
    /// Most of these providers retire the refresh token on every exchange, which makes an exchange
    /// a one-shot operation: the loser of two concurrent exchanges is left holding a token the
    /// provider has already invalidated, and the account then reads as revoked when nothing is
    /// wrong with it.
    func refresh(credentials: OAuthCredentials) async throws -> OAuthCredentials
}

/// What one account's refresh came to.
public enum SyncOutcome: Sendable {

    /// The account's usage was fetched.
    case success(accountID: String, result: UsageResult)

    /// The account could not be refreshed. The message is built from the error and from
    /// identifiers the engine chose; credential material is never interpolated into it, so an
    /// outcome is safe to display exactly as it stands.
    case failure(accountID: String, message: String)
}

/// Receives every outcome as it happens, successes and failures alike, so accounts settle on the
/// caller's screen the moment they finish.
public protocol SyncSink: Sendable {

    /// Deliberately non-throwing: a fault while persisting an outcome must never be turned back
    /// into an account failure, so a sink with problems owns them itself.
    func record(_ outcome: SyncOutcome, at time: Date) async
}

/// Refreshes quota for several AI-subscription accounts.
///
/// The engine is an actor for the sake of a single piece of state: the map of in-flight refreshes
/// keyed by credential reference. Everything an individual account does could run anywhere; the
/// guarantee that only one exchange per reference is ever in flight needs shared, serialised
/// state, and the actor is where it lives.
public actor SyncEngine {

    /// The `ProviderAccount.attributes` key under which an account names the provider that serves
    /// it. It lives in the attributes because the account type carries no provider field of its
    /// own.

    /// A pair this close to its recorded expiry is refreshed now rather than during the fetch that
    /// is about to run: a token that expires while the request is in flight fails the fetch for no
    /// real reason. Both expiry checks use the same leeway, so a pair judged worth refreshing
    /// outside the serial section is still judged worth refreshing inside it.
    public static let expiryLeeway: TimeInterval = 60

    private let providers: [String: any SyncProvider]
    private let credentials: any CredentialStore
    private let sink: any SyncSink
    private let now: @Sendable () -> Date

    /// One exchange in progress per credential reference. A caller that finds an entry awaits it
    /// rather than starting a rival exchange for the same rotating refresh token.
    private var refreshes: [String: Task<Exchange, any Error>] = [:]

    /// What one trip through `renewedCredentials` produced.
    ///
    /// `exchanged` distinguishes "I spent a rotation and here is the new pair" from "somebody
    /// else had already refreshed this reference, so here is what they saved". A caller that
    /// forced the exchange because the provider REJECTED the token needs to tell those apart:
    /// joining a non-forced exchange that decided nothing needed doing hands back the same dead
    /// token, and the account is then reported as revoked when it is merely stale.
    private struct Exchange: Sendable {
        let credentials: OAuthCredentials
        let exchanged: Bool
    }

    /// How many times a rotated pair is offered to the store before giving up on writing it.
    ///
    /// Retried rather than surrendered, because by the time the write is attempted the OLD
    /// refresh token is already dead: a failed save does not leave things as they were, it loses
    /// the account. The keychain items are `AfterFirstUnlockThisDeviceOnly`, so the usual
    /// transient cause — a locked device — cannot arise; what remains is worth a second attempt
    /// and not worth a long one.
    private static let saveAttempts = 3

    public init(providers: [String: any SyncProvider],
                credentials: any CredentialStore,
                sink: any SyncSink,
                now: @Sendable @escaping () -> Date = { Date() }) {
        self.providers = providers
        self.credentials = credentials
        self.sink = sink
        self.now = now
    }

    /// Refreshes every account given and returns the outcomes in the same order.
    ///
    /// The accounts run together, so a slow one delays only itself, and each is isolated: anything
    /// a single account throws becomes that account's failure and nothing else's, and every other
    /// account still syncs and is recorded — one bad account never aborts the batch. The one error
    /// that escapes is `CancellationError`: a cancelled run is not a broken account, so it
    /// propagates to the caller instead of being recorded as a failure.
    public func sync(accounts: [ProviderAccount]) async throws -> [SyncOutcome] {
        try Task.checkCancellation()

        var outcomes = Array<SyncOutcome?>(repeating: nil, count: accounts.count)

        try await withThrowingTaskGroup(of: (Int, SyncOutcome).self) { group in
            for (index, account) in accounts.enumerated() {
                group.addTask {
                    let outcome = try await self.run(account)
                    return (index, outcome)
                }
            }

            for try await (index, outcome) in group {
                outcomes[index] = outcome
            }
        }

        // A child that throws stops the group from returning at all, so by the time control
        // reaches here every slot has been filled exactly once.
        return outcomes.map { $0! }
    }

    /// Carries one account from outcome to recorded outcome, and returns it so the batch can be
    /// answered in the order it was asked for. Delivery is per account rather than at the end of
    /// the batch, so an outcome reaches the sink the moment it settles, whatever its neighbours
    /// are doing.
    private func run(_ account: ProviderAccount) async throws -> SyncOutcome {
        let outcome = try await isolatedOutcome(for: account)
        try await record(outcome)
        return outcome
    }

    /// The failure boundary for a single account.
    ///
    /// Every error the pipeline raises becomes this account's failure alone; the neighbouring
    /// accounts carry on and are recorded regardless. `CancellationError` is the single exception:
    /// it stands for the caller giving up, not for an account going wrong, and recording it as a
    /// failure would paint a healthy account red because a screen was closed.
    private func isolatedOutcome(for account: ProviderAccount) async throws -> SyncOutcome {
        do {
            let result = try await usage(for: account)
            return .success(accountID: account.id, result: result)
        } catch let cancelled as CancellationError {
            throw cancelled
        } catch {
            return .failure(accountID: account.id, message: Self.message(for: error))
        }
    }

    /// One account's quota refresh: resolve the provider, arrive at a credential pair that is not
    /// known to be expired, fetch the usage.
    private func usage(for account: ProviderAccount) async throws -> UsageResult {
        try Task.checkCancellation()

        // The account's own typed provider, not a stringly-typed attribute alongside it. Reading
        // an attribute meant a perfectly well-formed account — one whose `provider` says exactly
        // which provider it is — failed with "does not name a provider" because a second, parallel
        // copy of that fact had not been filled in. Two sources of truth for one thing, where
        // forgetting the redundant one costs the user a silently broken account.
        let providerID = account.provider.rawValue
        guard let provider = providers[providerID] else {
            // An id the engine has no provider for is stale configuration, not a reason to stop the
            // process: this account fails and the rest of the batch carries on.
            throw SyncEngineError.providerNotRegistered(providerID)
        }

        guard let stored = try await credentials.load(reference: account.credentialReference) else {
            // Nothing can be exchanged without a pair to exchange, so the account fails here
            // rather than somewhere deeper inside the provider.
            throw SyncEngineError.credentialsAbsent
        }

        let usable: OAuthCredentials
        if stored.isExpired(now: now(), leeway: Self.expiryLeeway) {
            usable = try await renewedCredentials(
                reference: account.credentialReference,
                provider: provider
            ).credentials
        } else {
            // "Not known to be expired" deliberately includes an absent expiry — refreshing such an
            // account on every call would burn a rotating refresh token each time.
            usable = stored
        }

        // If the run was cancelled while the credentials were being sorted out, give up before the
        // network rather than after it.
        try Task.checkCancellation()

        do {
            return try await provider.fetchUsage(credentials: usable, attributes: account.attributes)
        } catch ProviderError.unauthorised {
            // The reactive half of the refresh policy, and the half that makes the proactive one
            // safe to keep conservative.
            //
            // "Not known to be expired" includes an absent expiry, because refreshing on every
            // call would burn a rotating refresh token each time. On its own that leaves an
            // account whose provider never states an expiry to 401 for ever, silently — the
            // proactive branch can never fire for it. So a rejection is what triggers the
            // exchange instead: the provider itself has said the token is dead, which is better
            // evidence than any clock.
            //
            // What is passed is the access token this request actually tried, not a bare
            // "force". The difference matters twice. A concurrent pass may already have saved a
            // DIFFERENT token, which this request has not tried and which no evidence says is
            // dead — so it is worth one attempt before spending a rotation. And the retry
            // condition below used to be `!renewed.exchanged`, which spent a second rotation
            // even when the pair handed back was a different, freshly saved one. Identity of the
            // rejected token answers both questions exactly; `exchanged` only approximated them.
            //
            // Once only. If the pair that comes back is rejected too, the credential is
            // genuinely revoked and retrying is just a second way to fail.
            let renewed = try await renewedCredentials(
                reference: account.credentialReference,
                provider: provider,
                rejectedAccessToken: usable.accessToken
            )
            try Task.checkCancellation()
            return try await provider.fetchUsage(
                credentials: renewed.credentials, attributes: account.attributes)
        }
    }

    /// Returns a pair stored under `reference` that is not known to be expired, with at most one
    /// exchange for the reference in flight at a time.
    ///
    /// The lock is keyed by reference, not by account, because two accounts can share one
    /// credential reference and both would otherwise spend the same rotating refresh token. A
    /// caller that finds an exchange already running awaits it.
    ///
    /// The expiry is re-decided inside the serial section, against a pair re-read from the store.
    /// That re-read is what stops a caller arriving just after an earlier exchange finished from
    /// exchanging again immediately: the earlier exchange has already saved a fresh pair, and the
    /// re-read finds it. Trusting the copy the caller loaded before it waited is precisely the
    /// double refresh that used to strand accounts.
    /// - Parameter force: exchange even when the stored pair does not look expired. Set only by
    ///   the rejection path, where the provider has already said the token is dead.
    private func renewedCredentials(reference: String,
                                    provider: any SyncProvider,
                                    rejectedAccessToken: String? = nil) async throws -> Exchange {
        // A loop rather than a single join. A caller whose token was REJECTED can join an
        // exchange that was started proactively, decided nothing needed doing, and handed back
        // the very token the provider just refused. Retrying the endpoint with it would report a
        // healthy account as revoked. By the time that value is available the joined task's
        // `defer` has already cleared its entry, so coordinating again either starts a real
        // exchange or joins a later one — it cannot rejoin the same no-op.
        while let running = refreshes[reference] {
            let result = try await running.value
            if let rejectedAccessToken,
               !result.exchanged,
               result.credentials.accessToken == rejectedAccessToken {
                continue
            }
            return result
        }

        // The exchange runs as a deliberately unstructured task, which the engine never cancels:
        // an exchange abandoned between the rotation and the save leaves the old refresh token
        // dead and the new one unwritten, losing the account outright. For the same reason the
        // body performs no cancellation check — once begun, an exchange runs to its save.
        let task = Task<Exchange, any Error> {
            // The entry lasts exactly as long as the exchange: a late arrival either joins this
            // exchange or re-reads a store this exchange has already written to.
            defer { self.refreshes[reference] = nil }

            guard let current = try await self.credentials.load(reference: reference) else {
                throw SyncEngineError.credentialsAbsent
            }

            if let rejectedAccessToken {
                if current.accessToken != rejectedAccessToken {
                    // The store has moved on: a concurrent pass saved a different token, and
                    // nothing has rejected THAT one. Offer it before spending a rotation.
                    return Exchange(credentials: current, exchanged: false)
                }
                // Otherwise the stored token is the rejected one, and its expiry is irrelevant —
                // the provider has already said it is dead, which beats any clock.
            } else if !current.isExpired(now: self.now(), leeway: Self.expiryLeeway) {
                // An exchange for this reference has already finished; the saved pair is fresh,
                // and spending another rotation here is the very bug this routine exists to
                // prevent.
                return Exchange(credentials: current, exchanged: false)
            }

            let response = try await provider.refresh(credentials: current)

            // A token response is not a complete credential. Providers that do not rotate their
            // refresh token omit it from the response entirely, and storing the response as-is
            // would delete the refresh token this account depends on — losing an account that
            // nothing had revoked, which looks exactly like a revocation.
            let renewed = current.merging(refreshed: response)

            // Persisted before it is used — by this exchange or by any account waiting on it. If
            // the process dies between the exchange and the fetch, the old refresh token is
            // already dead, and a pair that was never written back loses the account entirely.
            //
            // Which is also why a failing save is retried and then, if it still will not write,
            // NOT allowed to fail the refresh. The rotation has already happened: the token in
            // the store is dead whatever this code does next. Throwing here would discard the
            // only working pair that exists and turn a storage fault into a signed-out paid
            // account within the same second. Returning it means this session keeps working,
            // and the sign-out is deferred to the next launch rather than caused now.
            await self.persist(renewed, reference: reference)
            return Exchange(credentials: renewed, exchanged: true)
        }

        // Nothing between the look-up above and this store suspends, so on this actor the
        // check-and-register is atomic: at most one exchange is ever registered per reference.
        refreshes[reference] = task
        return try await task.value
    }

    /// Writes a rotated pair, retrying, and never throwing. See the call site for why a failure
    /// here must not fail the refresh.
    private func persist(_ credentials: OAuthCredentials, reference: String) async {
        for attempt in 1...Self.saveAttempts {
            do {
                // Update-only, never insert. A rotation that started before the user removed
                // the account finishes afterwards, and `save` would put the deleted credential
                // straight back: the account is gone from the list while a usable token stays
                // in the keychain with nothing left to clean it up. A false return means
                // removal won, which is a correct outcome and not a failure to retry.
                _ = try await self.credentials.updateIfPresent(credentials, reference: reference)
                return
            } catch {
                guard attempt < Self.saveAttempts else { return }
                // A short, fixed pause. The plausible causes are momentary; a long backoff would
                // hold the exchange lock open while every other account waits on it.
                try? await Task.sleep(nanoseconds: 100 * 1_000_000)
            }
        }
    }

    /// Delivers one outcome to the sink, best-effort.
    ///
    /// The sink cannot throw, so persisting an outcome can never fail the account it belongs to —
    /// that is what makes the delivery best-effort. The outcome is delivered even if the run has
    /// been cancelled in the meantime, because the work is done and the sink should not lose it;
    /// cancellation that lands while the delivery is in flight must still escape, so it is raised
    /// once the sink has had its chance.
    private func record(_ outcome: SyncOutcome) async throws {
        await sink.record(outcome, at: now())
        try Task.checkCancellation()
    }

    /// Renders a caught error as an outcome message.
    ///
    /// The engine builds messages from the error itself and from identifiers it chose; credential
    /// material is never interpolated, so an outcome is safe to show the user as it stands.
    private static func message(for error: any Error) -> String {
        if let described = (error as? LocalizedError)?.errorDescription, !described.isEmpty {
            return described
        }
        return String(describing: error)
    }

    /// Faults the engine raises for a single account. The payloads are identifiers, never
    /// credential material, so every message built from them is safe to display.
    private enum SyncEngineError: LocalizedError, Sendable {

        /// `nil` when the account names no provider at all.
        case providerNotRegistered(String?)
        case credentialsAbsent

        var errorDescription: String? {
            switch self {
            case .providerNotRegistered(nil):
                return "The account does not name a provider."
            case .providerNotRegistered(let providerID?):
                return "No provider is registered for provider id \"\(providerID)\"."
            case .credentialsAbsent:
                return "No credentials are stored for the account."
            }
        }
    }
}