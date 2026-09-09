//
//  CredentialStore.swift
//  UsageLimitsKit
//
//  OAuth credential storage for Usage Limits. The credential model and the
//  store protocol are shared across platforms; the keychain implementation
//  exists only where `Security` can be imported; an in-memory actor covers
//  the unit tests and the Linux build.
//

import Foundation

#if canImport(Security)
import Security
#endif

// MARK: - Credential model

/// The OAuth material for one provider account.
///
/// Accounts are keyed by an opaque `reference` string chosen by the caller
/// (for example `"openai-work"`), so this type never needs to understand any
/// provider's identity model — it is just what an authorisation-code
/// exchange hands back. It deliberately conforms to neither
/// `CustomStringConvertible` nor `CustomDebugStringConvertible`: a
/// hand-written description of credentials eventually finds its way into
/// logs, whereas a synthesised case name never can.
public struct OAuthCredentials: Codable, Sendable, Equatable {
    /// The bearer token used against the provider's quota endpoints.
    public let accessToken: String

    /// The long-lived token used to mint a fresh access token. Optional
    /// because some providers issue access-only grants for read-only quota
    /// reads.
    public let refreshToken: String?

    /// The OpenID Connect identity token, when the provider returned one, so
    /// the account list can display a verified identity without ever handing
    /// UI code the access token.
    public let idToken: String?

    /// The absolute instant at which the access token stops working, as the
    /// provider reported it. Absolute rather than a relative `expires_in`
    /// count, because a relative value starts decaying the moment it is
    /// written and would make every later read depend on a fetch time the
    /// store no longer knows.
    public let expiresAt: Date?

    /// The scope string exactly as granted, so a silently narrowed
    /// re-authorisation can be detected by comparison rather than discovered
    /// later as a puzzling API error.
    public let scope: String?

    public init(
        accessToken: String,
        refreshToken: String? = nil,
        idToken: String? = nil,
        expiresAt: Date? = nil,
        scope: String? = nil
    ) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.idToken = idToken
        self.expiresAt = expiresAt
        self.scope = scope
    }

    /// Whether the access token should be treated as stale at `now`.
    ///
    /// A nil `expiresAt` deliberately returns `false`: some providers never
    /// report an expiry, and treating "unknown" as "expired" would force a
    /// refresh on every single read, hammering the token endpoint until it
    /// rate-limits us. The provider's eventual 401 is the cheaper and more
    /// reliable signal in that situation.
    ///
    /// `leeway` declares the token expired slightly early, absorbing clock
    /// skew between the device and the provider's servers plus the time the
    /// request will spend in flight: refreshing a moment early is free,
    /// refreshing late costs a failed round trip.
    public func isExpired(now: Date, leeway: TimeInterval = 60) -> Bool {
        guard let expiresAt else { return false }
        return now >= expiresAt.addingTimeInterval(-leeway)
    }
}

// MARK: - Store protocol

/// Where per-account credentials are persisted.
///
/// Async throughout because the keychain implementation must hop off the
/// caller's thread and the in-memory one serialises through an actor; the
/// protocol keeps callers from needing to know which is underneath.
public protocol CredentialStore: Sendable {
    /// The credentials stored under `reference`, or nil when nothing is.
    /// Absence is not an error: the app treats it as "this account is signed
    /// out", which is a state rather than a fault.
    func load(reference: String) async throws -> OAuthCredentials?

    /// Stores `credentials` under `reference`, replacing whatever is already
    /// there, so a token refresh can write back a rotated token with a single
    /// call and no separate delete.
    func save(_ credentials: OAuthCredentials, reference: String) async throws

    /// Removes the credentials under `reference`. Succeeds when nothing is
    /// stored, so a sign-out flow never needs to know whether a refresh ever
    /// completed.
    func delete(reference: String) async throws

    /// Every reference with stored credentials, sorted, so the account list
    /// renders stably no matter which store is underneath.
    func allReferences() async throws -> [String]
}

// MARK: - Errors

/// Failures reported by credential stores.
///
/// The cases carry machine statuses and bare tags — never message strings and
/// never payloads — so that even a careless `print(error)` in a debug build
/// cannot leak token material.
public enum CredentialStoreError: Error, Sendable {
    #if canImport(Security)
    /// A keychain call returned this unexpected status. The payload is the
    /// bare `OSStatus`: Security's status codes describe an operation's
    /// failure, never the data the operation touched.
    case keychain(OSStatus)
    #endif

