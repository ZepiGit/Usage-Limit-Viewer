# Widgets

The widgets are the reason the app exists. Opening an app to find out whether you have Codex
quota left is only marginally better than hitting the limit and finding out the hard way; a
number on the home screen is the actual product. Everything below follows from that, including
the decision to make the widgets read a cache rather than the network.

Both are built with Glance (`androidx.glance:glance-appwidget:1.1.1`), which compiles a
Compose-like description into `RemoteViews` that the launcher renders in its own process.
`app/src/main/kotlin/com/usagelimits/widget/` holds all three files: `UsageWidgets.kt` (the two
widgets and their receivers), `WidgetData.kt` (the reducer and its data types) and
`WidgetUpdater.kt` (the bridge to the cache, plus the refresh action).

## The three Android sizes

### Minimal — one ring, nothing else (`MinimalUsageWidget`)

Declared at `targetCellWidth="1"`, `targetCellHeight="1"`. A single ring whose arc is what is
LEFT of the leading account's tightest window, with the percentage, the window's label and its
reset inside. It exists because a bar has to be as wide as the tile to be legible, so a tile of
bars is a tile of little else — where a ring carries the same number in a square, and four of
them fit where one detailed widget does.

`UsageRing.fractionFor` returns a FULL ring for an unknown percentage rather than an empty one,
and the label then reads "—": an empty ring is what "exhausted" looks like, and drawing it for
"the provider did not say" would announce a limit nobody reported.

### Compact — one row, four tiles (`CompactUsageWidget`)

Declared at `targetCellWidth="4"`, `targetCellHeight="1"`, resizeable horizontally down to
180dp. Four equal tiles across a rounded card:

| Tile | Shows |
|---|---|
| **5h limit** | The tightest five-hour window across every account in scope, as remaining percent, with a bar |
| **Weekly** | The tightest weekly window, with a bar — falling back to the tightest monthly window when a plan has no weekly one |
| **Next reset** | The clock time of the *earliest* reset still ahead for the LEADING account — absolute, never a countdown, because a widget cannot tick; and only a reset still ahead, because a snapshot keeps a passed instant until the next fetch replaces it |
| **Quota** | One word for the overall worst state: `OK`, `Fair`, `Low`, `Out`, `Stale`, `Error`, coloured by severity |

It deliberately aggregates rather than showing one account. At a single cell of height there is
room for a glance, not a list, and the question this size answers is "do I need to think about
this right now?" Tapping anywhere opens the app.

### Detailed — header plus account cards (`DetailedUsageWidget`)

Declared at `targetCellWidth="4"`, `targetCellHeight="2"`, resizeable in both axes. A header
row carries the app name, "*N* accounts monitored", a freshness label (`Countdown.freshnessLabel`
with the leading "Updated " trimmed, so it reads "37m ago"), and a small circular refresh
button. Below it, one card per account, each showing the provider name and plan followed by up
to two rows — the five-hour window and the weekly (or monthly) one — where each row is
`label · bar · remaining % · reset countdown`.

At most three account cards are drawn, followed by a "+*N* more" line when there are more. A
Glance layout that overflows its host is silently clipped, so the cap plus the explicit count
is the honest version: the widget says what it is not showing instead of appearing to show
everything. With no accounts at all it renders "No accounts yet — tap to add one".

One inconsistency worth recording: `WidgetAccount` carries a `subtitle` holding the masked
e-mail (`m***@gmail.com`), and the detailed widget never renders it. Currently two accounts on
the same provider and plan are indistinguishable in the widget. Drawing the subtitle when it
is present is a two-line fix and should happen; it is listed here rather than quietly left in
the code.

All three widgets use `SizeMode.Responsive` with three declared sizes (250/320/420dp wide) rather
than `SizeMode.Exact`. Launcher grid cells differ enormously between a phone, a tablet and an
unfolded foldable; with `Responsive`, the launcher picks the nearest size the widget was
actually designed for instead of re-measuring one layout into a shape it never anticipated.

## The configuration model

Each placed widget can, in principle, show something different. `WidgetScope` names the four
possibilities:

- **`MOST_CRITICAL`** — every account, ordered worst-first, so whatever is closest to running
  out leads. The default and the fallback.
- **`ACCOUNT`** — one specific account, by `localId`.
- **`PROVIDER`** — every account of one provider, by provider id.
- **`ALL_ACCOUNTS`** — everything, in the user's account order.

`MOST_CRITICAL` and `ALL_ACCOUNTS` select the same set and differ only in ordering; that is the
whole distinction, and it matters because the detailed widget shows the first three.

