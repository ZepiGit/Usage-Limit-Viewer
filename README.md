<p align="center"><img src="docs/01-terracotta/app-icon.png" width="76" alt="Usage Limits app icon"></p>

<h1 align="center">Usage Limits Mobile</h1>

<p align="center"><strong>Know what’s left. See what resets next.</strong><br>Codex, Claude, Antigravity, Grok and Kimi — on your phone and home screen.</p>

<p align="center">
<a href="#build"><img src="docs/01-terracotta/android-badge.png" height="25" alt="Android 8.0+"></a> <a href="#build"><img src="docs/01-terracotta/ios-badge.png" height="25" alt="iOS 16.0+"></a> <a href="#build"><img src="docs/01-terracotta/source-badge.png" height="25" alt="Build from source"></a> 
</p>

<p align="center"><a href="#features">Features</a> · <a href="#home-screen-widgets">Widgets</a> · <a href="#multiple-accounts-per-provider">Multiple accounts</a> · <a href="#app-tour">App tour</a> · <a href="#providers">Providers</a> · <a href="#build">Build</a> · <a href="#documentation">Documentation</a></p>

<p align="center"><img src="docs/01-terracotta/devices.png" width="1000" alt="Usage Limits source-based mockups on an Android phone, iPhone and unfolded foldable"></p>

<p><sub>Illustrative device mockups with synthetic data, retained from the original Terracotta design; not native app captures. Foldable hardware transitions remain unverified.</sub></p>

> **Development preview · build from source.** Live
> sign-in and physical-device verification are not complete for every provider and device.


## Features

| One overview | Reset timeline | Home-screen widgets |
| :--- | :--- | :--- |
| Every connected account, with the windows that need attention first. | See when each allowance comes back, in chronological order. | Read your cached usage without opening the app. |

<img src="docs/01-terracotta/widgets.png" width="1000" alt="Home-screen widget summary with demonstration quota values">

## Home-screen widgets

**The home screen is part of the app.** Choose the view that fits your space: a compact
summary, detailed usage bars, account rings or mini rings. The examples below use **two
Codex accounts and two Claude accounts**, with different allowances for each connection.

<p align="center"><a href="docs/01-terracotta/widgets-gallery.png"><img src="docs/01-terracotta/widgets-gallery.png" width="1000" alt="Four source-based widget renders: Usage Bars with two Codex and two Claude accounts, Usage Summary, Account Rings and Mini Rings"></a></p>

<p><sub>Source-based widget illustrations with synthetic accounts, not native screenshots. OS text rendering and launcher dimensions can differ. <a href="docs/01-terracotta/ASSET-SOURCES.md">Image sources and scope</a>.</sub></p>

### Usage Summary — the compact check

A short row for the five-hour allowance, the long allowance window, the next reset and
quota status. Smaller placements adapt the readout to the available width.

**Track two accounts separately:** place the widget twice and choose a different account
for each instance. The names above these examples are presentation captions, not added
widget controls. Each tile reports its selected account; it does not add subscriptions together.

<p align="center"><a href="docs/01-terracotta/summary-scopes.png"><img src="docs/01-terracotta/summary-scopes.png" width="1000" alt="Two separate Usage Summary instances: Codex Personal has 72 percent of its five-hour allowance left and Codex Work has 41 percent"></a></p>

### Usage Bars — every account keeps its own rows

Provider, account label, quota windows, percentages and reset times stay together. **Two
accounts with the same provider are two separate cards**, not a shared provider balance.
Choose one provider to keep its accounts together, or use a custom selection and order.
The large gallery above shows all four demo accounts in one widget.

### Account Rings — identity, allowance and reset

A provider logo sits inside each account’s usage ring, with account information and the
reset alongside it. On Android, account labels make **Personal** and **Work** easy to distinguish;
the iOS layout uses the account title. Larger placements can show more than one column.

### Mini Rings — the smallest view

One ring and provider logo for each selected account. **Matching logos can belong to different
accounts**: the examples show two Codex logos and two Claude logos, with different ring fills.
Mini Rings deliberately omit long labels; select the accounts and their order in the widget
configuration rather than expecting names inside a tiny ring.

<p align="center"><a href="docs/01-terracotta/rings-multiaccount.png"><img src="docs/01-terracotta/rings-multiaccount.png" width="1000" alt="Account Rings show Personal and Work separately; Mini Rings show two independent Codex accounts and a four-account Codex and Claude selection"></a></p>

<details>
<summary><strong>All iPhone and iPad widget entries — including the original variants</strong></summary>

<p align="center"><a href="docs/01-terracotta/ios-widget-gallery.png"><img src="docs/01-terracotta/ios-widget-gallery.png" width="1000" alt="Source-based iOS examples of Usage Bars, Account Rings, Mini Rings, Usage Limits, Usage Limits Clear and Usage Ring"></a></p>

