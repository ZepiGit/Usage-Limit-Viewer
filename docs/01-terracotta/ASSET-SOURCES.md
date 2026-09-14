# Image sources and rendering scope

## Original Terracotta design

The original `app-icon.png`, platform badges, `devices.png`, `widgets.png` and individual
device assets have been copied without changes from the first Terracotta package. These
were source-based illustrations, not native app screenshots. The three device shells remain
visible; an iPad screenshot is not relabelled as an Android foldable.

## Added widget illustrations

These are deterministic SVG/PNG reconstructions, not images of a running Android or iOS
widget. No image-generation model was used for them. They reproduce the source-defined
component hierarchy, field choices, spacing where explicit, palette and provider artwork.
Text rasterisation, automatic measurement, system margins and launcher-specific sizing have
not been validated pixel-for-pixel against a native host. Do not describe them as genuine
screenshots or guaranteed 1:1 captures. The editorial panels and captions sit outside the
illustrated widget boundaries.

In particular, Mini Rings retain their logo-only content. Account labels were not invented
inside this widget. Summary instances are identified by external captions. iOS ring labels
use the title field, not Android-only labels. Legacy iOS tiles are not described as account-
configurable. Transparent-widget backgrounds are illustrative backdrops, not wallpaper captures.

Synthetic data: `demo-accounts.json`. Four separate local identities: Codex Personal / Work
and Claude Personal / Work. Data values are shared consistently between illustrations.

Source checked on 14 September 2026. Repository main resolved to `bfb9a14fe73d37b262d1007fd6d20c3f44513cb2`.

- [Android Usage Summary / Usage Bars](https://github.com/ZepiGit/Usage-Limit-Viewer/blob/bfb9a14fe73d37b262d1007fd6d20c3f44513cb2/app/src/main/kotlin/com/usagelimits/widget/UsageWidgets.kt)
- [Android Account Rings / Mini Rings](https://github.com/ZepiGit/Usage-Limit-Viewer/blob/bfb9a14fe73d37b262d1007fd6d20c3f44513cb2/app/src/main/kotlin/com/usagelimits/widget/RingWidgets.kt)
- [Android panel and status colours](https://github.com/ZepiGit/Usage-Limit-Viewer/blob/bfb9a14fe73d37b262d1007fd6d20c3f44513cb2/app/src/main/kotlin/com/usagelimits/widget/WidgetStyle.kt)
- [Android layout rules](https://github.com/ZepiGit/Usage-Limit-Viewer/blob/bfb9a14fe73d37b262d1007fd6d20c3f44513cb2/app/src/main/kotlin/com/usagelimits/widget/WidgetLayout.kt)
- [iOS configurable widgets](https://github.com/ZepiGit/Usage-Limit-Viewer/blob/bfb9a14fe73d37b262d1007fd6d20c3f44513cb2/ios/UsageLimits/Sources/Widget/ConfiguredWidgets.swift)
- [iOS standard, clear, ring and accessory variants](https://github.com/ZepiGit/Usage-Limit-Viewer/blob/bfb9a14fe73d37b262d1007fd6d20c3f44513cb2/ios/UsageLimits/Sources/Widget/UsageWidget.swift)
- [OpenAI provider artwork](https://github.com/ZepiGit/Usage-Limit-Viewer/blob/bfb9a14fe73d37b262d1007fd6d20c3f44513cb2/assets/providers/lobehub/openai.svg)
- [Claude Code provider artwork](https://github.com/ZepiGit/Usage-Limit-Viewer/blob/bfb9a14fe73d37b262d1007fd6d20c3f44513cb2/assets/providers/lobehub/claudecode-color.svg)

## Original native screenshots in the app tour

The five PNG files in `screenshots/` are copied unchanged from the GitHub Actions artifact
`ios-marketing-screenshots`, run `34712814242`, artifact `10303876932`. Capture commit:
`abc43759b19bcc59894b9ab055fec358916e1065`, 12 September 2026. The archive's `CAPTURE.txt` is
included. Screens use synthetic demo accounts, not live credentials; they precede the widget
source inspected for this README. The tour does not call a provider and does not fabricate
additional native screens. A generic phone frame surrounds the screenshots without
retouching the files.

[Capture run](https://github.com/ZepiGit/Usage-Limit-Viewer/actions/runs/34712814242)

## Native verification

No Android emulator, WidgetKit host or physical phone was run for this documentation task.
The local browser rendering and tour navigation were tested separately; they do not establish
native app correctness or foldable hardware compatibility.