The choice is persisted per widget in the Room table `widget_configs`, keyed by the
framework's `appWidgetId` and holding only `scope`, `accountId`, `provider`, `transparent` and
`updatedAt` — ids, a flag and enum names, nothing else. `transparent` drops the card background
so the wallpaper shows through, and arrived in `MIGRATION_5_6` with a column default matching
the entity's `@ColumnInfo(defaultValue = "0")`: Room compares the declared schema against the
one it finds, and a default stated in the ALTER but not on the entity fails validation at
launch. `WidgetUpdater.loadSnapshot` resolves the `GlanceId` to an
`appWidgetId`, reads the row, and passes the result to `WidgetDataBuilder.build`. Both the
scope name and the enum names are part of the on-disk contract, which is why `WidgetScope`
carries a "do not rename casually" note; `WidgetScope.fromName` maps anything unrecognised —
including `null` — to `MOST_CRITICAL` so a bad or missing row degrades to a useful widget
rather than an empty one.

**The configuration screen.** `WidgetConfigActivity` is declared as `android:configure` on
both providers, so the launcher opens it when a widget is dropped and again on reconfigure
(`android:widgetFeatures="reconfigurable"`). It receives `EXTRA_APPWIDGET_ID`, offers the four
scopes — "most important limits", "all accounts", one provider, one account — upserts a single
`WidgetConfigEntity` through `WidgetConfigDao`, repaints so the widget lands showing the chosen
scope, and returns `RESULT_OK` with the id.

The result is set to `RESULT_CANCELED` first and only flipped on an explicit choice. That is
the framework contract: backing out removes the widget rather than leaving an unconfigured one
on the home screen. The activity is not exported — only the system starts it, through the
`APPWIDGET_CONFIGURE` action.

This screen was missing for a while, and the cost was larger than "a setting you cannot
change": with no row ever written, `WidgetScope.fromName(null)` sent every widget down the
`MOST_CRITICAL` path, so `ACCOUNT` and `PROVIDER` were unreachable code and two widgets placed
to watch two different Codex accounts rendered identically.

## The data path, and why a token cannot reach the home screen

The complete chain for a widget render:

```
launcher asks for an update
  -> CompactUsageWidget/DetailedUsageWidget.provideGlance(context, glanceId)
     -> WidgetUpdater.loadSnapshot(context, glanceId)
        -> WidgetConfigDao.get(appWidgetId)          (scope + ids)
        -> UsageRepository.accountUsageOnce()        (accounts + latest snapshots)
        -> WidgetDataBuilder.build(...)              (pure function, no I/O)
     -> WidgetSnapshot  ->  Glance composition
```

Three properties make the "no token" claim structural rather than aspirational:

1. **The only data source is Room**, and Room by design holds no token — `AccountEntity`
   stores a `credentialReference` (a lookup name like `claude_9f2c…`), never a credential. Even
   a bug that dumped the entire widget input would expose usage numbers and a masked e-mail.
2. **`WidgetDataBuilder` is a pure function** over already-loaded `AccountUsage` values. It
   cannot fetch, cannot decrypt and cannot suspend on anything; a token has no route in.
3. **The `widget` package imports no credential or provider code.** Its complete set of
   in-project imports is `core.model.*`, `core.time.Countdown`, `core.database.AccountUsage`,
   `core.sync.SyncWorker`, plus `UsageLimitsApp` and `MainActivity`. Neither `core.auth` nor
   `providers` appears — one grep re-verifies it.

The output type reinforces this: `WidgetSnapshot`, `WidgetAccount` and `WidgetRow` have fields
for labels, percentages, reset times and severities, and no field a credential could occupy
even by accident.

The one caveat, stated because the guarantee should not be overclaimed: `WidgetUpdater` reaches
the graph by casting `context.applicationContext` to `UsageLimitsApp`, and `AppContainer`
exposes `credentialStore` and `providerRegistry` alongside `repository`. Nothing stops a future
edit in this package from touching them — the boundary is enforced by the import list and by
review, not by the compiler. Making it airtight would mean handing `WidgetUpdater` a narrow
read-only interface (`suspend fun accountUsageOnce()` plus the config DAO) instead of the whole
container; that is the right change if this package ever grows.

## Why the refresh button enqueues work instead of fetching

`RefreshWidgetAction` is an `ActionCallback` whose entire body is `SyncWorker.syncNow(context)`.
It does not call a provider, does not touch the credential store, and does not await a result.

The launcher is a bad place to do network work. An `ActionCallback` runs on a short budget in
a process the app does not own and cannot keep alive; a token refresh plus four provider
fetches over a slow mobile connection will not reliably finish inside it, and a half-completed
refresh is exactly the scenario that burns a rotated refresh token.