| Widget | Availability | What it adds |
| :--- | :--- | :--- |
| **Usage Bars** | iOS 17+ | Configurable account, provider or custom selection; small, medium and large. |
| **Account Rings** | iOS 17+ | Configurable ring grid with account title and reset information. |
| **Mini Rings** | iOS 17+ | Configurable provider-logo rings without the text column. |
| **Usage Limits** | iOS 16+ | Original small, medium and large quota tiles; also a rectangular Lock Screen accessory. |
| **Usage Limits (Clear)** | iOS 16+ | Original quota tiles without the opaque panel. |
| **Usage Ring** | iOS 16+ | A single-account ring; also a circular Lock Screen accessory. |

The original iOS entries follow the ranked account selection. **Use the configurable iOS
17+ entries to choose a particular account, one provider or a saved custom layout.**
The availability of a widget is separate from the app’s iOS 16 deployment target.

</details>

### Make each widget your own

| Choose the content | Arrange the accounts | Set the appearance |
| :--- | :--- | :--- |
| All accounts, one provider, one account or a custom selection. | Follow the app’s account order or use an independent custom order. | Android: panel colour, opacity and text tone. Configurable iOS widgets: transparent background and text tone. |

Widgets read the local usage cache, not account credentials. Android refresh enqueues the
app’s sync work; iOS refresh opens the app. Widget refreshes do not make model requests.
For implementation details, see [Widgets](docs/widgets.md).

## Multiple accounts per provider

**Two Codex subscriptions? Separate personal and work accounts with Claude? Keep both.**
Each connection has its own account identity, quota windows and reset clock. A provider is
not a single-account slot, and different accounts’ allowances are not pooled.

<p align="center"><a href="docs/01-terracotta/accounts-by-provider.png"><img src="docs/01-terracotta/accounts-by-provider.png" width="1000" alt="Two provider-scoped Usage Bars widgets: Codex Personal and Codex Work on the left, Claude Personal and Claude Work on the right, all with independent quota windows"></a></p>

| Same provider | Separate accounts | Independent five-hour allowance in the illustration |
| :--- | :--- | :--- |
| **OpenAI Codex** | Personal · Work | 72% left · 41% left |
| **Claude** | Personal · Work | 58% left · 18% left |

Connect your own accounts individually in **Accounts**. Then choose **One provider** in a
widget’s content settings to show that provider’s accounts, **One account** for a dedicated
tile, or **Custom** for a mixed selection. On iOS, use a configurable widget on iOS 17+ and
save custom layouts in the app’s settings. These are illustrative accounts, not live usage.

## App tour

<p align="center"><a href="docs/01-terracotta/DEMO.md"><img src="docs/01-terracotta/tour-card.png" width="1000" alt="Open the offline app screenshot tour: Overview, Accounts, Resets and Settings"></a></p>

**[Open the screenshot tour](docs/01-terracotta/DEMO.md).** Click the bottom tabs to explore
four original iPhone Simulator captures. The tour also includes a widget gallery with the
same demo accounts used above. It is an offline presentation, **not a functioning web port**:
there is no sign-in, sync, account editing or access to real provider data.

The supplied browser preview opens the tour directly. On GitHub, the link leads to the
local-opening instructions; an interactive HTML page must be opened in a browser or hosted
separately, rather than executed inside the README.

## Providers

| Provider | Usage windows | Connect with |
| :--- | :--- | :--- |
| **OpenAI Codex** | Five-hour, weekly, plan-dependent limits and reset credits | OAuth + PKCE; device-code fallback |
| **Claude** | Five-hour and reported weekly / per-model windows | OAuth + PKCE |
| **Antigravity** | Per-model quota buckets | Google OAuth + PKCE |
| **Grok** | Weekly credits and monthly billing window | Device-code flow |
| **Kimi Code** | Coding quota and reset windows | Device code or console key |

All five have implementations on Android and iOS. Live sign-in is not verified for every
provider; Kimi OAuth usage access may require vendor approval.


## Build

### Android

Use JDK 21 for tests and Android SDK platform 36.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

### iOS

The app requires macOS, Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
swift test --package-path ios/UsageLimitsKit
cd ios/UsageLimits && xcodegen generate
```

Open the generated project in Xcode to configure signing and run the app.


## Privacy & boundaries

Your phone talks directly to the providers with your own account. There is **no app-owned
account, backend, proxy or telemetry**. This app reads quota; it does not make model calls.
Credentials remain in Android Keystore-backed storage or iOS Keychain. Widgets read only
the local cache, never credentials.

Several integrations use undocumented first-party endpoints. They can change or disappear.
Use only accounts you control, and review provider terms before public distribution.
See the [security model](docs/security.md) and [release readiness](docs/release-readiness.md).


## Documentation

| Start here | For contributors |
| :--- | :--- |
| [Device compatibility](docs/compatibility.md) | [Architecture](docs/architecture.md) |
| [Widgets](docs/widgets.md) | [Verification](docs/verification.md) |
| [Security](docs/security.md) | [Release guide](docs/release.md) |

For provider-specific behavior, see [Codex](docs/providers-codex.md) and the other
`docs/providers-*` guides. Report reproducible problems in
[Issues](https://github.com/ZepiGit/Usage-Limit-Viewer/issues).

