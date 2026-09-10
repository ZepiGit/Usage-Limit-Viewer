import AuthenticationServices
import Foundation
import UIKit
import UsageLimitsKit

/// Presents a loopback sign-in and returns the authorisation code.
///
/// Two things have to be true at once, and this type exists to make them so.
///
/// The browser must be `ASWebAuthenticationSession`. It presents IN PROCESS, so the app stays
/// foregrounded and the loopback listener stays alive while the user signs in. Sending them to
/// the system browser instead would background the app, and a suspended app does not accept
/// connections — the redirect would arrive at a closed port.
///
/// And the listener must already be bound before the browser is opened, or a provider that
/// redirects quickly finds nothing listening.
@MainActor
final class LoopbackSignIn: NSObject {

    private var session: ASWebAuthenticationSession?

    /// Runs the sign-in and returns the code the provider redirected with.
    ///
    /// TWO paths race, and that is deliberate rather than indecisive.
    ///
    /// `ASWebAuthenticationSession` matches `callbackURLScheme` against top-frame navigations, so
    /// passing `http` may make it complete the moment the provider redirects to the loopback URL
    /// — handing over the code without the request ever being issued. That is the better outcome
    /// when it happens: no port to bind, no listener to attack, and it survives the user
    /// backgrounding the app to fetch a code from Messages. But whether Apple honours `http`
    /// there is not documented, and a sign-in that depends on undocumented behaviour is a
    /// sign-in that breaks on an OS update.
    ///
    /// So both run. If the session intercepts, its callback wins; if it merely LOADS the URL,
    /// the listener answers it and wins. Neither path is load-bearing on its own.
    func authorize(_ challenge: LoopbackChallenge) async throws -> String {
        // Built from the challenge rather than its parts, so this never handles the state and
        // cannot open a listener for one attempt while checking another's.
        let listener = LoopbackListener(challenge: challenge)

        return try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask { try await listener.awaitCode() }
            // A plain closure that awaits into this actor, rather than one annotated
            // `@MainActor` in place. Both mean the same thing, but the annotated form is a
            // shape the region-based isolation checker rejects outright — "does not understand
            // how to check" — because the closure is main-actor-isolated while `addTask` wants
            // a sendable one, and the values crossing that edge are a class and a struct it
            // cannot follow. Naming the isolated work as a method moves the hop to a call it
            // does understand.
            group.addTask { try await self.interceptedCode(for: challenge) }

            defer { group.cancelAll(); self.session?.cancel(); self.session = nil }
            guard let code = try await group.next() else { throw CancellationError() }
            return code
        }
    }

    /// The browser half of the race: present the sign-in and read a code out of an intercepted
    /// redirect, if there is one to read.
    ///
    /// Isolated to the main actor because presenting is, and because `session` is this object's
    /// mutable state.
    private func interceptedCode(for challenge: LoopbackChallenge) async throws -> String {
        let callback = try await present(challenge.url)

        if let callback {
            // The session intercepted the redirect. The challenge does the reading, so this
            // never handles the state and cannot get the comparison wrong — and it is the same
            // check, through the same function, that the listener applies.
            return try challenge.code(fromCallback: callback)
        }

        // The sheet closed without a callback. That is not an answer: the user may have finished
        // in the browser and dismissed it while the redirect was still in flight, so give the
        // listener a moment rather than racing it to a conclusion.
        try await Task.sleep(nanoseconds: 2 * NSEC_PER_SEC)
        throw CancellationError()
    }

    /// Opens the provider's own sign-in page, and reports the callback URL if it intercepts one.
    ///
    /// `http` as the callback scheme is the interception attempt described on `authorize`. If
    /// the session honours it, the redirect completes here and the returned URL carries the
    /// code. If it does not, the loopback URL simply loads, the listener answers it, and this
    /// returns nil when the user dismisses the sheet — which is why nil is not an error.
    ///
    /// Both authorize endpoints are https end to end, so there is no intermediate http
    /// navigation for this to match by accident.
    private func present(_ url: URL) async throws -> URL? {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL?, any Error>) in
            let session = ASWebAuthenticationSession(
                url: url, callbackURLScheme: "http"
            ) { callback, error in
                if let sessionError = error as? ASWebAuthenticationSessionError,
                   sessionError.code == .canceledLogin {
                    // The user closed the sheet. Not a failure to report — the listener may
                    // still have the answer.
                    continuation.resume(returning: nil)
                } else if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: callback)
                }
            }
            session.presentationContextProvider = self
            // A fresh session every time. Reusing the browser's cookies would silently sign the
            // user into whichever account the browser already holds, which is wrong for an app
            // whose entire purpose is holding SEVERAL accounts per provider.
            session.prefersEphemeralWebBrowserSession = true
            self.session = session

            if !session.start() {
                continuation.resume(
                    throwing: DeviceLoginError.malformedResponse(
                        "the sign-in page could not be opened"))
            }
        }
    }
}

extension LoopbackSignIn: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        // The key window of the active foreground scene. Nil-coalescing to a fresh window rather
        // than force-unwrapping: a sign-in that cannot find an anchor should fail to present,
        // not terminate the app.
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        return scene?.keyWindow ?? ASPresentationAnchor()
    }
}
