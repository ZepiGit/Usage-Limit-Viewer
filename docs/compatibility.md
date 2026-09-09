# Device and OS compatibility

## Android versions

| | Value | Why |
|---|---|---|
| `minSdk` | 26 (Android 8.0, Oreo) | Covers essentially the entire active install base. It is also the floor at which the three things this app depends on are dependable: `java.time` is available natively (no desugaring), the Keystore's AES/GCM behaviour is consistent, and notification channels exist, so there is no second notification code path. |
| `targetSdk` | 35 (Android 15) | Current at time of writing. Targeting the newest platform opts the app into the current background-work, permission and windowing rules rather than running under legacy compatibility shims. |
| `compileSdk` | 35 | Matches `targetSdk`. |

Going below 26 would mean adding core-library desugaring for `java.time` and a
pre-O notification path, in exchange for a fraction of a percent of devices. If
that trade ever becomes worth making, desugaring is the only real change: no API
below 26 is used anywhere except through the Keystore and Glance, both of which
already support 23+.

**Version-specific handling actually present in the code:**

- `POST_NOTIFICATIONS` is requested only on API 33+, and every notification path
  checks the permission first. Notifications are optional everywhere — declining
  them never blocks a feature.
- Notification channels are created unconditionally behind an API 26 check, which
  `minSdk` makes a formality but keeps honest.
- No API newer than 26 is called without a guard.

## Screen sizes, foldables and windowing

The app derives its layout from the **window** size class, never from the physical
screen or the device model. That single decision is what makes it correct on
hardware nobody enumerated: a folded phone, an unfolded inner display, a tablet, a
split-screen pane, a freeform window and a desktop-mode window are all just
widths.

| Window width | Layout |
|---|---|
| Compact (< 600dp) — phones, folded foldables, narrow split-screen | Bottom navigation bar |
| Medium (600–839dp) — unfolded foldables, small tablets, half-screen on a tablet | Navigation rail |
| Expanded (≥ 840dp) — tablets, desktop windows | Navigation rail |

Two further rules keep wide windows readable:

- **Content is capped at 720dp and centred.** A usage row is label → bar → percent
  → countdown. Stretched across a tablet, the eye has to travel the full width to
  connect a label to its number. Past a comfortable measure the extra space becomes
  margin instead of line length.
- **Nothing is orientation-locked or aspect-ratio-locked.** `screenOrientation` is
  `unspecified` and `resizeableActivity` is `true`, so unfolding a device re-lays
  out rather than letterboxing.

`configChanges` declares `screenSize|screenLayout|smallestScreenSize|orientation|density|uiMode`,
so a fold, rotation or resize is handled in-place by Compose rather than by
destroying and recreating the activity. `calculateWindowSizeClass` recomposes on
each of those changes, so the shell switches between bar and rail live as the
device folds.

State survives configuration changes regardless, because all screen state lives in
a `ViewModel` and the navigation back stack, not in the activity.

## Widgets

Both widgets use Glance's `SizeMode.Responsive` with three declared sizes rather
than `SizeMode.Exact`. Launcher grid cells differ substantially between a phone, a
tablet and an unfolded foldable; with `Responsive`, the launcher picks the nearest
declared size and the widget renders a layout it was actually designed for,
instead of having one layout re-measured into a shape it never anticipated.

Both are resizeable (`minResizeWidth` 180dp), so a user on a dense grid can shrink
the compact widget to roughly two cells and still get a usable row.

## RTL and accessibility

- `supportsRtl` is enabled and every layout uses start/end-relative modifiers, so
  mirroring is automatic.
- Status is never encoded in colour alone — each state carries a text label and a
  bar length as well, which is what keeps the screens usable for colour-blind
  users and in bright sunlight.
- Each usage row is merged into one spoken sentence for screen readers
  ("Weekly, 53% remaining, Reset in 5d 2h"). Four disconnected fragments per
  window becomes unusable once an account reports five of them.
- Type and spacing follow Material 3 defaults, so system font scaling applies
  without clipping.

## What has not been verified

No part of this has been exercised on physical hardware or an emulator in the
environment where it was built. The layout rules above are implemented and the
project compiles, but "renders correctly on a Pixel Fold" is a claim that needs a
device or an emulator to support it. Treat the adaptive behaviour as designed and
implemented, not as tested.
