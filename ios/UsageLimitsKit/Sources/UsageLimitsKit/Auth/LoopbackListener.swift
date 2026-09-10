import Foundation

#if canImport(Network)
import Network

/// Answers exactly one OAuth redirect on 127.0.0.1.
///
/// An iOS app can bind and accept on loopback: the local-network permission governs the LAN and
/// Bonjour, not 127.0.0.1, so no usage description and no entitlement are required. What makes
/// this work at all is that the browser is `ASWebAuthenticationSession`, which presents in-process
/// — the app stays foregrounded and the socket stays alive while the user signs in. Sending them
/// to the system browser would background the app, and a suspended app does not accept
/// connections.
///
/// The rules this enforces, each because the alternative is worse than a failed sign-in:
///
/// - Bound to 127.0.0.1 explicitly, NEVER to all interfaces. A listener on 0.0.0.0 offers the
///   authorisation code to anything that can route to the device.
/// - One answer, then closed. The port is not left open after the code arrives.
/// - A deadline, so a sign-in the user abandoned does not leave a socket bound for the life of
///   the process.
/// - Nothing is read beyond a bounded request, and only the request line is parsed.
public actor LoopbackListener {

    public enum ListenError: Error, LocalizedError, Equatable {
        /// The port a provider registered is already taken by something else on the device.
        case portUnavailable(UInt16)
        case timedOut
        case refused(String)

        public var errorDescription: String? {
            switch self {
            case .portUnavailable(let port):
                return "Port \(port) is in use, so the sign-in cannot be received. "
                    + "Close whatever is using it and try again."
            case .timedOut:
                return "The sign-in was not completed in time."
            case .refused(let reason):
                return reason
            }
        }
    }

    private let port: UInt16
    private let path: String
    private let state: String
    private let timeout: TimeInterval

    /// The listener for a challenge.
    ///
    /// Preferred over naming the parts, so the caller never handles the state or the path — and
    /// so a listener cannot be opened for one attempt while checking another's state.
    public init(challenge: LoopbackChallenge, timeout: TimeInterval = 300) {
        self.init(
            port: challenge.port, path: challenge.path, state: challenge.state, timeout: timeout)
    }

    public init(port: UInt16, path: String, state: String, timeout: TimeInterval = 300) {
        self.port = port
        self.path = path
        self.state = state
        self.timeout = timeout
    }

    /// Binds, waits for the redirect, and returns the authorisation code.
    ///
    /// Cancellation is honoured: closing the sign-in screen tears the listener down rather than
    /// leaving it bound.
    public func awaitCode() async throws -> String {
        guard let endpointPort = NWEndpoint.Port(rawValue: port) else {
            throw ListenError.portUnavailable(port)
        }

        let parameters = NWParameters.tcp
        // Loopback only. This is the single most important line in the file.
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(
            host: .ipv4(.loopback), port: endpointPort)
        parameters.allowLocalEndpointReuse = true

        let listener: NWListener
        do {
            listener = try NWListener(using: parameters, on: endpointPort)
        } catch {
            throw ListenError.portUnavailable(port)
        }

        return try await withTaskCancellationHandler {
            try await withThrowingTaskGroup(of: String.self) { group in
                group.addTask { try await self.accept(on: listener) }
                group.addTask {
                    try await Task.sleep(nanoseconds: UInt64(self.timeout * 1_000_000_000))
                    throw ListenError.timedOut
                }
                defer { group.cancelAll(); listener.cancel() }

                guard let code = try await group.next() else { throw ListenError.timedOut }
                return code
            }
        } onCancel: {
            listener.cancel()
        }
    }

    /// One connection at a time until one of them is the redirect.
    ///
    /// A browser opens more than one connection to a page — a favicon fetch alone would end the
    /// wait if the first connection were treated as the answer — so requests that are not the
    /// redirect are answered and the listener keeps waiting.
    private func accept(on listener: NWListener) async throws -> String {
        let queue = DispatchQueue(label: "com.usagelimits.loopback")

        return try await withCheckedThrowingContinuation { continuation in
            let finished = Finished()

            listener.stateUpdateHandler = { state in
                switch state {
                case .failed:
                    if finished.claim() {
                        continuation.resume(throwing: ListenError.portUnavailable(self.port))
                    }
                case .cancelled:
                    // Cancellation MUST resume the continuation, and this is the only place it
                    // can. Without this arm the sign-in deadlocks rather than ending: the
                    // timeout or the user closing the sheet cancels the listener, the
                    // continuation is never resumed, and the task group — which waits for every
                    // child before returning — waits for a child that can never finish.
                    if finished.claim() {
                        continuation.resume(throwing: CancellationError())
                    }
                default:
                    break
                }
            }

            listener.newConnectionHandler = { connection in
                connection.start(queue: queue)
                Self.read(connection) { request in
                    let outcome = LoopbackRedirect.interpret(
                        request: request, expectedPath: self.path, expectedState: self.state,
                        expectedPort: self.port)

                    switch outcome {
                    case .ignored:
                        // Not an answer to THIS attempt — including a mismatched state. Answer
                        // briefly and keep waiting, because every process on the device shares
                        // this address and stopping on the first stranger hands any of them a
                        // way to break a sign-in in progress.
                        Self.reply(connection, LoopbackRedirect.failureResponse(
                            message: "Nothing to see here."))
                    case .code(let code):
                        Self.reply(connection, LoopbackRedirect.successResponse())
                        if finished.claim() { continuation.resume(returning: code) }
                    case .rejected(let message):
                        Self.reply(connection, LoopbackRedirect.failureResponse(message: message))
                        if finished.claim() {
                            continuation.resume(throwing: ListenError.refused(message))
                        }
                    }
                }
            }

            listener.start(queue: queue)
        }
    }

    /// Reads a bounded request, stopping at the end of the headers.
    ///
    /// The request line is all that is parsed, so there is no reason to wait for a body — and
    /// waiting for one is how a connection that never sends it stalls the sign-in.
    private static func read(
        _ connection: NWConnection,
        accumulated: Data = Data(),
        then handle: @escaping (String) -> Void
    ) {
        connection.receive(
            minimumIncompleteLength: 1,
            maximumLength: LoopbackRedirect.maximumRequestBytes
        ) { chunk, _, isComplete, error in
            var buffer = accumulated
            if let chunk { buffer.append(chunk) }

            let text = String(decoding: buffer, as: UTF8.self)
            let done = text.contains("\r\n\r\n")
                || isComplete
                || error != nil
                || buffer.count >= LoopbackRedirect.maximumRequestBytes

            if done {
                handle(text)
            } else {
                read(connection, accumulated: buffer, then: handle)
            }
        }
    }

    private static func reply(_ connection: NWConnection, _ response: String) {
        connection.send(
            content: Data(response.utf8),
            completion: .contentProcessed { _ in connection.cancel() })
    }

    /// Resumes a continuation exactly once.
    ///
    /// Several connections can arrive at once and each is handled on a concurrent queue, so
    /// without this a second redirect — or a failure racing a success — resumes a continuation
    /// that is already spent, which traps.
    private final class Finished: @unchecked Sendable {
        private let lock = NSLock()
        private var claimed = false

        func claim() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            if claimed { return false }
            claimed = true
            return true
        }
    }
}
#endif
