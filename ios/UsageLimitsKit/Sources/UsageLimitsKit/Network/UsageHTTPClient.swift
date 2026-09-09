//
//  UsageHTTPClient.swift
//  UsageLimitsKit
//
//  The Swift twin of the Android app's OkHttp quota client. It fetches usage
//  data from four providers' undocumented internal endpoints, so nothing on
//  the wire is trusted: target URLs, status codes, Retry-After headers and
//  error bodies are all treated as hostile until they have been checked.
//

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

// MARK: - Responses and errors

/// One finished exchange with a provider endpoint.
///
/// The body is decoded lossily: a diagnosis that shows U+FFFD replacement
/// characters is worth more than a decode failure that hides the status which
/// produced it. Header names are lower-cased because HTTP names are
/// case-insensitive and the four providers capitalise them differently; when
/// a name repeats, the last value wins, which costs nothing for the
/// single-valued fields this client reads.
public struct HTTPResponse: Sendable {
    public let status: Int
    public let body: String
    public let headers: [String: String]

    public init(status: Int, body: String, headers: [String: String]) {
        self.status = status
        self.body = body
        self.headers = headers
    }
}

/// Every way a request can fail, with payloads kept small enough that no case
/// can smuggle credentials back out of the client.
public enum HTTPError: Error {
    /// The target is not HTTPS (and not permitted loopback http). The
    /// offending string is our own configuration rather than network-supplied
    /// data, so echoing it cannot leak anything an attacker controls.
    case insecureURL(String)

    /// The target could not be parsed as an absolute URL at all.
    case invalidURL(String)

    /// The transport failed and the retry budget is spent. The payload is
    /// whatever the transport itself reported; request headers never travel
    /// with it.
    case transport(Error)

    /// A status a retry cannot fix. The body is truncated before it reaches
    /// this case, and request headers are never included in any error.
    case status(code: Int, body: String)

    /// A 429 that outlasted the retry budget. `retryAfter` is the server's
    /// hint when one was sent, parseable and plausible — and already clamped
    /// to the same ceiling the client itself honours, so a caller acting on
    /// the hint cannot be talked into parking a background task for a day
    /// either.
    case rateLimited(retryAfter: TimeInterval?)
}

// MARK: - Transport

/// One attempt to hand a request to the network and get bytes back.
///
/// The seam exists so tests can script the four providers' undocumented
/// endpoints — their 429 storms, malformed Retry-After headers and echoing
/// error pages — without a network, and so the transport can be swapped
/// without touching retry policy.
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, URLResponse)
}

/// The production transport, backed by URLSession.
///
/// The unchecked conformance is sound: URLSession is documented thread-safe
/// and this type holds nothing else mutable.
public final class URLSessionTransport: HTTPTransport, @unchecked Sendable {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        try await session.data(for: request)
    }
}

// MARK: - Client

