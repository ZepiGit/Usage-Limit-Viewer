import Foundation

/// Resolves the listener and browser results for a single loopback sign-in.
public struct LoopbackSignInRace {
    private var failure: (any Error)?

    public init() {}

    /// A failed path leaves the other path available to return a code.
    public mutating func receive(
        _ result: Result<String, any Error>, taskIsCancelled: Bool
    ) throws -> String? {
        if taskIsCancelled { throw CancellationError() }
        switch result {
        case .success(let code):
            return code
        case .failure(let error):
            // Browser dismissal ends its grace period with CancellationError too.
            // Preserve a bind failure so Codex can select its device-code fallback.
            if error is CancellationError { throw failure ?? error }
            failure = error
            return nil
        }
    }

    public var terminalError: any Error { failure ?? CancellationError() }
}
