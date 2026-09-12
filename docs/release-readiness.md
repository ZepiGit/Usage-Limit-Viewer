# Release verification, 12 September 2026

This is a private verification build. It is not signed for distribution and no live provider
account has been accessed during this audit.

## Current verification state

The final local run passed all 400 Android tests, with no failures or skips, and
all 456 Swift package tests. Android debug and minified release APKs built
successfully. `aapt` confirmed minimum API 26, target API 36 and compile API 36.
The build uses AGP 8.10.1 and Robolectric 4.16.1; tests run on JDK 21, while app
bytecode remains Java 17. The final iOS app and widget sources also passed the
Linux framework-shim type check.

The final minified APK passed the account-free API 36 emulator checks below,
and the crash buffer was empty afterwards. In CI for
[PR #9](https://github.com/ZepiGit/Usage-Limit-Viewer/pull/9), real iOS SDK
compilation completed in the earlier run at `19fc063`, but three
scroll-gesture checks failed. The captured frames showed no content movement;
the test now scrolls the identified container and resets orientation between cases.
The PR records the current head and the corrected iPhone/iPad CI results.

## Fixes and regression evidence

| Change | Evidence |
|---|---|
| Kimi/Grok device grants interpret HTTP 400/403 instead of treating every error as pending | Android `DevicePollProtocolTest`, `KimiLoginTest`; Swift `DeviceLoginTests`. Denial and expiry stop before another request; both later waits after `slow_down` are 10 seconds rather than 5. Original code restored in a separate build copy: tests failed again. |
| A credential removed before Android refresh acquires its lock is not used | `RefreshAfterRemovalTest`: the second credential read stops the operation; restoring the fallback to the pre-lock value fails the regression. |
| An outgoing sign-in step cannot start a second login | `SignInTransitionTest` invokes a captured click after the state changes. Disabling the gate makes it fail. |
| Android provider hints describe the actual default flows | `SignInTransitionTest`: Codex says browser; Kimi says device code. Restoring the old hints fails. |
| Android follows changes to system reduced motion while open | `ReducedMotionTest` changes the global animator scale and checks the theme's current policy. Forcing motion on makes it fail. |
| Android 15 system bars remain readable; widget configuration clears the status bar | API 35 before/after screenshots confirm light status icons and the widget title moving from y=42 to y=170, below the 128-pixel status bar, after dark system-bar styling and safe drawing insets. |
| The provider picker remains reachable in short landscape windows | The Android scroll regression failed before the fix, passed after it, and failed when scrolling was removed in an isolated copy. |
| Android sync intervals remain reachable with large text in a narrow window | On the final API 36 APK, the interval choices wrapped at 320 dp with font scales 1.3 and 2.0; the 3 h choice remained visible and selectable. |
| iOS browser dismissal preserves an earlier bind error for Codex fallback, while actual task cancellation wins | `LoopbackSignInRaceTests` covers the masked bind error and queued-code cancellation. Tests failed with the old behavior, passed with the fix, and failed when it was restored in an isolated copy. |
| Long Kimi/Grok HTTP error bodies remain parseable during device login | Swift `DeviceLoginTests` exercises long `slow_down` and `authorization_pending` responses while retaining truncated diagnostics for ordinary HTTP errors. Red, green and isolated sabotage runs confirmed the regression. |
| Unrelated Codex callbacks do not consume the pending login | Android `CodexBrowserLoginTest` sends unrelated callbacks before the matching redirect and checks that a matching denial ends login without exchanging a code. Red, green and isolated sabotage runs confirmed the fix. |

These regressions use synthetic data and local callbacks, without live provider credentials.

## Size and runtime

The starting commit was `66389ab`. Package sizes are:

| Build | Debug APK | Release APK |
|---|---:|---:|
| Baseline, AGP 8.7.3 / SDK 35 | 13,702,817 bytes | 2,491,175 bytes, unsigned |
| Final, AGP 8.10.1 / SDK 36 | 13,737,666 bytes | 2,481,299 bytes, debug-signed |

The baseline used JDK 17. Signing overhead and the AGP/R8 toolchain change mean these
are package measurements, not a controlled comparison of application code or
evidence of a performance optimization. The final release APK is a local
verification artifact and is not signed for distribution.

An API 35 AOSP x86_64 emulator exercised install, launch, empty refresh, all four
tabs, and placement of the compact and ring widgets. Five launch samples were
965, 921, 950, 952 and 973 ms, with mixed COLD/WARM classifications. One PSS
sample was 27,541 kB. These are baseline observations, not five cold starts or
evidence of a speed or memory improvement.

The final run used the API 36 AOSP x86_64 emulator `emulator-5582` with WHPX and
the 2,481,299-byte minified release APK. Five launches reported
`LaunchState: COLD`, with no app PID before each launch. The `am start -W`
TotalTime values were 621, 612, 632, 627 and 650 ms: median 627 ms, range
612–650 ms. One idle `dumpsys meminfo` sample reported PSS 25,997 kB and RSS
154,040 kB. The API 35 baseline and API 36 final run differ in OS, toolchain and
launch method, so these results do not establish a speed or memory improvement.
No physical-device comparison was performed.

No runtime dependency was added to either app. AGP and Robolectric were upgraded
for API 36. The dependency review found the window library is also
required transitively by Material's window-size classes; it was retained. Startup DI is lazy,
Android settings/scheduling work is off the UI thread, and no new motion triggers a sync or
owns a background timer. No speed or memory reduction is claimed without a measured comparison.

## Motion and visual checks

Bars animate their fill and severity colour in 240 ms, sign-in steps crossfade in 180 ms, and
account lists use their platform's item transitions. The dragged Android card keeps its finger
position without a competing placement spring. SwiftUI uses its native list reorder behavior.
The same provider glyphs and the quota-cell/return-arrow app icon are configured
on both platforms. Android includes adaptive and monochrome icons; the iOS
AppIcon catalog contains 18 opaque slots and has its build setting wired up.

Android content is capped at 720 dp, with bottom navigation below 600 dp and a
rail above it. iOS content is capped at 760 pt and fills narrower windows.
The final API 36 smoke covered:

- Empty refresh and all four tabs.
- A 320 dp window at font scales 1.3 and 2.0, including selection of the 3 h sync
  interval after the choices wrapped.
- Simulated effective windows of 960×540 dp (landscape), 800×1280 dp (tablet) and
  840×720 dp (unfolded size) with side-rail navigation; the landscape provider
  picker scrolled to Kimi.
- Compact-widget configuration, placement and opening the app from the widget.
- Add-account navigation and Back with animator scale zero.

The emulator's window, font and animation settings were restored. Some bottom-tab
labels break mid-word at font scale 2.0, while the touch targets remain accessible.
Physical foldable transitions and populated-account layouts remain unverified.
iPad, landscape and large-font checks are part of the pending corrected iOS CI run.

Android scale zero selects target values, suppresses item movement and gives sign-in changes
zero duration. The system-setting observer is scoped to the theme and removed with it.
SwiftUI reads `accessibilityReduceMotion`; value animations select nil, and widget numeric
transitions are disabled when reduced motion is set. WidgetKit owns timeline refresh motion;
Glance remains static. No animation dependency or repeating animation was introduced.

Before distribution, verify on both platforms with populated accounts: change a window from
healthy to low, add/remove an account, reorder, complete or cancel sign-in, and refresh each
widget. Repeat with reduced motion on, including toggling it during a transition. Values and
focus must update immediately without motion; leaving the screen must stop its work. The
Linux Apple framework shims do not establish runtime animation behavior.

## Owner actions still required

- Complete provider permission and ToS review, including Kimi's `UsageLimits` allowlist entry.
- Create and retain the real signing keys and provisioning credentials outside the repository.
- Run live sign-in, renewal and quota comparisons with accounts the owner controls. The
  minified-build login portion remains human-only, even where installation and widgets pass.
- Supply privacy policy and store/legal copy. Store publication is outside this task.

Local machine evidence is saved outside the repository under `C:/temp/ulv-audit`. CI links and
the final artifact measurements belong in the PR description as well as this record.