Routing through WorkManager buys four things at once. The pass obeys the same
`NetworkType.CONNECTED` constraint as every other sync, so tapping refresh with no signal
queues rather than fails. It goes through the same `SyncEngine`, so it inherits per-account
failure isolation and the per-credential refresh mutex — a widget tap racing the periodic pass
cannot double-spend a refresh token. It is enqueued as unique work with
`ExistingWorkPolicy.KEEP`, so an impatient user tapping five times gets one sync. And because
the worker calls `WidgetUpdater.refreshAll` after the cache moves, every placed widget updates
from one pass rather than each fetching for itself.

The visible cost is that the refresh is not instant and the button has no spinner: the widget
keeps showing the old numbers until the pass lands, then repaints. A progress state in the
header would be an improvement, and would need a small piece of transient state the widget can
read — which today would mean another Room write.

The reverse direction used to be broken and is worth recording: `WidgetUpdater.refreshAll` was
reachable only from `SyncWorker.doWork`, so refreshing inside the app updated the screens while
the home screen kept the previous numbers until the next background pass. Every foreground
path now calls it — the refresh button, a per-account refresh, a completed login, a spent reset
credit, and a removed account.

## Glance constraints that shaped the layout

Glance is not Compose. It emits `RemoteViews`, so there is no measurement, no custom drawing,
no arbitrary layout, and no access to the app's process at draw time. Four consequences are
visible in the code:

**The bars use Glance's own progress indicator.** `androidx.glance.appwidget.LinearProgressIndicator`
accepts a per-instance `color` and `backgroundColor` and fills whatever width its modifier
gives it, which is exactly what these bars need — the colour carries the severity and the
width has to follow the tile.

This was originally hand-rolled as two nested `Box`es with an explicit `maxWidth: Int` in dp,
on the mistaken belief that Glance had no tintable primitive. That was a real defect, not just
extra code: a compact tile is about 31dp wide at the 250dp size bucket, but the bar was told it
had 62dp. The fill was clipped at the tile edge, so **every window above roughly half remaining
painted as a completely full bar** — a 55 % quota looked identical to an untouched one. The
lesson is worth keeping: in a layout system that does not measure, a hardcoded width is a
silent wrong answer rather than a visible overflow.

**No fractional width measurement.** There is still no `fillMaxWidth(fraction)` a child can
resolve against a parent whose width Glance does not know at composition time — which is why
the fraction has to be handed to a primitive that resolves it at inflation, rather than
computed in the composable. Inside the detailed row the bar takes `defaultWeight()` so it
absorbs whatever the fixed-width label and percent columns leave.

**Glance cannot read the app's theme.** Composition happens for the launcher's process, so
`MaterialTheme` and the app's `UsageColors` are not reachable. The handful of colours the
widgets need are restated in a private object `W` at the top of `UsageWidgets.kt` — the same
warm-neutral surfaces and the same severity ramp (teal for an untouched window, green, amber,
red, slate for stale). This is duplication, and it is acknowledged as such: a palette change is
two edits, one in `ui/theme/Color.kt` and one in `W`. Keeping them in one object on each side is
what makes that a mechanical change rather than a hunt. The severity *mapping* is not
duplicated in any meaningful sense — both sides read the same `Severity` enum computed by the
same `Severity.fromRemainingPercent`, so the two surfaces cannot disagree about whether
something is low, only about the exact hex value used to say so.

**No scrolling and no overflow reporting.** A widget that draws past its host is clipped
without complaint, which is why the detailed widget caps at three accounts and states the
remainder in text. For the same reason the refresh control is a text glyph (`↻`) in a circular
box rather than a vector icon: it is one composable, it scales with the text system, and it
needs no drawable at a size Glance would have to be told about.

A fifth, smaller constraint drove `Countdown`: it is deliberately free of Android and locale
APIs, so "5d 2h" is formatted identically in the app process and in the launcher process. Both
widgets call `System.currentTimeMillis()` inline during composition, which is fine because a
countdown is only redrawn when the widget is updated — but it does mean the displayed countdown
is as old as the last update, not live.

## Adding a third size later

The reducer is size-agnostic, so a new size is mostly declaration:

1. Add a `GlanceAppWidget` subclass in `UsageWidgets.kt` with its own `SizeMode.Responsive` set,
   composing from the same `WidgetSnapshot`. Reuse `UsageBar`, `percentText` and the `W`
   palette; add a new selection helper to `WidgetDataBuilder` only if the size needs data the
   snapshot does not already carry (for example a per-provider grouping, or more than two rows
   per account — both would be new headline selectors, not a new data path).