    /// The credentials could not be serialised for storage. The underlying
    /// error is discarded rather than wrapped, because it can name the
    /// property it failed on.
    case encodingFailed

    /// Something is stored under the reference but cannot be deserialised —
    /// an item written by an older schema, say. Reported rather than
    /// masquerading as an absent item on load, so corruption stays
    /// diagnosable instead of silently signing the user out.
    case decodingFailed
}

#if canImport(Security)
// MARK: - Keychain store (Apple platforms)

/// A keychain-backed `CredentialStore`.
///
/// The entire implementation sits behind `canImport(Security)` because
/// `Security` does not exist on Linux; the Linux build uses
/// `InMemoryCredentialStore`.
///
/// The class is `final` and holds a single immutable `String`, which is
/// exactly what lets it conform to the `Sendable`-inheriting protocol with a
/// compiler-checked conformance — no `@unchecked Sendable`, and no locks.
public final class KeychainCredentialStore: CredentialStore {
    /// The keychain service grouping every credential this app stores.
    ///
    /// A dedicated namespace keeps "delete everything this app owns" from
    /// ever colliding with, or damaging, items belonging to another product.
    public let service: String

    /// Creates a store over the given service, defaulting to the app's own
    /// namespace.
    public init(service: String = "com.usagelimits.credentials") {
        self.service = service
    }

    /// The accessibility applied to every item this store writes.
    ///
    /// Deliberately `AfterFirstUnlock`, for two reasons a reviewer can check
    /// against the constant rather than take on trust:
    ///
    /// * Not `WhenUnlocked` — the quota refresh runs on a schedule
    ///   (BGTaskScheduler) and has to work whilst the device is locked. A
    ///   `WhenUnlocked` item would make every one of those reads fail with
    ///   `errSecProtectedDataUnavailable`, so each scheduled refresh would
    ///   silently stall until the user next unlocks. Stale quota with no
    ///   visible error anywhere is the worst failure this app could ship.
    /// * `ThisDeviceOnly`, so the item is excluded from encrypted backups and
    ///   from device-to-device migration. The cost is real and worth stating:
    ///   a user moving to a replacement phone loses every provider connection
    ///   and has to sign in again. It is accepted because the alternative is
    ///   OAuth tokens for four paid accounts sitting inside a backup, which is
    ///   a copy of the credentials that outlives the device and that the app
    ///   can neither see nor revoke.
    ///
    ///   This also keeps the two platforms honest with each other: the Android
    ///   build sets `allowBackup="false"` and disables device transfer for the
    ///   same reason, so an iOS build that quietly shipped tokens into iCloud
    ///   would make the project's own security note untrue.
    private var accessibility: CFString { kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly }

    // MARK: CredentialStore

    public func load(reference: String) async throws -> OAuthCredentials? {
        var query = baseQuery(reference: reference)
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecReturnData as String] = true

