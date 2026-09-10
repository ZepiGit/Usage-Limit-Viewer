import Foundation

// The Network surface the kit's `LoopbackListener` uses, so its real Darwin-only source can
// be type-checked off a Mac. Nothing here opens a socket.

public struct IPv4Address: Sendable {
    public static let loopback = IPv4Address()
    public init() {}
    public init?(_ string: String) {}
}

/// An enum, as the real one is: the kit builds `.hostPort(host:port:)` and matches on it.
public enum NWEndpoint: Sendable {
    case hostPort(host: Host, port: Port)

    public struct Port: ExpressibleByIntegerLiteral, Equatable, Sendable {
        public let rawValue: UInt16
        public init(integerLiteral value: UInt16) { rawValue = value }
        public init?(rawValue: UInt16) { self.rawValue = rawValue }
        public static let any = Port(integerLiteral: 0)
    }

    public enum Host: ExpressibleByStringLiteral, Sendable {
        case ipv4(IPv4Address)
        case name(String)
        public init(stringLiteral value: String) { self = .name(value) }
        public init(_ value: String) { self = .name(value) }
    }
}

public final class NWParameters: @unchecked Sendable {
    public static var tcp: NWParameters { NWParameters() }
    public init() {}
    public var allowLocalEndpointReuse: Bool = false
    public var acceptLocalOnly: Bool = false
    public var requiredLocalEndpoint: NWEndpoint?
}

public final class NWConnection: @unchecked Sendable {
    public enum State: Equatable, Sendable {
        case setup, preparing, ready, cancelled
        case waiting(NWError), failed(NWError)
    }
    public var stateUpdateHandler: ((State) -> Void)?
    public func start(queue: DispatchQueue) {}
    public func cancel() {}
    public func receive(minimumIncompleteLength: Int, maximumLength: Int,
                        completion: @escaping (Data?, ContentContext?, Bool, NWError?) -> Void) {}
    public func send(content: Data?, completion: SendCompletion) {}
    public struct ContentContext {}
    public enum SendCompletion {
        case idempotent
        case contentProcessed((NWError?) -> Void)
    }
}

public struct NWError: Error, Equatable, Sendable {
    public let debugDescription: String
    public init(debugDescription: String = "") { self.debugDescription = debugDescription }
}

public final class NWListener: @unchecked Sendable {
    public enum State: Equatable, Sendable {
        case setup, waiting(NWError), ready, cancelled, failed(NWError)
    }
    public init(using parameters: NWParameters, on port: NWEndpoint.Port) throws {}
    public var port: NWEndpoint.Port? { .any }
    public var stateUpdateHandler: ((State) -> Void)?
    public var newConnectionHandler: ((NWConnection) -> Void)?
    public func start(queue: DispatchQueue) {}
    public func cancel() {}
}
