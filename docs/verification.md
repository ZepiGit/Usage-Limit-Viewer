# Verification

Usage Limits reads quota for five providers: OpenAI Codex, Anthropic Claude,
Google Antigravity, xAI Grok and Kimi Code. The main correctness risk is a
plausible but wrong percentage or reset time. Tests need to check what a number
means, which account it belongs to, and how old it is.

## Follow-up corrections — 13 September 2026

Source baseline: main at `ba6d2bf3d9ff1eb96418a9f5576e3c67c8cc2db8`, with the local
PAK-1 through PAK-4 corrections below. No commit, push, CI dispatch or release was made.
The pre-existing untracked `.mimosa/` directory was not modified by this work.

| Package | Implemented behavior and evidence |
|---|---|
| PAK-1 / ULV-001 | Both Codex parsers retain declared unknown durations as OTHER / "Limit", preserving percentages, resets and exhaustion. Known and legacy assignments retain priority; remaining unknowns use free long then short slots. Each family has two inputs, so no third-window product decision is needed. |
| PAK-2 / ULV-002 | Swift checks cancellation before publishing an error and before starting reactive renewal after a rejection. An already-started exchange still runs through persistence; completed successes still reach the sink. |
| PAK-3 / ULV-003 | Device-poll and xAI tests require the correct error variants; the sync sink test checks account identities and outcome kinds, not just the number of calls. |
| PAK-4 / ULV-005 | Kotlin and Swift reconnect inference reference the evaluator-owned current message. The old persisted wording remains an explicit compatibility fallback. |
| PAK-5 / ULV-004 | Not changed: choosing 5h versus OTHER/null when Kimi omits duration still requires the owner's product decision. |

**Corrections to the supplied analysis.** The ISO timestamp 2026-09-09T18:00:00Z is
1788976800000 epoch milliseconds, not 1788640800000. A known 60% remaining is HEALTHY,
not UNKNOWN merely because its duration maps to OTHER. Both device-poll implementations
map `invalid_client` to `ProviderException.Unexpected`, not LoginCancelled. The existing
xAI double-failure fixture uses HTTP 503; its public provider error is `ProviderError.noData`,
not the lower-level HTTPError.status. Tests preserve those existing production contracts.

**Red/green proof.** New Codex assertions were executed against an archived baseline with
only the test files overlaid: three test cases failed on each platform (one unknown window,
two unknown windows, and mixed known/legacy precedence). With the parser fixes, all six
Android position-fallback tests and all 23 Swift Codex tests passed. This was an isolated
baseline replay, not an assertion that production edits waited for the first red execution.
The two Swift cancellation tests use a gated synthetic HTTPTransport through the actual
ClaudeClient and UsageHTTPClient with maxRetries zero. On baseline, URLError(.cancelled)
produced one persisted failure; a 401 delivered after cancellation started one token exchange
and changed the stored pair. Both cases still surfaced CancellationError to the caller:
that fact alone did not protect the sink or credentials. With the guards, both tests pass
with zero recorded outcomes, zero exchanges and unchanged credentials.

**Assertion sensitivity.** In the disposable baseline copy, changing the Kimi terminal-error
variant from Unexpected to LoginCancelled failed the new type assertion; changing the xAI
mapped variant from noData to malformedPayload failed its case assertion; changing the
failure's account ID to "wrong-account" failed the sink identity assertions while the
record count remained two. Mutations were restored; the final suites passed.

**Executed final checks:**

- Android: **415 tests, zero failures/errors/skips**, JDK 21.0.12.1, Android SDK 36.
  The initial full run had one ConnectException in the unchanged silent-peer case of
  LoopbackServerNoiseTest. The baseline class run then passed, as did the complete final
  rerun. No root cause was established and no timeout or loopback code was changed.
- Swift package: **468 tests, zero failures**, Swift 6.0.3 in the existing local Docker image,
  with networking disabled, source mounted read-only and a separate scratch build directory.
- A throwaway executable linked against the built Swift package passed direct checks for two
  retained unknown windows, exhausted daily quota, preserved reset and reconnect inference.
- Read-only Codex and Swift sync/security reviews found no blocking issues.

The successful Android run invoked the wrapper JAR directly because this harness could not
launch the batch wrapper correctly (same Gradle tasks, no project configuration change):

```text
C:/temp/ulv-jdk21/jdk-21.0.12.1+1/bin/java.exe -Xmx64m -Xms64m -jar <repo>/gradle/wrapper/gradle-wrapper.jar :app:testDebugUnitTest --rerun-tasks --console=plain
```

JAVA_HOME pointed to that JDK; ANDROID_HOME was
`C:/Users/miche/AppData/Local/ElWeatherTools/android-sdk`.
The equivalent normal invocation is `gradlew.bat :app:testDebugUnitTest --rerun-tasks`.
Swift ran `swift test --package-path ios/UsageLimitsKit --scratch-path /build`
inside `swift:6.0.3` with `--network=none`.
Local logs are retained under `C:/temp/ulv-followup-20260913` (android-red,
android-codex-green, android-pak3-mutant, android-final, android-final-rerun,
swift-red, swift-cancellation-red/green, swift-pak3-mutant, swift-sink-mutant/restored,
swift-final and smoke logs). Temporary baseline sources and scratch builds were removed.

