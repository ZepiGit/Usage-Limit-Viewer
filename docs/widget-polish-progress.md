# Account and widget polish

This change addresses the release APK feedback from 12 September 2026. Android and iOS retain their existing visual style while making account status, provider identity and widget selection consistent.

## Delivered behavior

| Area | Result |
| --- | --- |
| Account connection | Connection status is independent of remaining quota and data freshness. An exhausted limit is not a disconnected account. Needs attention contains only accounts requiring sign-in again. |
| Overview | Connected-account count, next future reset, current app artwork and stable manual account order. |
| Provider artwork | 19 transparent choices in Settings, grouped into five providers. Claude Code and Gemini are the defaults for Claude and Antigravity respectively. |
| Ordering | Drag changes persist, and All accounts widgets follow the overview. Custom widget order is independent. |
| Widget content | Closest Resets sorts by the next future reset. All accounts, One provider, One account and Custom apply exact selections. Removed accounts do not make an account-scoped widget display unrelated accounts. |
| Widget configuration | Existing values load when editing. Changes have a preview and explicit save. Background and content settings belong to each widget instance. |
| Android Account Rings | One column at 3×2; two columns at 3×4. Rows have spacing and overflow remains reachable. |
| Android Mini Rings | Provider logo inside each account ring; 1×1 shows two, 1×2 four, and 2×2 eight. |
| Android Usage Bars | A single-account selection displays that account, with readable percentages, reset times and a centered refresh icon. |
| Android picker | Four distinct widget names, content previews and appropriate initial sizes. |
| iOS widgets | Configurable bars, account rings and mini rings on iOS 17+. Settings includes saved custom layouts with account selection and drag ordering. Existing iOS 16 widget entries remain available. |
| Copy and layout | Reset-credit expiry explains the 24-hour notice. Duplicate provider/tier titles are removed; quota windows retain independent colors and readable labels. |

## Verification

All device records used for verification are synthetic. No real account credentials or provider sessions were used.

- Android: 413 unit tests passed, with zero failures, errors or skipped tests. A local debug build and minified release build completed successfully. [Android CI at the final Android source commit](https://github.com/ZepiGit/Usage-Limit-Viewer/actions/runs/34712221745) also passed.
- Android database upgrade: a real version-6 fixture migrated to version 8 while preserving account selection and widget transparency.
- Android 16 emulator: 11 valid accounts remained connected despite exhausted limits; forcing one synthetic authentication failure produced 10/11 connected and exactly one Needs attention result.
- Android home screen: verified Mini Rings at 1×1, 1×2 and 2×2; Account Rings at 3×2 and 3×4; exact single-account bars; custom selection and drag order; live icon updates; separate opaque and transparent widgets.
- Android restart: overview ordering and icon choices survived app restart. Widget selection, custom order and separate backgrounds also survived an emulator restart.
- iOS: shared-package tests passed on Linux and macOS, and the real iOS SDK compiled the app and widget extension. The simulator verified provider-icon persistence after relaunch in [this successful iOS run](https://github.com/ZepiGit/Usage-Limit-Viewer/actions/runs/34710860434).
- iOS custom layouts: the [iPhone/iPad capture run](https://github.com/ZepiGit/Usage-Limit-Viewer/actions/runs/34712814242) passed assertions for account selection, drag reordering, saving and restoration after relaunch. Captures use real SwiftUI screens with demo data.
- Assets: all 38 platform icon exports have transparent pixels. The debug-only emulator fixture bypass is absent from the minified release DEX.

## Design references

Widget editing follows the preview and explicit-save patterns inspected in Mobbin: [Vocabulary](https://mobbin.com/screens/e4c5803f-2fc3-4895-9697-7e27ed175a6c), [Airbuds](https://mobbin.com/screens/dc46c160-5b10-41f6-95bc-c0df3077b667) and [Life Reset](https://mobbin.com/screens/8efc71f9-bf9c-4a53-99fa-9ce20dc6c240). Account identity and full-width limit rows were compared with [Rocket Money](https://mobbin.com/screens/2f6c9f01-08d2-40c4-be25-ea1e89f7cfe5), [Mercury](https://mobbin.com/screens/2927d241-dd5f-4eff-99f2-48c788b217c3) and [Buddy](https://mobbin.com/screens/684e06c5-6ce4-419f-986f-ae4c2d5ae8ed).

## Coverage boundaries

Android launcher verification used the Android 16 AOSP emulator. Other launchers can report different cell dimensions; the widget records placement metrics and responds to available bounds. A physical-device launcher matrix was not run. iOS app and custom-layout flows ran on simulator; SpringBoard widget placement and resizing were not exercised interactively. Live OAuth reconnection was covered by existing provider tests and synthetic state transitions, not by signing into real accounts.

Final integration status and checks are recorded by the pull request and its merge commit.
