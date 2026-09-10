import Foundation

/// Receiving an OAuth redirect that was addressed to `http://localhost:PORT/...`.
///
/// Two of the four providers issue their authorisation code to a loopback address, because their
/// official clients are desktop CLIs and RFC 8252 §7.3 names loopback as the redirect for a
/// native app that cannot register a scheme. The app must therefore answer one HTTP request on
/// 127.0.0.1 and read the code out of the request line.
///
/// Everything in this file is pure: bytes in, decision out. The socket lives next door in
/// `LoopbackListener`, which exists only where `Network` does — which is what lets the part that
/// can actually be wrong be tested on Linux.
public enum LoopbackRedirect {

    /// What a request to the loopback listener turned out to be.
    public enum Outcome: Sendable, Equatable {
        /// The provider redirected here with an authorisation code, and the state matched.
        case code(String)

        /// The provider refused, and said so on OUR attempt — the state matched. This ends the
        /// sign-in. The message is safe to display: it is built from the provider's own error
        /// code and from nothing the request supplied verbatim.
        case rejected(String)

        /// Not an answer to this attempt: a favicon fetch, a probe, a stray tab, or a request
        /// whose state does not match. Keep listening.
        ///
        /// A mismatched state belongs HERE and not in `rejected`, and the difference matters:
        /// every process on the device shares 127.0.0.1, so any of them can connect to this port
        /// and send something. Treating the first bad request as a failure lets any local
        /// process end a sign-in the user is halfway through, simply by connecting first.
        case ignored
    }

    /// The largest request this will read.
    ///
    /// A redirect carries a code, a state and little else; anything approaching this is not the
    /// provider. The cap exists so a client that opens a connection and streams cannot make the
    /// app allocate without bound while the user waits on a sign-in screen.
    public static let maximumRequestBytes = 16 * 1024

    /// Reads one HTTP request and decides what it was.
    ///
    /// - Parameters:
    ///   - request: the raw bytes as they arrived. Only the request line is read; headers and
    ///     any body are ignored entirely, because nothing this app needs can be in them and
    ///     parsing more is more that can be wrong.
    ///   - expectedPath: the path this listener was opened for, so a request to anything else is
    ///     not mistaken for the redirect.
    ///   - expectedState: the `state` issued for this attempt. Compared in constant time, before
    ///     anything else in the URL is read.
    ///   - expectedPort: the port this listener is bound to, checked against the request's `Host`.
    public static func interpret(
        request: String,
        expectedPath: String,
        expectedState: String,
        expectedPort: UInt16? = nil
    ) -> Outcome {
        // The request line is the first line, and its shape is `METHOD SP target SP version`.
        guard let line = request.split(whereSeparator: \.isNewline).first else {
            return .ignored
        }
        let parts = line.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count >= 2, parts[0] == "GET" else { return .ignored }

        // The Host header, checked before the target is read.
        //
        // A page in the user's browser can be made to resolve a name to 127.0.0.1 and issue
        // requests at this port — DNS rebinding. The state check already stops such a request
        // being mistaken for the redirect, but a request that never claimed to be addressed to
        // this listener should not be examined at all.
        if let expectedPort, let host = header(named: "host", in: request) {
            let permitted = ["127.0.0.1:\(expectedPort)", "localhost:\(expectedPort)"]
            guard permitted.contains(host.lowercased()) else { return .ignored }
        }

        let target = String(parts[1])
        // The target is origin-form (`/callback?code=…`), so it is resolved against a base to be
        // parsed at all. The host here is a parsing convenience and is never contacted.
        guard let url = URL(string: target, relativeTo: URL(string: "http://127.0.0.1")) else {
            return .ignored
        }
        guard url.path == expectedPath else { return .ignored }

        do {
            return .code(try OAuthFlows.code(from: url, expectedState: expectedState))
        } catch let error as OAuthCallbackError {
            switch error {
            case .stateMismatch:
                // Not an answer, and deliberately not a failure — see `ignored`. Anything on the
                // device can reach this port, so ending the sign-in on the first request that
                // does not match hands any local process a way to break it.
                return .ignored
            case .providerError(let code, _):
                // The provider's own code, and never its description: that description is
                // attacker-influenced text arriving over plain http on a port anything local can
                // reach, and this string is rendered.
                return .rejected("The provider refused the sign-in (\(code)).")
            case .missingCode:
                return .rejected("The sign-in came back without an authorisation code.")
            }
        } catch {
            return .rejected("The sign-in could not be read.")
        }
    }

    /// The page the browser is left showing.
    ///
    /// Deliberately a complete, tiny response with an explicit `Content-Length` and
    /// `Connection: close`, so the browser renders it and stops rather than holding the socket
    /// open waiting for more. It names no code, no state and no account: this is served over
    /// plain http and ends up in the browser's history.
    public static func successResponse(
        title: String = "Signed in",
        message: String = "You can close this page and return to Usage Limits."
    ) -> String {
        response(status: "200 OK", title: title, message: message)
    }

    public static func failureResponse(message: String) -> String {
        response(status: "400 Bad Request", title: "Sign-in failed", message: message)
    }

    private static func response(status: String, title: String, message: String) -> String {
        let body = """
        <!doctype html><meta charset="utf-8"><title>\(escape(title))</title>\
        <body style="font:16px -apple-system,system-ui,sans-serif;margin:3rem;color:#1a1918">\
        <h1 style="font-size:1.2rem">\(escape(title))</h1><p>\(escape(message))</p>
        """
        return """
        HTTP/1.1 \(status)\r
        Content-Type: text/html; charset=utf-8\r
        Content-Length: \(body.utf8.count)\r
        Cache-Control: no-store\r
        Connection: close\r
        \r
        \(body)
        """
    }

    /// One header's value, case-insensitively.
    private static func header(named name: String, in request: String) -> String? {
        for line in request.split(whereSeparator: \.isNewline).dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            guard line[line.startIndex..<colon].lowercased() == name else { continue }
            return line[line.index(after: colon)...]
                .trimmingCharacters(in: .whitespaces)
        }
        return nil
    }

    /// Escapes text before it goes into the page.
    ///
    /// Every string reaching here is one this app wrote, so this is belt and braces — but the
    /// page is rendered by a real browser, and a rule that only holds while nobody adds a
    /// provider-supplied string to it is not a rule.
    private static func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }
}
