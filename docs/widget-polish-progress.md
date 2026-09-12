# Account and widget polish

Branch: `fix/account-status-and-widget-polish`. Starting commit: d09f198.

## Acceptance checklist

- [ ] Android/iOS separate connection, quota and freshness; Needs attention is reauthentication only.
- [ ] Overview counts connected accounts and shows the next future reset across accounts.
- [ ] Drag order survives sync/restart and matches All accounts widgets.
- [ ] Closest Resets, account, provider and ordered Custom scopes are exact and per-widget.
- [ ] Configuration loads saved values, previews changes and saves explicitly; transparency survives refresh/resize/restart.
- [ ] Android Account Rings: one column at 3x2, two at 3x4, spacing and reachable overflow.
- [ ] Mini Rings: one ring/account with logo; capacities 2 at 1x1, 4 at 1x2, 8 at 2x2.
- [ ] Widget names, previews, dimensions and refresh alignment match content.
- [ ] Current app icon and genuine provider assets on both platforms.
- [ ] Clear credit-expiry copy; no duplicate titles or ambiguous limit labels; independent per-limit colors.
- [ ] Android tests/minified build/device flows; iOS tests/real SDK build/simulator flows.
- [ ] Reviewed, committed, merged to remote main and verified.

## Decisions and evidence

- One ring per account showing its tightest limit is the default. Physical-device launcher remains unspecified.
- Mobbin references inspected: [Vocabulary](https://mobbin.com/screens/e4c5803f-2fc3-4895-9697-7e27ed175a6c), [Airbuds](https://mobbin.com/screens/dc46c160-5b10-41f6-95bc-c0df3077b667), [Life Reset](https://mobbin.com/screens/8efc71f9-bf9c-4a53-99fa-9ce20dc6c240). Adopt preview, content/appearance controls and explicit save.
- Android connection model, database migration 7, reactive widget configuration, Custom scope, Closest Resets, exact widget sizing and new ring renderer implemented; not yet comprehensively tested.
- Android compileDebugKotlin, all 407 unit tests and debug/minified release assembly passed. UI/runtime evidence remains pending.
- Claude asset upgraded to official 512px App Store artwork per user request; other original marks prepared. iOS connection state, scopes, provider badge rendering and overview foundation implemented; iOS widget configuration and remaining layouts pending.
- User additionally authorized sensible Android/iOS layout improvements informed by Mobbin. Inspected [Rocket Money](https://mobbin.com/screens/2f6c9f01-08d2-40c4-be25-ea1e89f7cfe5), [Mercury](https://mobbin.com/screens/2927d241-dd5f-4eff-99f2-48c788b217c3), [Buddy](https://mobbin.com/screens/684e06c5-6ce4-419f-986f-ae4c2d5ae8ed). Android limit rows now show readable full-width labels, percentage, bar and reset.
- Runtime tools: JDK21 at C:/temp/ulv-jdk21/jdk-21.0.12.1+1. Build SDK at C:/Users/miche/AppData/Local/ElWeatherTools/android-sdk (platform 36 and build tools 35 installed). Android emulator/system images also available under C:/temp/ulv-android-qa/sdk.
- Remaining: authenticate status UI/reconnect routing, reliable drag/autoscroll tests, picker previews/default dimensions, widget selection and transparency runtime proof, Android regression tests, all iOS logic/UI, final review and main merge.
- Checkpoint committed and pushed as 02344a2. Android Actions run 34703221603 and iOS run 34703221691 started. Linux Swift package tests completed, then its additional shim-based app typecheck flagged missing `scaledToFit` in the local shim; added that API surface. Real SDK and macOS jobs remain authoritative for iOS compilation/runtime.
- Next: finish iOS selectable widget configurations and custom preset editor, app account filters/reconnect UI, per-widget mini rings; add Assets.xcassets to the widget target; build/test in Actions. Android device proof and final layout/reorder work still required. No merge to main yet.