        var queryResult: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &queryResult)
        switch status {
        case errSecSuccess:
            break
        case errSecItemNotFound:
            // Nothing stored under this reference is a signed-out account,
            // not a fault.
            return nil
        default:
            throw CredentialStoreError.keychain(status)
        }

        guard let data = queryResult as? Data else {
            // An item exists but is not the bytes we write; report it as
            // unreadable rather than absent.
            throw CredentialStoreError.decodingFailed
        }
        return try decoded(from: data)
    }

    public func save(_ credentials: OAuthCredentials, reference: String) async throws {
        // Encode first: a serialisation failure must leave the keychain
        // untouched rather than half-written.
        let data = try encoded(credentials)

        var addQuery = baseQuery(reference: reference)
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = accessibility

        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        if addStatus == errSecSuccess {
            return
        }
        guard addStatus == errSecDuplicateItem else {
            throw CredentialStoreError.keychain(addStatus)
        }

        // The reference already has an item, so update it in place rather
        // than delete-then-add. `SecItemUpdate` is atomic: a concurrent load
        // never observes a reference that exists with nothing behind it,
        // whereas delete-then-add opens exactly that window, in which a
        // scheduled refresh would wrongly conclude the user signed out.
        let updatedAttributes: [String: Any] = [
            kSecValueData as String: data,
            // Re-asserted on write-back so an item first created by an older
            // build is normalised to the accessibility above.
            kSecAttrAccessible as String: accessibility
        ]
        let updateStatus = SecItemUpdate(
            baseQuery(reference: reference) as CFDictionary,
            updatedAttributes as CFDictionary
        )
        switch updateStatus {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            // The item vanished between the failed add and this update —
            // another writer deleted it. Put it back rather than surfacing
            // a transient race to the caller.
            let retryStatus = SecItemAdd(addQuery as CFDictionary, nil)
            if retryStatus != errSecSuccess {
                throw CredentialStoreError.keychain(retryStatus)
            }
            return
        default:
            throw CredentialStoreError.keychain(updateStatus)
        }
    }

    public func delete(reference: String) async throws {
        let status = SecItemDelete(baseQuery(reference: reference) as CFDictionary)
        switch status {
        case errSecSuccess, errSecItemNotFound:
            // "Not found" is success: the caller's desired end state — no
            // stored item — is precisely what the keychain reports.
            return
        default:
            throw CredentialStoreError.keychain(status)
        }
    }

    public func allReferences() async throws -> [String] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnAttributes as String: true,
            // Attributes only: the caller wants keys, and asking for the
            // payload would drag token material into the process for no
            // reason at all.
            kSecReturnData as String: false
        ]

        var queryResult: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &queryResult)
        switch status {
        case errSecSuccess:
            break
        case errSecItemNotFound:
            return []
        default:
            throw CredentialStoreError.keychain(status)
        }

        guard let items = queryResult as? [[String: Any]] else {
            throw CredentialStoreError.decodingFailed
        }
        var references = Set<String>()
        for item in items {
            if let account = item[kSecAttrAccount as String] as? String {
                references.insert(account)
            }
        }
        return references.sorted()
    }

    // MARK: - Internals

    /// The identifying core of every operation: one generic-password item per
    /// (service, reference) pair. `kSecClassGenericPassword` rather than an
    /// internet password, because the key is our own stable reference — an
    /// internet password is keyed by a server URL, which would orphan every
    /// stored credential the day a provider renamed its host.
    private func baseQuery(reference: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: reference
        ]
    }

    /// Serialises one credential. A fresh encoder per call: encoders are
    /// cheap, and holding one as a stored property would reintroduce exactly
    /// the mutable state this class's checked `Sendable` conformance depends
    /// on being absent.
    ///
    /// ISO 8601 dates rather than the encoder's deferred default: a fixed
    /// absolute format round-trips without depending on the device's
    /// calendar or time zone, and stays greppable whenever a stored item has
    /// to be inspected by hand.
    private func encoded(_ credentials: OAuthCredentials) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        do {
            return try encoder.encode(credentials)
        } catch {
            // Discarded rather than wrapped: a serialisation error can name
            // the property it failed on.
            throw CredentialStoreError.encodingFailed
        }
    }

    /// Deserialises one credential under the same date strategy as
    /// `encoded(_:)`. The underlying error is discarded for the same reason
    /// as there.
    private func decoded(from data: Data) throws -> OAuthCredentials {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        do {
            return try decoder.decode(OAuthCredentials.self, from: data)
        } catch {
            throw CredentialStoreError.decodingFailed
        }
    }
}

#endif

// MARK: - In-memory store (tests, and the Linux build)

/// An actor-isolated `CredentialStore` holding credentials in process memory
/// only.
///
/// It exists for the unit tests — deterministic, instant, and free of the
/// keychain entitlements a test host would otherwise need — and for the Linux
/// build, where `Security` does not exist. An actor rather than a
/// lock-protected class, because the Apple locking primitives are themselves
/// unavailable on Linux, and actor isolation buys the same exclusion
/// guarantees using nothing but the language.
///
/// Nothing persists: when the process dies, the credentials go with it.
/// That is the right behaviour for tests and for anything before first
/// authentication; production uses `KeychainCredentialStore`.
public actor InMemoryCredentialStore: CredentialStore {
    /// Backing storage. `OAuthCredentials` is a value type of `Sendable`
    /// fields, so state confined to this actor is race-free by construction.
    private var credentials: [String: OAuthCredentials] = [:]

    public init() {}

    /// Seeds the store with known contents, so a test can build a whole
    /// world in one expression instead of a sequence of saves.
    public init(credentials: [String: OAuthCredentials]) {
        self.credentials = credentials
    }

    public func load(reference: String) async throws -> OAuthCredentials? {
        credentials[reference]
    }

    public func save(_ credentials: OAuthCredentials, reference: String) async throws {
        self.credentials[reference] = credentials
    }

    public func delete(reference: String) async throws {
        credentials[reference] = nil
    }

    public func allReferences() async throws -> [String] {
        // Sorted to match the keychain store's behaviour, so callers cannot
        // grow to rely on an insertion order that a restart would lose.
        credentials.keys.sorted()
    }
}