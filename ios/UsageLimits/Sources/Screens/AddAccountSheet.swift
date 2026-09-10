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

    @EnvironmentObject private var store: UsageStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    /// Where the sheet is in the flow. One value rather than several booleans, so "waiting" and
    /// "failed" cannot both be true and leave the screen showing a contradiction.
    private enum Stage: Equatable {
        case choosing
        case starting(ProviderID)
        case waiting(ProviderID, DeviceLoginChallenge)
        case failed(ProviderID, String)
    }

    @State private var stage: Stage = .choosing
    @State private var login: Task<Void, Never>?
    @State private var loopback = LoopbackSignIn()
    @State private var copied = false

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
                    case .failed(let provider, let message):
                        failure(provider, message)
                    }
                }
                .padding(16)
            }
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

    // MARK: - Choosing

    private var chooser: some View {
        ForEach(ProviderID.allCases, id: \.self) { provider in
            Button { start(provider) } label: {
                UsageCard {
                    Text(provider.displayName)
                        .font(.headline)
                        .foregroundStyle(UsageColors.terracotta)
                    // Named before the tap, not after: a user should know what the button is
                    // about to do with their account before it does it. The two flows feel
                    // different enough that saying which one is coming is worth a line.
                    Text(DeviceLoginSupport.style(for: provider) == .deviceCode
                         ? "Shows a code to approve in your browser."
                         : "Opens the provider's sign-in page.")
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
                ProgressView()
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
                ProgressView()
                Text("Waiting for you to approve it…")
                    .font(.footnote)
                    .foregroundStyle(UsageColors.textSecondary)
            }

            Text("The browser opens \(provider.displayName)'s own sign-in page. This app never sees your password.")
                .font(.caption)
                .foregroundStyle(UsageColors.textTertiary)
        }
        // On the clipboard before either button is pressed: the browser is opened for the user,
        // so the next thing they do is paste, and a code they have to go back and copy first is
        // a code they will type by hand.
        .onAppear {
            UIPasteboard.general.string = challenge.userCode
            copied = true
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
            Button("Try again") { start(provider) }
                .buttonStyle(.bordered)
                .tint(UsageColors.terracotta)
        }
    }

    /// Reads a code out character by character, so VoiceOver does not pronounce "ABCD" as a word
    /// or run two groups together.
    private func spelledOut(_ code: String) -> String {
        code.map(String.init).joined(separator: " ")
    }

    // MARK: - Driving the flow

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

                case .loopbackRedirect:
                    // The listener is bound INSIDE `authorize`, before the browser opens: a
                    // provider that redirects promptly would otherwise find nothing listening.
                    let challenge = try await container.beginLoopbackLogin(provider: provider)
                    let code = try await loopback.authorize(challenge)
                    _ = try await container.completeLoopbackLogin(
                        code: code, challenge: challenge)
                }

                // Straight into a refresh: an account that appears with no numbers looks like it
                // failed, when in fact nothing has asked yet.
                await store.load()
                await store.refresh()
                dismiss()
            } catch is CancellationError {
                // The sheet was closed. Not a failure, and nothing left to show it on.
            } catch {
                stage = .failed(provider, error.localizedDescription)
            }
        }
    }
}