**Limits:** Gradle reported corruption in the existing user-cache journal file-access.bin,
including on successful runs. No shared cache was deleted or repaired. The intermittent
loopback refusal remains an observation, not a confirmed regression or resolved defect.
No APK packaging, emulator/device UI, Xcode build, Apple URLSession cancellation behavior,
provider login/refresh, credit redemption or store publication was verified in this follow-up.
The scripted HTTP tests do not establish what error an Apple URLSession emits on a device.

## Local audit results — 12 September 2026

The completed baseline checks used JDK 17, Android SDK 35 and Swift 6.0.3 in
Docker. The current Android source targets API 36 with AGP 8.10.1 and Robolectric
4.16.1; its tests use JDK 21, while app bytecode remains Java 17. The final local
build, test and emulator results are below. The corrected iPhone/iPad CI run
remains pending.

| Check | Result |
|---|---|
| Android baseline packaging | Debug APK: 13,702,817 bytes; unsigned release APK: 2,491,175 bytes |
| Final Android API 36 suite | 400 tests passed; no failures or skips |
| Final Android packaging | Debug APK: 13,737,666 bytes; debug-signed release APK: 2,481,299 bytes |
| Final APK SDK metadata | `aapt` confirmed minimum 26, target 36 and compile 36 |
| Final Swift package suite | 456 tests passed |
| iOS app and widget sources, Linux framework shims | Final source type-check passed |
| Android API 35 emulator smoke | Install, launch, empty refresh, all four tabs, compact widget and ring widget exercised |
| Final minified APK on Android API 36 | Empty refresh, all four tabs, compact-widget configuration/placement/open, narrow and wide layouts, large text and reduced-motion navigation passed; crash buffer empty |
| Physical Android device | Not verified |
| Real iOS SDK build and simulator smoke | Compilation completed at `19fc063`; three scroll-gesture UI checks failed. Corrected iPhone/iPad results tracked in PR #9 |
| Final API 36 runtime samples | Five COLD launches: 621, 612, 632, 627 and 650 ms; median 627 ms. Idle PSS: 25,997 kB; RSS: 154,040 kB |
| Baseline API 35 runtime samples | Launch: 965, 921, 950, 952 and 973 ms, mixed COLD/WARM; one PSS sample: 27,541 kB |
| Live login, token refresh or reset-credit redemption | Not attempted in this audit |
| Distribution signing and store publication | Not performed |

The baseline APKs used AGP 8.7.3 and SDK 35; the final APKs use AGP 8.10.1 and SDK
36. The final release is debug-signed, while the baseline release was unsigned.
These are package-size measurements; signing and toolchain differences prevent
attributing the change to application code or performance improvements. See
[release.md](release.md) for signing rules and
[release-readiness.md](release-readiness.md) for regression evidence.

The emulator exposed unreadable system-bar icons and overlapping widget
configuration content; explicit dark system-bar styling and safe drawing insets
were added. Before/after screenshots confirm the status-icon correction and the
widget title moving from y=42 to y=170, below the 128-pixel status bar.

The final run used the API 36 AOSP x86_64 emulator `emulator-5582` with WHPX and
the 2,481,299-byte minified APK. At 320 dp width and font scales 1.3 and 2.0,
sync-interval choices wrapped and the 3 h option remained visible and selectable.
Landscape, tablet and unfolded-size windows used the side rail; scrolling the
landscape provider picker reached Kimi. Compact-widget configuration, placement
and opening the app passed. Add-account navigation and Back also worked with
animator scale zero. Emulator settings were restored after the checks. At font
scale 2.0, some bottom-tab labels break mid-word; their touch targets remain
accessible. This is a remaining cosmetic issue.

Each final launch had no prior app PID and Android reported `LaunchState: COLD`.
The times above are `am start -W` TotalTime values; memory is one idle
`dumpsys meminfo` sample. The baseline used a different Android version and a
mixture of cold and warm launches. These observations do not establish a speed
or memory improvement. Local logs, screenshots and UI dumps are retained under
`C:/temp/ulv-audit`, including `device/api36-final-startup.txt`,
`device/api36-final-meminfo.txt` and `device/api36-final-crash.txt`.

The Linux shims catch missing declarations and some type and concurrency errors.
They do not reproduce the Apple SDK's full behavior, render the SwiftUI screens,
or prove an Xcode build. The separate real-SDK CI run compiled the source at
`19fc063`, then failed three provider-picker UI checks involving scroll gestures.
The corrected iPhone/iPad run, including landscape and large text, is still pending.

## What the tests establish

**Synthetic fixtures** record intended behavior and reproduce known failures.
They are useful regression tests, but a parser and fixture written from the same
assumption can agree and both be wrong.

**Production-shaped and upstream-derived fixtures** preserve field names, types,
nulls and nesting while using synthetic values. They cover cases such as Codex
weekly windows in the primary slot, numeric reset timestamps, integer
percentages, Claude's scoped windows and xAI's nested billing fields. Their
provenance matters: an upstream type and a captured account response are
different evidence. Neither re-confirms the provider's current live response.

