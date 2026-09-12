import SwiftUI
import UIKit
import UsageLimitsKit

/// Signing in, from a phone.
///
/// The device grant, which is what this screen drives, exists precisely for a device that cannot
/// receive a redirect: the provider shows a short code, the user approves it in a browser
/// anywhere, and the app polls. That means the interesting states here are not "loading" and
/// "done" but "here is your code, go and use it, I am still waiting" — so the code stays on
/// screen the whole time rather than being replaced by a spinner.
///
/// Two of the four providers cannot be signed into here at all, and the screen says so in a
/// sentence instead of offering a control that starts something which cannot finish.
struct AddAccountSheet: View {

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var store: UsageStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    /// Where the sheet is in the flow. One value rather than several booleans, so "waiting" and
    /// "failed" cannot both be true and leave the screen showing a contradiction.
    private enum Stage: Equatable {
        case choosing
        case starting(ProviderID)
        case waiting(ProviderID, DeviceLoginChallenge)
        /// No flow to drive: the user has to fetch a key and paste it.
        case awaitingKey(ProviderID)
        case failed(ProviderID, String)
    }

    @State private var stage: Stage = .choosing
    @State private var login: Task<Void, Never>?
    @State private var loopback = LoopbackSignIn()
    @State private var copied = false
    @State private var pastedKey = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    switch stage {
                    case .choosing:
                        chooser
                    case .starting(let provider):
                        starting(provider)
                    case .waiting(let provider, let challenge):
                        waiting(provider, challenge)
                    case .awaitingKey(let provider):
                        awaitingKey(provider)
                    case .failed(let provider, let message):
                        failure(provider, message)
                    }
                }
                .id(stageIdentity)
                .transition(AnyTransition.opacity)
                .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.18), value: stage)
                .padding(16)
                .readableWidth()
            }
            .accessibilityIdentifier("provider-picker-scroll")
            .background(UsageColors.background)
            .navigationTitle("Add account")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        // The poll is a long-running task the user has just abandoned. Left
                        // running it would keep talking to the provider about a sign-in nobody
                        // is going to approve.
                        login?.cancel()
                        dismiss()
                    }
                }
            }
        }
        .onDisappear { login?.cancel() }
    }

    private var stageIdentity: Int {
        switch stage {
        case .choosing: return 0
        case .starting: return 1
        case .waiting: return 2
        case .awaitingKey: return 3
        case .failed: return 4
        }
    }

    // MARK: - Choosing

    private var chooser: some View {
        ForEach(ProviderID.allCases, id: \.self) { provider in
            Button { start(provider) } label: {
                UsageCard {
                    ProviderBadge(provider: provider)
                    Text(provider.displayName)
                        .font(.headline)
                        .foregroundStyle(UsageColors.terracotta)
                    // Named before the tap, not after: a user should know what the button is
                    // about to do with their account before it does it. The two flows feel
                    // different enough that saying which one is coming is worth a line.
                    Text(signInHint(for: provider))
                        .font(.footnote)
                        .foregroundStyle(UsageColors.textSecondary)
                }
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - In progress

    private func starting(_ provider: ProviderID) -> some View {
        UsageCard {
            Text(provider.displayName)
                .font(.headline)
                .foregroundStyle(UsageColors.textPrimary)
            HStack(spacing: 8) {
                if reduceMotion { Text("...") } else { ProgressView() }
                Text("Asking \(provider.displayName) for a code…")
                    .font(.footnote)
                    .foregroundStyle(UsageColors.textSecondary)
            }
        }
    }

    private func waiting(_ provider: ProviderID, _ challenge: DeviceLoginChallenge) -> some View {
        UsageCard {
            Text("Enter this code")
                .font(.headline)
                .foregroundStyle(UsageColors.textPrimary)

            // Monospaced digits so the code does not reflow while it is being read aloud or
            // copied character by character, and selectable because typing it on another device
            // is the whole point.
            Text(verbatim: challenge.userCode)
                .font(.system(.largeTitle, design: .monospaced).weight(.semibold))
                .foregroundStyle(UsageColors.terracotta)
                .textSelection(.enabled)
                .accessibilityLabel(spelledOut(challenge.userCode))

            Text(verbatim: challenge.verificationURI)
                .font(.footnote)
                .foregroundStyle(UsageColors.textSecondary)

            HStack(spacing: 10) {
                Button(copied ? "Copied" : "Copy code") {
                    UIPasteboard.general.string = challenge.userCode
                    copied = true
                }
                .buttonStyle(.borderedProminent)
                .tint(UsageColors.terracotta)

                Button("Open sign-in page") {
                    // The pre-filled page when the provider offers one, so the code does not have
                    // to be typed at all. Both URLs came from the provider and were checked
                    // against its own host before this screen vouched for either.
                    let target = challenge.verificationURIComplete ?? challenge.verificationURI
                    if let url = URL(string: target) { openURL(url) }
                }
                .buttonStyle(.bordered)
                .tint(UsageColors.terracotta)
            }

            HStack(spacing: 8) {
                if reduceMotion { Text("...") } else { ProgressView() }
                Text("Waiting for you to approve it…")
                    .font(.footnote)
                    .foregroundStyle(UsageColors.textSecondary)
            }

            Text("The browser opens \(provider.displayName)'s own sign-in page. This app never sees your password.")
                .font(.caption)
                .foregroundStyle(UsageColors.textTertiary)

            if DeviceLoginSupport.acceptsPastedKey(provider) {
                // The flow is the default because it costs the user nothing; the key is for
                // when the flow's account cannot reach the API yet.
                Button("Use an API key instead") {
                    login?.cancel()
                    stage = .awaitingKey(provider)
                }
                .buttonStyle(.bordered)
                .tint(UsageColors.terracotta)
            }
        }
        // On the clipboard before either button is pressed: the browser is opened for the user,
        // so the next thing they do is paste, and a code they have to go back and copy first is
        // a code they will type by hand.
        .onAppear {
            UIPasteboard.general.string = challenge.userCode
            copied = true
        }
    }

    /// The sign-in with nothing to drive: a key from the provider's console.
    ///
    /// Kimi Code's second way in, under its device flow, for the time before Moonshot has
    /// allowlisted this app's name on the coding API. See docs/providers-kimi.md.
    private func awaitingKey(_ provider: ProviderID) -> some View {
        UsageCard {
            Text("Paste your \(provider.displayName) key")
                .font(.headline)
                .foregroundStyle(UsageColors.textPrimary)

            Text("Create a key in your \(provider.displayName) console and paste it here. "
                 + "It is stored on this device only, in the keychain with every other account.")
                .font(.footnote)
                .foregroundStyle(UsageColors.textSecondary)

            TextField("API key", text: $pastedKey)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)

            HStack(spacing: 10) {
                Button("Connect") { submitKey(provider) }
                    .buttonStyle(.borderedProminent)
                    .tint(UsageColors.terracotta)
                    .disabled(pastedKey.trimmingCharacters(in: .whitespaces).isEmpty)

                Button("Open console") {
                    if let url = URL(string: ProviderEndpoints.Kimi.consoleURL) { openURL(url) }
                }
                .buttonStyle(.bordered)
                .tint(UsageColors.terracotta)
            }
        }
    }

    private func failure(_ provider: ProviderID, _ message: String) -> some View {
        UsageCard {
            Text("Could not add \(provider.displayName)")
                .font(.headline)
                .foregroundStyle(UsageColors.textPrimary)
            Text(verbatim: message)
                .font(.footnote)
                .foregroundStyle(UsageColors.textSecondary)
            HStack(spacing: 10) {
                Button("Try again") { start(provider) }
                    .buttonStyle(.bordered)
                    .tint(UsageColors.terracotta)
                if DeviceLoginSupport.acceptsPastedKey(provider) {
                    Button("Use an API key") {
                        login?.cancel()
                        stage = .awaitingKey(provider)
                    }
                    .buttonStyle(.bordered)
                    .tint(UsageColors.terracotta)
                }
            }
        }
    }

    /// Reads a code out character by character, so VoiceOver does not pronounce "ABCD" as a word
    /// or run two groups together.
    private func spelledOut(_ code: String) -> String {
        code.map(String.init).joined(separator: " ")
    }

    // MARK: - Driving the flow

    /// Three styles now, so a binary check would call the pasted-key row a browser sign-in.
    private func signInHint(for provider: ProviderID) -> String {
        switch DeviceLoginSupport.style(for: provider) {
        case .deviceCode: return "Shows a code to approve in your browser."
        case .loopbackRedirect: return "Opens the provider's sign-in page."
        case .pastedKey: return "Needs a key from the provider's console."
        }
    }

    private func submitKey(_ provider: ProviderID) {
        let key = pastedKey
        login?.cancel()
        stage = .starting(provider)

        login = Task {
            guard let container = UsageStore.sharedContainer else {
                stage = .failed(provider, "This build cannot reach its shared storage.")
                return
            }
            do {
                _ = try await container.completePastedKeyLogin(provider: provider, key: key)
                await store.load()
                await store.refresh()
                dismiss()
            } catch is CancellationError {
                // The sheet was closed.
            } catch {
                stage = .failed(provider, error.localizedDescription)
            }
        }
    }

    private func start(_ provider: ProviderID) {
        login?.cancel()
        stage = .starting(provider)

        login = Task {
            guard let container = UsageStore.sharedContainer else {
                stage = .failed(provider, "This build cannot reach its shared storage.")
                return
            }
            do {
                switch DeviceLoginSupport.style(for: provider) {
                case .deviceCode:
                    let challenge = try await container.beginLogin(provider: provider)
                    stage = .waiting(provider, challenge)
                    // Blocks until the user approves, the code expires, or the provider refuses.
                    _ = try await container.completeLogin(
                        provider: provider, challenge: challenge)

                case .pastedKey:
                    // Parks here. The key arrives through `submitKey`, which runs its own
                    // task, so this one simply ends rather than holding the sheet open.
                    stage = .awaitingKey(provider)
                    return

                case .loopbackRedirect:
                    // The listener is bound INSIDE `authorize`, before the browser opens: a
                    // provider that redirects promptly would otherwise find nothing listening.
                    let challenge = try await container.beginLoopbackLogin(provider: provider)
                    do {
                        let code = try await loopback.authorize(challenge)
                        _ = try await container.completeLoopbackLogin(
                            code: code, challenge: challenge)
                    } catch LoopbackListener.ListenError.portUnavailable where provider == .codex {
                        try Task.checkCancellation()
                        // The CLI's port is taken, and Codex alone has a second way in: its
                        // device flow needs no port at all, at the price of a code to carry
                        // into the browser — the step the browser flow exists to remove.
                        let fallback = try await container.beginLogin(provider: provider)
                        stage = .waiting(provider, fallback)
                        _ = try await container.completeLogin(
                            provider: provider, challenge: fallback)
                    }
                }

                // Straight into a refresh: an account that appears with no numbers looks like it
                // failed, when in fact nothing has asked yet.
                await store.load()
                await store.refresh()
                dismiss()
            } catch is CancellationError {
                // Dismissing only the browser leaves this sheet open. A cancelled
                // task belongs to a closed sheet or an older attempt instead.
                if !Task.isCancelled { stage = .choosing }
            } catch {
                stage = .failed(provider, error.localizedDescription)
            }
        }
    }
}