2. Add a matching `GlanceAppWidgetReceiver` subclass.
3. Add `res/xml/widget_<name>_info.xml` with `minWidth`/`minHeight`, `minResizeWidth`/
   `minResizeHeight`, `targetCellWidth`/`targetCellHeight`, `resizeMode`, a
   `description` string, and `initialLayout="@layout/glance_default_loading_layout"`.
4. Add the description to `res/values/strings.xml` and register the receiver in
   `AndroidManifest.xml` with the `APPWIDGET_UPDATE` intent filter and the provider metadata.
5. **Add the new widget class to `WidgetUpdater.refreshAll`** — it names each widget class
   explicitly, so a size omitted there will render once when placed and then never update
   again. This is the one step that fails silently, so it is the one to check first when a new
   widget looks stale.

Nothing else needs to change: the configuration table is keyed by `appWidgetId` and is
class-agnostic, and `loadSnapshot` works for any `GlanceId`.

## The iOS widgets

WidgetKit is a different set of constraints from Glance, and two of them decide the shape of
everything below.

**A widget cannot scroll and cannot tick.** There is no `LazyColumn` on a home screen: a tile
renders a fixed view at dates the system picks. So the Android answer to "show me eight
accounts" — a scrolling 1×4 — has no iOS twin, and the equivalent is a taller tile. And nothing
may be phrased relative to "now": every time the tiles show is absolute ("Resets 14:05", "As of
12:40"), because a countdown frozen at the last render goes wrong in the one direction that
matters — the limit can have reset while the tile still insists "in 20m".

**A widget extension is a separate process, and this one holds no credentials.** It reads the
JSON snapshot the app writes into the App Group container and nothing else. That is why the
refresh control is a link into the app rather than a fetch, and why it is not an iOS 17
`Button(intent:)` that reloads the timeline: reloading the timeline redraws the same cached
numbers, which looks like a refresh that changed nothing.

Three entries appear in the gallery, all fed by one `UsageProvider` and one snapshot:

| Entry | Families | What it shows |
|---|---|---|
| **Usage Limits** | small, medium, large, `accessoryRectangular` | The kit's ranking: one account small, three medium, up to eight large |
| **Usage Limits (Clear)** | small, medium, large | The same, with the wallpaper showing through |
| **Usage Ring** | small, `accessoryCircular` | One account as a ring, with its limit and reset inside |

### Why transparency is a second entry rather than a switch

Android puts a "transparent background" checkbox in its configuration activity. iOS has no
equivalent that this app can reach: a per-tile option requires a *configurable* widget, which
means `AppIntentConfiguration` and iOS 17, where the app's deployment target is iOS 16. A
second gallery entry costs the user one extra row when placing a tile and works on every
version the app runs on; a checkbox would work for part of the installed base and be invisible
to the rest.

The same reasoning applies to per-widget account selection, which Android offers through
`WidgetConfigActivity`. On iOS the large tile shows every account instead, and the ring shows
the one the kit ranks first — the selection a user placing a small tile almost always wants.

On iOS 17 "transparent" means an explicitly CLEAR container background, not the absence of one:
a widget that declares no `containerBackground` is rejected from the Home Screen outright.
`widgetBackground(_:)` takes an optional colour and passes `Color.clear` for nil, so both
generations of chrome are handled in one place.

### The scheme the links needed

`widgetURL(DeepLink.glance)` had shipped against `usagelimits://`, a scheme no bundle claimed —
so the system could not route it and tapping a tile did nothing at all. The scheme is now
declared in the app's `CFBundleURLTypes` and answered in `onOpenURL`: `//glance` selects the
Overview tab, `//refresh` selects it and fetches. An unrecognised URL is ignored rather than
treated as a refresh, because a link this build does not know is a link from a later one.

The refresh glyph is a `Link` rather than the tile-wide `widgetURL`, so one glyph opens the app
on a refresh while the rest of the tile opens the overview. The system honours `Link` on medium
and large only — which is why the small tile has no such button rather than a decorative one
that does nothing.

## Refresh motion

WidgetKit bars and rings use short value transitions, with numeric text transitions on iOS 17
and later. `accessibilityReduceMotion` suppresses them. These run only when WidgetKit displays
a changed timeline entry; no timer, network request or extra reload is scheduled. Glance keeps
its static rendering because it does not expose Compose animation in RemoteViews. Ring and
minimal widgets retain their transparent backgrounds; detailed cards retain the app palette.
