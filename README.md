# Usage Limits

Usage Limits is an Android and iOS app that shows, at a glance, how much of each AI subscription you
have left and when each limit rolls over — your Codex five-hour window, your Claude weekly
window, your Antigravity model buckets, Grok credits and Kimi Code quota, side by side on one screen and
on the home screen. It talks directly from your phone to each provider's own usage endpoint
with your own OAuth login or, for Kimi, a key from your console. There is no app-owned account
or server; credentials and cached usage stay on the device and requests go to the providers.

## What it does

Four screens and two widgets, all reading the same local cache:

- **Overview** leads with the single window closest to running out across every account, then
  one card per account.
- **Accounts** lists the connected accounts; tapping one opens its detail, where every window
  the provider reports is broken out, and where a Codex reset credit can be spent.
- **Resets** is a chronological timeline of every upcoming rollover.
- **Settings** controls the sync interval and which notifications fire.

A background pass refreshes every account on a schedule (30 minutes by default, 15 minutes
minimum — WorkManager's floor for periodic work), and also on app start, on resume, and when
the widget's refresh button is tapped. The refresh button, finishing a login and spending a reset
credit refresh in the foreground immediately. Accounts refresh independently, so one expired
token cannot stop the others, and when a refresh fails the previous numbers stay on screen with
their real age rather than being blanked.

## Non-goals

This is a meter, not a gateway. The app never sends an inference request, and there is no code
path by which it could: `UsageProvider` — the interface every provider implements — has
methods for logging in, refreshing a token, reading identity, reading quota and spending a
Codex reset credit, and nothing else.

Specifically, and permanently:

- **No proxy and no API gateway.** The app does not accept requests from other programs, does
  not listen on a network port (the only socket it ever opens is a loopback listener bound to
  127.0.0.1 for the duration of one OAuth redirect), and exposes no OpenAI-compatible endpoint.
- **No model calls.** No completions, no chat, no embeddings, no streaming — nothing that
  consumes the quota it is reporting on.
- **No routing or model translation.** It does not pick a provider for you, does not rewrite
  one provider's request format into another's, and does not fail over between accounts.
- **No app-owned server.** There is no backend, no telemetry endpoint and no analytics SDK.
  The only hosts the app contacts are the providers' own, listed in `ProviderEndpoints`.

The reference implementations this project read its provider knowledge from (CLIProxyAPI and
its Management Center) *are* proxies. This app deliberately took only the authentication and
quota-reading halves of that work.

## Supported providers

| Provider | Login | What is read |
|---|---|---|
| **OpenAI Codex** (ChatGPT subscription) | Authorization code + PKCE in the system browser, returning to the Codex CLI's loopback redirect on port 1455; OpenAI's device-code flow steps in only if that port is taken | Five-hour, weekly and (on some plans) monthly windows, code-review limits, and rate-limit reset credits, which can also be spent from the app |
| **Claude** (Anthropic subscription) | Authorization code + PKCE in the system browser, returning to a loopback redirect on port 54545 | Five-hour window plus the weekly windows Anthropic reports, including per-model ones |
| **Antigravity** (Google) | Google installed-app authorization code + PKCE, loopback redirect on port 51121 | The already-grouped quota buckets Google returns per model family, five-hour and weekly |
| **Grok** (xAI) | RFC 8628 device flow, with the endpoints resolved from xAI's OIDC discovery document and validated to be x.ai hosts | Weekly credit usage and the monthly billing window |
| **Kimi Code** (Moonshot) | Device-code flow under the app's `UsageLimits` identity, or a pasted key from the user's Kimi Code console | Coding quota and reset windows |

All five have implementations on both platforms; live account access remains unverified in
this audit. Codex uses browser authorization by default and device authorization if its
loopback listener cannot bind. Claude and Antigravity also use fixed loopback callbacks.
Grok and Kimi use device codes, and Kimi provides a pasted-key option. Kimi OAuth usage access
may require vendor approval of the `UsageLimits` identity. See [docs/security.md](docs/security.md)
and the per-provider notes for the callback checks and compatibility limits.

## Project status

**The final local run passed 400 Android tests with no failures or skips, and 456 Swift
package tests.** The API 36 debug and minified release APKs built successfully, and the iOS
app and widget sources passed the Linux framework-shim type check. The final minified APK
passed an API 36 emulator smoke covering empty refresh, all four tabs, compact-widget
configuration and placement, narrow and wide layouts, large text and reduced motion. Both
widget sizes were also exercised on the earlier API 35 build. Real iOS SDK compilation
completed. Simulator scroll-test failures were reproduced and the gesture handling corrected;
[PR #9](https://github.com/ZepiGit/Usage-Limit-Viewer/pull/9) records the current iPhone/iPad CI results.

Historical production-shaped and upstream-derived fixtures test parser compatibility without
live credentials. No login, live token refresh or reset-credit redemption was attempted in
this audit. The remaining checks are in [docs/verification.md](docs/verification.md).

The workflows are configured for the following checks; an observed run is needed to claim a pass:

| | Android | iOS |
|---|---|---|
| Unit tests | JVM and Robolectric | Swift package on Linux and macOS |
| Screens and launch | Compose screens and Application under Robolectric | App on an iOS simulator |
| Packaging | Debug and release APKs; both verification APKs uploaded | Real SDK app and widget build |
| Release workflow | Signed AAB and APK when release secrets are supplied | Unsigned archive |

Five API 36 cold launches had a median of 627 ms. These emulator samples do not establish a
speed improvement over the earlier API 35 run. Physical-device checks, the final iOS CI run,
live account approval, vendor permission, distribution signing and store publication remain open. Both
platforms now use the shared quota-cell and return-arrow app icon, including Android adaptive
and monochrome variants and the configured iOS AppIcon catalog. See
[docs/release-readiness.md](docs/release-readiness.md) for measured results and remaining checks.

## Build

Requirements:

- **JDK 21 for tests.** Robolectric 4.16.1's API 36 test runtime needs it. Packaging alone uses
  JDK 17 in CI, and Kotlin and Java still target Java 17 bytecode.
- **An Android SDK containing platform 36 and Build Tools 35 or later**, located either through
  `ANDROID_HOME` or through `sdk.dir` in `local.properties`.
- No global Gradle install — the wrapper pins Gradle 8.11.1 and downloads it on first run.

```powershell
$env:ANDROID_HOME = 'C:\path\to\android-sdk'
.\gradlew.bat :app:assembleDebug
```

On Linux or macOS, set `ANDROID_HOME` for that shell and use `./gradlew`.

Key versions: `minSdk` 26 (Android 8.0), `compileSdk` and `targetSdk` 36, AGP 8.10.1, Kotlin
2.0.21, Compose BOM 2024.12.01, Room 2.6.1 with KSP, Glance 1.1.1. `docs/compatibility.md`
explains why the floor is 26 and what it would take to go lower.

The debug variant installs as `com.usagelimits.debug`, so it can sit alongside a release
build. With no signing settings, `assembleRelease` uses the debug key for local verification.
Supplying a complete signing configuration enables real release signing; a partial one fails.
R8 rules for serialization and crash locations are present. See [docs/release.md](docs/release.md)
for the setting names and the device smoke test required before distribution.

## Tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

The suite combines plain JVM parser and model tests, local HTTP and socket tests, and
Robolectric tests for Android storage, application startup and Compose screens. It uses
synthetic fixtures without live provider credentials and requires no emulator. There is no
instrumented (`androidTest`) source set. Reports are written to
`app/build/reports/tests/testDebugUnitTest/index.html`.

## Documentation

| Document | What it covers |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Layering, the `UsageProvider` abstraction, the normalised model, the sync pipeline, and the two deliberate deviations from convention |
| [docs/verification.md](docs/verification.md) | Local results, test coverage and limits, and the remaining device and account-holder runbook |
| [docs/release-readiness.md](docs/release-readiness.md) | Release audit evidence, artifact sizes, runtime samples and outstanding final checks |
| [docs/security.md](docs/security.md) | Threat model, credential storage, the no-tokens-in list, PKCE and state validation, and a frank accepted-risks section |
| [docs/widgets.md](docs/widgets.md) | The two widget sizes, the configuration model, the Room-only data path, and the Glance constraints that shaped the layout |
| [docs/compatibility.md](docs/compatibility.md) | Android version floor, foldables, window size classes, RTL and accessibility |
| [docs/provider-auth-research.md](docs/provider-auth-research.md) | Phase 0: the feasibility matrix, why each login flow was chosen for Android, and the three corrections reading the current upstream code forced |
| [docs/release.md](docs/release.md) | Building, testing, signing a real release, and what must be reviewed before any public distribution |
| [docs/providers-codex.md](docs/providers-codex.md) · [claude](docs/providers-claude.md) · [antigravity](docs/providers-antigravity.md) · [xai](docs/providers-xai.md) · [kimi](docs/providers-kimi.md) | One document per provider: the auth flow, payload shapes and verification limits |

## Legal and stability

Read this before pointing the app at an account.

**These are not public APIs.** Several of the usage endpoints — `chatgpt.com/backend-api/wham/*`,
`api.anthropic.com/api/oauth/usage`, `cloudcode-pa.googleapis.com/v1internal:*`,
`cli-chat-proxy.grok.com/v1/billing` — are internal endpoints belonging to each vendor's own
CLI or desktop client. They are not documented for third-party use, carry no compatibility
promise, and can change shape or disappear without notice. The OAuth client identifiers the
app sends are likewise the public identifiers those first-party clients ship. Everything is
centralised in `ProviderEndpoints` so an upstream change is a one-file edit, and the parsers
degrade to "one row missing" rather than failing outright — but breakage is expected over
time, not exceptional.

**This app is for accounts you personally control.** It authenticates as you, with your own
consent, and reads only your own quota. It is not a tool for monitoring someone else's
account, and it has no multi-user or administrative mode.

**Permissibility must be reviewed before any public release.** Using a first-party client's
OAuth client id and internal endpoints from a third-party app may conflict with each
provider's terms of service, and the answer is likely to differ per provider. Nothing in this
repository constitutes that review. Personal use of your own accounts is the intended scope
today; distributing the app — to a store, or to other people at all — requires reading each
provider's current terms first and being prepared to drop a provider.

**What the app refuses to do to stay working.** CLIProxyAPI wraps its Anthropic calls in TLS
fingerprint mimicry and strict header ordering to get past bot detection. This app sends a
plain HTTPS request with no TLS trickery. It does send the client identifiers these endpoints
expect — several vary their response by client, or refuse a request with no user agent at all —
and every one of them is visible in a single file, `ProviderEndpoints`. What it will not do is
imitate another client's TLS fingerprint, solve challenges, or otherwise evade bot detection;
if a provider's edge rejects it, the app reports a clear error. That decision is permanent, and
it is the single biggest feasibility risk in the project.
