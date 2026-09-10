# iOS

The iOS client shares no code with the Android app, but it shares every decision the Android
app paid for — the normalised model, classifying Codex windows by declared duration rather than
payload position, the `iguana_necktie` supersession, the xAI `config` envelope. Those were
found by reading upstream and by four separate defects in production-shaped payloads; they are
ported, not re-derived. `docs/providers-*.md` is the reference.

## The split, and why

```
ios/
  UsageLimitsKit/     Foundation only. Models, parsers, providers, HTTP.
                      Builds and tests on Linux — no Mac required.
  UsageLimits/        SwiftUI app + WidgetKit extension. Keychain,
                      ASWebAuthenticationSession. Requires the iOS SDK.
```

`UsageLimitsKit` imports `Foundation` and nothing else. That is a hard rule, enforced by the
fact that CI builds it in a `swift:6.0.3` Linux container where SwiftUI and Security simply do
not exist — an accidental `import UIKit` fails the build rather than passing unnoticed until
someone opens Xcode.

The boundary is not arbitrary. On Android, every real defect found in this project was in a
parser: an inverted percentage, a missing `config` envelope, windows read by position instead
of declared duration, a present-but-empty field marking a whole account as failed. None was in
a view. So the half that can be verified without Apple hardware is also the half where the bugs
live, and putting the engine on the Linux-testable side of the line is what makes that
verification possible at all.

## Platform mapping

| Android | iOS | Note |
|---|---|---|
| Android Keystore + AES-GCM | Keychain, `kSecAttrAccessibleAfterFirstUnlock` | Background refresh must read the token while the device is locked, so `WhenUnlocked` is not usable. |
| Custom Tabs | `ASWebAuthenticationSession` | **Removes the loopback server entirely.** It supports a custom-scheme callback, so the fixed ports 54545 and 51121 — and the whole class of "another app squatted the port" failures — do not exist on iOS. |
| WorkManager | `BGAppRefreshTask` | iOS gives materially weaker scheduling guarantees. Staleness detection matters more here, not less. |
| Glance + Room | WidgetKit + App Group container | The widget still reads only the normalised cache, never a credential. |
| Room | SQLite via an App Group, or `Codable` snapshots | Whichever is chosen, tokens stay in the Keychain and out of it. |

## Building

The engine, anywhere including Linux:

```bash
cd ios/UsageLimitsKit
swift build
swift test
```

The app requires macOS with Xcode. `.github/workflows/ios.yml` runs the engine on both Linux
and macOS, and the app itself on a macOS runner against the real SDK — so "the iOS app
compiles" is a claim CI makes on Apple hardware, not one asserted from a Linux container.

## Status

The engine is real and tested. The app target is not yet written; the CI job for it is guarded
on the Xcode project existing, so it stays green rather than reporting a false failure until
there is something to build.