/// Shared HTTP client for the providers' internal quota endpoints.
///
/// Defensive by design: targets are validated before anything is sent,
/// retries are spent only where they can help, Retry-After is treated as
/// attacker-influenced input, and no error ever carries request headers or an
/// untrimmed body.
public actor UsageHTTPClient {

    // MARK: Policy

    /// The ceiling on any wait this client imposes on itself, whether the
    /// server suggested it or backoff computed it. One policy for both, so
    /// hostile input can never park a background task for minutes on end.
    private static let maximumWait: TimeInterval = 30

    /// A Retry-After above a full day is not a schedule; it is a broken clock
    /// or an attack, so it is rejected as malformed rather than obeyed — or
    /// even clamped.
    private static let implausibleRetryAfter: TimeInterval = 86_400

    /// Response bodies carried into errors stop here: provider error pages
    /// sometimes echo request material — redirect URIs carry authorisation
    /// codes — and a bearer token fits comfortably in a few hundred
    /// characters.
    private static let errorBodyLimit = 512

    /// Plain http is permitted only for these hosts. The OAuth loopback
    /// redirect receiver binds a local http listener because that traffic
    /// never leaves the device; everything else must be HTTPS.
    private static let loopbackHosts: Set<String> = ["localhost", "127.0.0.1", "::1"]

    /// Backoff doubles from here, bounded by `maximumWait`.
    private static let backoffBase: TimeInterval = 0.5

    /// Each individual attempt is capped so one wedged endpoint consumes a
    /// bounded slice of time; the retry budget then means something in
    /// seconds as well as in attempts.
    private static let perAttemptTimeout: TimeInterval = 30

    // MARK: Lifecycle

    private let transport: any HTTPTransport
    private let maxRetries: Int
    private let now: @Sendable () -> Date

    /// - Parameters:
    ///   - transport: normally the URLSession-backed transport; tests inject a
    ///     scripted one so the undocumented endpoints can be exercised
    ///     without a network.
    ///   - maxRetries: additional attempts after the first, clamped at zero
    ///     because a negative budget should mean "no retries", not "refuse to
    ///     send at all".
    ///   - now: the clock HTTP-date Retry-After values are measured against;
    ///     injectable so date handling is testable deterministically.
    public init(
        transport: any HTTPTransport,
        maxRetries: Int = 2,
        // A closure literal rather than `Date.init`: the initialiser reference is not
        // itself @Sendable, so passing it as the default warns under strict concurrency.
        now: @Sendable @escaping () -> Date = { Date() }
    ) {
        self.transport = transport
        self.maxRetries = max(0, maxRetries)
        self.now = now
    }

    // MARK: Requesting

    /// Sends one request and returns the finished exchange.
    ///
    /// Retries are spent only where they can help: 429 (the window is
    /// time-based and may genuinely reopen), 5xx (a sick back-end may
    /// recover) and transport faults (a dropped connection may re-establish).
    /// Every other status answers an identical request identically, so those
    /// surface immediately as `.status` with a truncated body instead of
    /// burning attempts on foregone conclusions.
    ///
    /// A 429 that outlives the budget surfaces as `.rateLimited` carrying the
    /// already-clamped server hint, so a caller can schedule a later attempt
    /// rather than spin. Cancellation is honoured at every seam: waits are
    /// `Task.sleep`, and `CancellationError` is rethrown rather than absorbed,
    /// so a caller that gives up is never dragged through another attempt.
    /// Total added latency is bounded — each wait at `maximumWait`, each
    /// attempt at `perAttemptTimeout`.
    public func request(
        url: String,
        method: String = "GET",
        headers: [String: String] = [:],
        body: Data? = nil
    ) async throws -> HTTPResponse {
        // Validated before anything is built or sent, so a plaintext URL
        // never even reaches the transport.
        let target = try Self.validatedTarget(url)

        var request = URLRequest(url: target)
        request.timeoutInterval = Self.perAttemptTimeout
        // The endpoints speak canonical verbs; a stray lowercase "get" would
        // otherwise draw a 405 that no retry can fix.
        request.httpMethod = method.uppercased()
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.httpBody = body

        var attemptIndex = 0
        while true {
            // Checked before every attempt, so cancellation raised during a
            // wait still stops the next send.
            try Task.checkCancellation()

            switch try await verdict(for: request, attemptIndex: attemptIndex) {
            case .delivered(let response):
                return response
            case .failed(let failure):
                throw failure
            case .waitAndRetry(let delay):
                try await Task.sleep(nanoseconds: UInt64((max(0, delay) * 1_000_000_000).rounded()))
                attemptIndex += 1
            }
        }
    }

    // MARK: Attempt machinery

    /// The outcome of one attempt, classified so that the sleeping — which
    /// must never sit inside the attempt's own catch-alls — stays in the
    /// caller's flat retry loop.
    private enum Verdict {
        case delivered(HTTPResponse)
        case failed(HTTPError)
        case waitAndRetry(TimeInterval)
    }

    /// Runs a single attempt and classifies the result. Throws only
    /// `CancellationError` — by construction, so the catch-alls below can
    /// never mistake a caller's exit for a flaky socket — and folds every
    /// other failure into the verdict.
    private func verdict(for request: URLRequest, attemptIndex: Int) async throws -> Verdict {
        let budgetSpent = attemptIndex >= maxRetries

        do {
            let (data, response) = try await transport.send(request)

            guard let http = response as? HTTPURLResponse else {
                // The transport contract is HTTP; anything else is a transport
                // fault, so it is retried and surfaced as one.
                if budgetSpent {
                    return .failed(.transport(NonHTTPResponseFault(
                        unexpectedTypeName: String(describing: type(of: response))
                    )))
                }
                return .waitAndRetry(Self.backoffDelay(afterAttempt: attemptIndex))
            }

            let status = http.statusCode
            let headerFields = Self.normalisedHeaders(http.allHeaderFields)
            let text = String(decoding: data, as: UTF8.self)

            if (200..<300).contains(status) {
                return .delivered(HTTPResponse(status: status, body: text, headers: headerFields))
            }

            let retryCouldHelp = status == 429 || (500..<600).contains(status)
            guard retryCouldHelp else {
                // 401, 403, 404, a stray 3xx: deterministic answers. Another
                // attempt draws the same one, so it is reported at once — with
                // a truncated body, and never with request headers.
                return .failed(.status(code: status, body: Self.truncated(text)))
            }

            if budgetSpent {
                if status == 429 {
                    return .failed(.rateLimited(retryAfter: Self.honouredRetryAfter(
                        headerFields["retry-after"], at: now()
                    )))
                }
                return .failed(.status(code: status, body: Self.truncated(text)))
            }

            // A plausible Retry-After is honoured within the clamp —
            // whichever retryable status sent it; otherwise backoff decides.
            let wait = Self.honouredRetryAfter(headerFields["retry-after"], at: now())
                ?? Self.backoffDelay(afterAttempt: attemptIndex)
            return .waitAndRetry(wait)
        } catch let cancellation as CancellationError {
            // Must pierce the catch-all below: a cancelled caller wants out,
            // not another attempt.
            throw cancellation
        } catch let failure as HTTPError {
            // A transport may fail fast with an HTTPError of its own; pass it
            // through untouched instead of double-wrapping it.
            return .failed(failure)
        } catch {
            if budgetSpent {
                return .failed(.transport(error))
            }
            return .waitAndRetry(Self.backoffDelay(afterAttempt: attemptIndex))
        }
    }

    // MARK: Target validation

    /// Admits a target only if it is HTTPS — or plain http aimed at the
    /// device's own loopback, which exists for the OAuth redirect receiver.
    ///
    /// Scheme and host are read from the parsed authority, never from the raw
    /// string, so camouflage fails closed:
    /// `http://evil.example/?x=http://localhost` is judged by its actual host,
    /// and `http://localhost@evil.example/` by its actual host too, because
    /// parsing ignores the userinfo decoy. A substring match on "localhost"
    /// or a scheme prefix would let both through.
    private static func validatedTarget(_ raw: String) throws -> URL {
        guard let parsed = URL(string: raw),
              let components = URLComponents(url: parsed, resolvingAgainstBaseURL: false),
              let scheme = components.scheme?.lowercased(),
              let host = components.host
        else {
            // Without a scheme and a host there is no authority to inspect
            // and nothing to send. The string is our own configuration, so it
            // is safe to echo back for the operator to fix.
            throw HTTPError.invalidURL(raw)
        }

        switch scheme {
        case "https":
            return parsed
        case "http" where loopbackHosts.contains(canonicalHost(host)):
            return parsed
        default:
            // Any other scheme is either plaintext or not HTTP at all, and
            // both facts make it unfit for token-bearing traffic.
            throw HTTPError.insecureURL(raw)
        }
    }

    /// IPv6 authorities wear brackets in URLs and platforms disagree about
    /// whether URLComponents keeps them, so strip and lower-case before
    /// comparing.
    private static func canonicalHost(_ host: String) -> String {
        var host = host.lowercased()
        if host.hasPrefix("["), host.hasSuffix("]") {
            host.removeFirst()
            host.removeLast()
        }
        return host
    }

    // MARK: Retry-After

    /// Reads a Retry-After header — delay-seconds or HTTP-date — and returns
    /// the delay this client will actually honour, or nil when the header is
    /// absent, malformed or implausible.
    ///
    /// The value is attacker-influenced, so it is both sanity-checked and
    /// clamped: anything above a full day is rejected as malformed rather
    /// than obeyed or truncated, and everything else is capped at
    /// `maximumWait`, because a header demanding 86400 seconds must not park
    /// a background task for a day.
    private static func honouredRetryAfter(_ raw: String?, at reference: Date) -> TimeInterval? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }

        let requested: TimeInterval
        if let seconds = UInt(trimmed) {
            requested = TimeInterval(seconds)
        } else if let date = httpDate(trimmed) {
            // A date already in the past means the provider believes the
            // window has closed; waiting nothing is the honest reading, and
            // clock skew must not turn into a negative sleep.
            requested = max(0, date.timeIntervalSince(reference))
        } else {
            // Neither the integer nor the date form: treat the header as
            // absent rather than guess at hostile input.
            return nil
        }

        guard requested <= implausibleRetryAfter else { return nil }

        return min(requested, maximumWait)
    }

    /// Parses an HTTP-date in the IMF-fixdate form
    /// (`Sun, 06 Nov 1994 08:49:37 GMT`).
    ///
    /// Only that form is accepted: it is the only one modern providers emit,
    /// and every extra tolerated grammar is another way for hostile input to
    /// steer the parser. The weekday is not cross-checked against the date
    /// because RFC 7231 tells recipients to ignore a mismatch.
    private static func httpDate(_ text: String) -> Date? {
        let parts = text.split(separator: " ").map(String.init)
        guard parts.count == 6 else { return nil }

        let weekday = parts[0]
        guard weekday.count == 4,
              weekday.last == ",",
              weekday.dropLast().allSatisfy({ $0.isASCII && $0.isLetter })
        else { return nil }

        let monthName = parts[2].lowercased()
        let monthNumbers = [
            "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
            "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
        ]
        guard monthName.count == 3, let month = monthNumbers[monthName] else { return nil }

        guard parts[1].count == 2, let day = Int(parts[1]), (1...31).contains(day)
        else { return nil }
        guard parts[3].count == 4, let year = Int(parts[3]), (1_000...9_999).contains(year)
        else { return nil }

        let clock = parts[4].split(separator: ":").map(String.init)
        guard clock.count == 3,
              clock.allSatisfy({ $0.count == 2 }),
              let hour = Int(clock[0]), (0...23).contains(hour),
              let minute = Int(clock[1]), (0...59).contains(minute),
              let second = Int(clock[2]), (0...60).contains(second)
        else { return nil }

        guard parts[5].lowercased() == "gmt" else { return nil }

        // GMT is the only zone an IMF-fixdate may carry; the failable
        // initialiser never fails for zero, but a guard reads better than a
        // force-unwrap in a defensive parser.
        guard let gmt = TimeZone(secondsFromGMT: 0) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = gmt

        var fields = DateComponents()
        fields.year = year
        fields.month = month
        fields.day = day
        fields.hour = hour
        fields.minute = minute
        // A leap second folds onto 59: DateComponents cannot hold 60, and a
        // one-second difference never changes a retry decision.
        fields.second = min(second, 59)

        return calendar.date(from: fields)
    }

    // MARK: Backoff

    /// Retry spacing when the server offers nothing to honour.
    ///
    /// The exponential term respects how slowly a flaky back-end recovers;
    /// the jitter decorrelates clients knocked over by the same event, so
    /// four providers' worth of retries do not land in lockstep. Equal
    /// jitter — half fixed, half random — keeps the average on the
    /// exponential curve while still allowing the short waits that let a
    /// lucky request through early. The curve is capped at `maximumWait`,
    /// deliberately the same ceiling Retry-After is clamped to.
    private static func backoffDelay(afterAttempt attemptIndex: Int) -> TimeInterval {
        // The shift is capped so the doubling stays inside Int; the bound on
        // the product bites long before the shift cap matters.
        let growth = TimeInterval(1 << min(attemptIndex, 30))
        let bounded = min(maximumWait, backoffBase * growth)
        return bounded / 2 + Double.random(in: 0..<(bounded / 2))
    }

    // MARK: Redaction

    /// Bodies carried into errors stop at `errorBodyLimit` characters.
    ///
    /// Request headers are never included in any error, so the
    /// `Authorization` line and cookies never reach diagnostics at all; the
    /// truncated body is the only provider-supplied material that does.
    private static func truncated(_ body: String) -> String {
        guard body.count > errorBodyLimit else { return body }
        return String(body.prefix(errorBodyLimit)) + "…"
    }

    // MARK: Header handling

    /// Folds response header names to lower case and keeps the last value on
    /// repeats. HTTP names are case-insensitive and each provider
    /// capitalises differently, so lookups must not depend on spelling; the
    /// fields this client reads (Retry-After above all) are single-valued, so
    /// a repeated name losing earlier values costs nothing.
    private static func normalisedHeaders(_ fields: [AnyHashable: Any]) -> [String: String] {
        var folded: [String: String] = [:]
        for (name, value) in fields {
            guard let name = name as? String, let value = value as? String else { continue }
            folded[name.lowercased()] = value
        }
        return folded
    }
}

/// Signalled when a transport hands back something that is not an HTTP
/// response; the client counts it against the retry budget like any other
/// transport fault.
private struct NonHTTPResponseFault: Error, CustomStringConvertible {
    let unexpectedTypeName: String

    var description: String {
        "transport returned \(unexpectedTypeName), which is not an HTTP response"
    }
}