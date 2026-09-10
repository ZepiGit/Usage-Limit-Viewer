# Usage Limits

Usage Limits is an Android and iOS app that shows, at a glance, how much of each AI subscription you
have left and when each limit rolls over — your Codex five-hour window, your Claude weekly
window, your Antigravity model buckets and your Grok credits, side by side on one screen and
on the home screen. It talks directly from your phone to each provider's own usage endpoint
with your own OAuth login: there is no account to create, no server in the middle, and
nothing about your usage leaves the device.

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
| **OpenAI Codex** (ChatGPT subscription) | OpenAI's device-code flow — you type a short code at `auth.openai.com/codex/device` while the app polls | Five-hour, weekly and (on some plans) monthly windows, code-review limits, and rate-limit reset credits, which can also be spent from the app |
| **Claude** (Anthropic subscription) | Authorization code + PKCE in the system browser, returning to a loopback redirect on port 54545 | Five-hour window plus the weekly windows Anthropic reports, including per-model ones |
| **Antigravity** (Google) | Google installed-app authorization code + PKCE, loopback redirect on port 51121 | The already-grouped quota buckets Google returns per model family, five-hour and weekly |
| **Grok** (xAI) | RFC 8628 device flow, with the endpoints resolved from xAI's OIDC discovery document and validated to be x.ai hosts | Weekly credit usage and the monthly billing window |

All four work on both platforms. The two device flows are the easier fit for a phone — nothing
has to survive the app being backgrounded and no local port has to be free — while Claude and
Antigravity pin loopback redirect URIs in their client registrations, so for those two the app
binds the exact port they expect and answers one request on it. That is what RFC 8252 §7.3
describes for a native app that cannot register a scheme, and it works on iOS because the sign-in
is presented by `ASWebAuthenticationSession`, which runs in process: the app stays foregrounded
and the socket stays alive. `docs/security.md` explains what the listener refuses and why.

## Project status

**Every provider's usage endpoint has been called with a real account; no login, token refresh
or credit redemption has been run end to end from a device.**

Those are different claims and the difference is the whole point. The four usage payloads were
captured from live accounts and the parsers corrected against them — which found six divergences
that synthetic fixtures could not have, because a fixture written from the same assumption as the
parser cannot catch a wrong assumption. `docs/verification.md` sets out what each layer of
testing can and cannot establish.

What runs in CI, on every push:

| | Android | iOS |
|---|---|---|
| Unit tests | 256, on the JVM | 330, on Linux and macOS |
| Screens rendered | every screen, under Robolectric | every screen, on a booted simulator |
| App launched | the Application, under Robolectric | the real binary, on a booted simulator |
| Installable artifact | debug APK, and a signed release on a tag | unsigned archive |

What that still does not cover: signing in to a real account, a token actually being refreshed,
and a reset credit actually being spent. Refresh in particular has deliberately NOT been
exercised — these providers rotate refresh tokens on use, so testing it against the maintainer's
gateway would have invalidated working credentials. The
per-provider documents in `docs/` each carry their own status section saying exactly what is
and is not verified for that provider — read them before trusting a field name.

## Build

Requirements:

- **JDK 17.** The Kotlin and Java targets are both 17; a newer JDK will work as a toolchain
  but 17 is what the build is configured for.
- **An Android SDK containing platform 35 and build-tools for it**, located either through
  `ANDROID_HOME` or through `sdk.dir` in `local.properties`.
- No global Gradle install — the wrapper pins Gradle 8.11.1 and downloads it on first run.

```sh
export ANDROID_HOME=/path/to/android-sdk    # must contain platforms/android-35
./gradlew :app:assembleDebug
```

Key versions: `minSdk` 26 (Android 8.0), `compileSdk` and `targetSdk` 35, AGP 8.7.3, Kotlin
2.0.21, Compose BOM 2024.12.01, Room 2.6.1 with KSP, Glance 1.1.1. `docs/compatibility.md`
explains why the floor is 26 and what it would take to go lower.

The debug variant installs as `com.usagelimits.debug`, so it can sit alongside a release
build. Note that `assembleRelease` is currently signed with the **debug** signing config and
minifies against an empty `proguard-rules.pro`; that is enough to verify the release build
locally and is not enough to ship. Both must be fixed before any distribution.

## Tests

```sh
./gradlew :app:testDebugUnitTest
```

Everything under `app/src/test` is a plain JVM JUnit 4 test — no Robolectric, no emulator, no
network. That is a deliberate constraint rather than an accident: the four provider parsers
and the shared model helpers (`Severity`, `WindowCategory`, `Instants`, `Countdown`) are
written as pure Kotlin with no `android.*` dependency precisely so they can be tested this
way, and every fixture is synthetic JSON with invented identifiers and `example.com`
addresses. There is no instrumented (`androidTest`) source set.

## Documentation

| Document | What it covers |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Layering, the `UsageProvider` abstraction, the normalised model, the sync pipeline, and the two deliberate deviations from convention |
| [docs/verification.md](docs/verification.md) | How the app checks it is telling the truth: the three layers of verification, what each can and cannot catch, and the defects each one actually found |
| [docs/security.md](docs/security.md) | Threat model, credential storage, the no-tokens-in list, PKCE and state validation, and a frank accepted-risks section |
| [docs/widgets.md](docs/widgets.md) | The two widget sizes, the configuration model, the Room-only data path, and the Glance constraints that shaped the layout |
| [docs/compatibility.md](docs/compatibility.md) | Android version floor, foldables, window size classes, RTL and accessibility |
| [docs/provider-auth-research.md](docs/provider-auth-research.md) | Phase 0: the feasibility matrix, why each login flow was chosen for Android, and the three corrections reading the current upstream code forced |
| [docs/release.md](docs/release.md) | Building, testing, signing a real release, and what must be reviewed before any public distribution |
| [docs/providers-codex.md](docs/providers-codex.md) · [claude](docs/providers-claude.md) · [antigravity](docs/providers-antigravity.md) · [xai](docs/providers-xai.md) | One document per provider: the exact flow, payload shapes, corrections to the original research, and what remains unverified |

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
