# Device and OS compatibility

## Android toolchain

| Setting | Current value |
|---|---|
| Minimum Android version | API 26 (Android 8.0) |
| Compile and target SDK | API 36 (Android 16) |
| Android Gradle Plugin / wrapper | 8.10.1 / 8.11.1 |
| Robolectric | 4.16.1 |
| JVM for API 36 tests | JDK 21 |
| App bytecode target | Java 17; packaging CI uses JDK 17 |

The API 26 floor provides native `java.time` and notification channels. Notification
permission is requested on API 33 and later; declining it does not block quota
viewing. The final minified APK was exercised on an API 36 AOSP x86_64 emulator
with WHPX. Earlier API 35 checks provide additional widget and system-bar evidence.

## Window sizes and navigation

Android chooses navigation from the current window width, including split-screen
and resizable windows:

| Window width | Navigation |
|---|---|
| Below 600 dp | Bottom bar |
| 600–839 dp | Side rail |
| 840 dp and wider | Side rail |

Content is centered and capped at 720 dp. The activities allow resizing and do
not lock orientation. The provider picker scrolls in short landscape windows;
its regression test was observed failing before the fix, passing afterwards and
failing again when scrolling was removed in an isolated copy.

Both Android activities use light system-bar icons over the dark app. Widget
configuration consumes safe drawing insets. These changes address issues found
on the API 35 emulator. The manifest has no edge-to-edge opt-out; navigation
uses AndroidX rather than a custom legacy back-key handler. The final API 36 run
covered a narrow 320 dp window and simulated effective windows of 960×540 dp
(landscape), 800×1280 dp (tablet) and 840×720 dp (unfolded size). Wide windows
used the side rail, and the landscape provider picker scrolled to Kimi;
physical foldable transitions have not been exercised.

iOS content fills narrow windows and is capped at 760 pt on wider ones. The app
supports iPhone and iPad, including landscape and split view. The CI workflow now
includes an iPad run and a landscape/large-text provider-picker check. Real SDK
compilation completed at `19fc063`, but three scroll-gesture UI checks failed.
The corrected iPhone/iPad run remains pending.

## Widgets, icons and accessibility

Android's compact and ring widgets use Glance responsive sizes and support
resizing. Both were placed on the API 35 emulator in their empty state. The final
API 36 APK also passed compact-widget configuration, placement and opening the
app from the widget. Populated accounts, additional launcher grids and physical
foldable resizing still need runtime checks. iOS uses WidgetKit and the shared
App Group snapshot.

Both platforms use the same quota-cell and return-arrow icon. Android supplies
adaptive and monochrome variants. The iOS AppIcon catalog is wired into the
XcodeGen build and contains 18 opaque slots.

Status includes text and bar length as well as color. Android exposes grouped
usage descriptions to screen readers and enables RTL support. At 320 dp and font
scales 1.3 and 2.0, the final API 36 APK wrapped the sync-interval choices and kept
the 3 h option visible and selectable. Some bottom-tab labels break mid-word at
2.0; their touch targets remain accessible. Native keyboard/pointer behavior is
retained, but those input paths and screen-reader behavior need device checks.

Android observes changes to the system animator scale while the app is open.
Scale zero removes value interpolation, item movement and sign-in transitions;
Add-account navigation and Back passed with scale zero on the API 36 emulator.
iOS reads `accessibilityReduceMotion`. Test large text, screen readers, RTL,
keyboard navigation and reduced motion on populated screens before distribution.

## Verified scope

The final API 36 build passed 400 Android tests without failures or skips;
`aapt` confirmed minimum 26, target 36 and compile 36 in the APK. All 456 Swift
package tests and the Linux app/widget type check passed.

The API 35 emulator covered installation, launch, empty refresh, all four tabs,
and both widgets. Before/after screenshots confirm the system-bar and widget
inset fixes. The final API 36 run covered the layouts, large text, reduced-motion
navigation and compact-widget steps described above; its crash buffer was empty.
Emulator settings were restored after the checks. Physical-device checks remain open. The PR records the corrected iPhone/iPad
simulator CI results. Local screenshots,
UI dumps and runtime logs are under `C:/temp/ulv-audit`.
See [verification.md](verification.md) for the runbook and
[release-readiness.md](release-readiness.md) for the measured audit evidence.
