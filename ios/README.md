# iOS

The iOS client has a SwiftUI app, a WidgetKit extension and a shared Swift package.
It follows the same quota model and provider behavior as Android, with separate
implementations. The per-provider documents in `docs/` describe payload shapes
and compatibility limits.

## Targets

- `UsageLimitsKit` contains models, parsers, HTTP clients, authentication and sync.
  Its portable code builds and tests on Linux; Keychain and Network-framework
  adapters are compiled when those Apple frameworks are available.
- `UsageLimits` contains the app, system-browser presentation and background work.
- `UsageLimitsWidget` reads snapshots through the shared App Group container.
  It is embedded in the app by `UsageLimits/project.yml`.

## Platform mapping

| Android | iOS |
|---|---|
| Android Keystore and AES-GCM | Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` |
| Custom Tabs and loopback listener | `ASWebAuthenticationSession` plus a Network-framework loopback listener |
| WorkManager | `BGAppRefreshTask`, subject to the system's scheduling decisions |
| Glance and local cache | WidgetKit and snapshots in the shared App Group |

Codex, Claude and Antigravity use their registered loopback callbacks. The browser
may intercept the callback, while the listener provides another way to receive
it. Codex also has a device-code fallback when the port cannot be used. Grok and
Kimi use device codes; Kimi additionally accepts a key from the user's console.
These code paths are implemented, but live account sign-in has not been verified
in the current audit.

## Building and checking

For the portable package, from the repository root:

```sh
swift build --package-path ios/UsageLimitsKit
swift test --package-path ios/UsageLimitsKit
```

The app and widget require macOS with Xcode and XcodeGen:

```sh
cd ios/UsageLimits
xcodegen generate
xcodebuild build -scheme UsageLimits -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO
```

The iOS workflow is configured to test the package on Linux and macOS, build the
app with the real SDK, check the embedded widget and run simulator UI tests.
The Linux `ios/Tools/typecheck-app.sh` script provides an additional check with
framework shims; a pass there does not establish an Xcode build or UI behavior.
The final local audit passed 456 package tests and the app/widget shim type check.
Real-SDK, iPhone/iPad, landscape and large-text CI results remain pending.

See [verification.md](../docs/verification.md) for the observed local results and
remaining device checks, and [release.md](../docs/release.md) for unsigned archives,
distribution signing and release requirements.
