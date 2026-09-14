# Login and widget fixes — 14 September 2026

The reported login messages came from the shared Android HTTP client. Token exchanges
correctly disabled retries when delivery was uncertain, but also stopped immediately
when DNS or connection setup failed before any HTTP exchange. The client now retries
those confirmed, temporary connection failures within its existing two-retry budget.
The request body remains one-shot. Lost responses, HTTP 5xx, certificate failures and
failures of unknown origin do not trigger another presentation of the grant.

The summary widget read `androidx.compose.ui.platform.LocalContext`, which Glance does
not provide. Its composition failed with `CompositionLocal LocalContext not present`;
Glance then replaced the widget with “Can't show content”. It now uses Glance's context.
All widget types also retain their data flow for the composition session instead of
resubscribing whenever the view changes.

The old summary widget failure was reproduced in both Robolectric and the Android 16
AOSP launcher. Installing the corrected APK over that installation restored the placed
widget without recreating it. Summary, Usage Bars, Account Rings and Mini Rings retained
their content and configuration after restarting the emulator. Summary and Usage Bars
also survived repeated refresh actions. No Glance or host rendering errors were logged
after the update or reboot. Usage Bars did not reproduce a separate rendering failure
on this launcher.

The full Android suite passed: 454 tests, no failures, errors or skipped tests. New
coverage exercises temporary DNS and connection failures, the retry limit, certificate
rejection, all three provider code exchanges, Glance composition and host view updates.
Existing tests still verify that lost replies and server errors never replay a grant.
Reintroducing the old retry condition and the wrong widget context made six regression
tests fail again. The corrected source files were then restored byte for byte. The
minified release APK also built successfully and passed APK signature verification.

The additional `lintDebug` check reports an existing `MissingPermission` error at
`NotificationPublisher.kt:189`. That file already checks `hasPermission()` before posting,
but the lint analyser does not recognise the helper. This change does not alter that path.

Provider exchanges were tested against local HTTP fixtures. No personal provider login
was performed, and the user's physical handset and launcher were not available for testing.
A persistent DNS block or a connection lost after a grant was sent can still require
starting a new login.