**Integration and regression tests** exercise account isolation, token storage,
refresh and removal races, notifications, widget snapshots, OAuth parsing and
polling. Android includes real local socket tests for loopback cancellation and
timeouts, plus Robolectric storage and Compose screen tests. Those checks can
catch a regression without contacting a provider; they do not prove the browser
round trip on a phone.

Code review complements those tests by tracing the whole displayed result. A
correctly parsed value can still be paired with another account's reset, lose its
stale marker, or disagree with a widget. Findings should have a source trace and,
where practical, a demonstrating test before being treated as confirmed.

## Device runbook

Run the account-free steps first on both a debug and a minified Android build.
Record the commit, artifact, device, OS version and whether each check passed.
Repeat the relevant checks on an iOS build made with the real SDK.

### Before signing in

1. **Install and open.** A fresh installation reaches Overview with no accounts
   and no crash. Record cold-start time and memory use on the test device if
   reporting performance; include the measurement method.
2. **Open every tab.** Overview, Accounts, Resets and Settings render their empty
   states. Check large text, system appearance changes and reduced-motion
   settings. The iOS app currently requests dark appearance. Add both widget
   sizes and confirm their empty state too.

### Sign-in, with the account holder

Live sign-in and credential checks require the account holder's participation.
Use a dedicated test account where available. Do not reuse credentials from a
working gateway: a provider may rotate its refresh token, and a failed persistence
step can leave that credential unusable. Kimi accounts connected by a pasted key
have no refresh grant.

3. **Codex:** the default is browser authorization with PKCE and a loopback
   callback on port 1455. The device-code flow is the fallback when the listener
   cannot bind. Verify the normal flow and fallback separately. Approval that
   succeeds in the browser but leaves the app waiting is a failure.
4. **Claude and Antigravity:** browser authorization returns to loopback ports
   54545 and 51121 respectively. Verify completion, browser cancellation and
   retry. A closed sheet without an account, an indefinite wait, or a port left
   occupied after cancellation is a failure.
5. **Grok and Kimi:** the app shows a device code and polls after browser
   approval. Check cancellation, expiry and denial as well as success. Kimi also
   offers a pasted key from the user's console; test it separately. Kimi OAuth
   access may remain blocked until the vendor permits the app's `UsageLimits`
   identity. Record that outcome without substituting another client's identity.
6. **Add two accounts on one provider.** They must remain separate, with the
   correct account label and windows. Reconnecting an existing account must not
   create a duplicate or overwrite a different account.

### Quota, resets and widgets

7. **Compare against the provider's own UI.** Check each account and each
   reported window, including the distinction between used and remaining quota.
   An unsupported or missing value must not become a confident zero.
8. **Check reset times now and later.** Compare with the wall clock and the
   provider UI. Return after a reset or after an hour: a frozen countdown or a
   past reset still shown as upcoming is a failure.
9. **Compare both widgets with the app.** Confirm account, percentage, severity,
   reset time and freshness agree. Refresh, background the app and reopen it.
   Remove an account during refresh and check that it stays removed everywhere.
10. **Check failures.** Disable the network and refresh. Previously fetched data
    should retain its age and show the failure; one failed account must not stop
    others. Restore the network and verify recovery. Check that notifications
    are not duplicated on every refresh or removed just because nothing changed.

### Credential renewal and credit redemption

11. **Observe OAuth renewal.** Leave the app installed until a token has expired,
    then refresh and reopen the app. Do not assume every provider expires or
    rotates tokens at the same interval. An account that works only until app
    restart suggests the replacement credential was not persisted. Resolve that
    before proceeding with any credit operation.
12. **Reset-credit redemption is optional and spends a real credit.** The account
    holder must deliberately approve it on a Codex account with an applicable
    credit. First confirm that the action is unavailable when
    `applicable_available_count` is zero. If redeemed, verify the result against
    the provider and confirm a single action did not produce repeated requests.

### If a check fails

Record the expected value, actual value, timestamps, account label without a
private identifier, and build version. When a payload is needed, retain a
sanitized copy of its shape and relevant values. Remove tokens, keys, cookies and
personal identifiers before sharing or committing evidence. Avoid repeated live
authentication attempts while the cause is unknown.

## Remaining release decisions

This audit has not established end-to-end access to any live account. Historical
fixtures remain useful regression evidence, but no current provider response was
captured or re-confirmed here. Physical-device checks, the corrected iOS CI run,
populated-account behavior and a controlled performance comparison remain open.
The final API 36 emulator smoke and cold-launch measurements are complete.
Both platforms have the shared app-icon assets configured; their presence does
not establish store acceptance.

Vendor permission and terms review, Kimi allowlisting, signing-key custody, legal
and store copy, and publication require their respective owners. Ordinary HTTPS
rejection is a compatibility finding to resolve with the provider; the runbook
does not include TLS fingerprint mimicry or another client's identity as a
workaround. Passing automated checks does not settle any of these release
decisions.
