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

## The two sizes

### Compact — one row, four tiles (`CompactUsageWidget`)

Declared at `targetCellWidth="4"`, `targetCellHeight="1"`, resizeable horizontally down to
180dp. Four equal tiles across a rounded card:

| Tile | Shows |
|---|---|
| **5h limit** | The tightest five-hour window across every account in scope, as remaining percent, with a bar |
| **Weekly** | The tightest weekly window, with a bar — falling back to the tightest monthly window when a plan has no weekly one |
| **Resets in** | A countdown to the *earliest* upcoming reset anywhere in scope |
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

Both widgets use `SizeMode.Responsive` with three declared sizes (250/320/420dp wide) rather
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
framework's `appWidgetId` and holding only `scope`, `accountId`, `provider` and `updatedAt` —
ids and enum names, nothing else. `WidgetUpdater.loadSnapshot` resolves the `GlanceId` to an
`appWidgetId`, reads the row, and passes the result to `WidgetDataBuilder.build`. Both the
scope name and the enum names are part of the on-disk contract, which is why `WidgetScope`
carries a "do not rename casually" note; `WidgetScope.fromName` maps anything unrecognised —
including `null` — to `MOST_CRITICAL` so a bad or missing row degrades to a useful widget
rather than an empty one.

**Honest status: there is no configuration UI yet.** No activity is declared with
`android:configure` in either `widget_compact_info.xml` or `widget_detailed_info.xml`, and
nothing in the app writes a `WidgetConfigEntity` — the table is only ever read. Every placed
widget therefore takes the `null` path and renders `MOST_CRITICAL`. The storage, the reducer
and the fallback are all finished and exercised by the read path; what is missing is the
screen. Adding it means a small configuration activity that receives
`EXTRA_APPWIDGET_ID`, offers the four scopes plus an account or provider picker, upserts one
row through `WidgetConfigDao`, calls `updateAll` for that widget class, and returns
`RESULT_OK` with the id — plus `android:configure` in the provider XML. The reducer needs no
change at all.

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

## Glance constraints that shaped the layout

Glance is not Compose. It emits `RemoteViews`, so there is no measurement, no custom drawing,
no arbitrary layout, and no access to the app's process at draw time. Four consequences are
visible in the code:

**No tintable progress primitive.** Glance's progress indicator cannot be tinted per instance
in a way that works across launchers and API levels, and the whole point of these bars is that
their colour carries the severity. So `UsageBar` is two nested `Box`es: a track with a
`cornerRadius`, and inside it a filled box with the severity colour.

**No fractional width measurement.** There is no `fillMaxWidth(fraction)` a child can resolve
against a parent whose width Glance does not know at composition time. `UsageBar` therefore
takes an explicit `maxWidth: Int` in dp — 62 inside a compact tile, 88 inside a detailed row —
and computes `filled = maxWidth * fraction`, with a 4dp floor so a small non-zero value still
shows a sliver instead of vanishing. Those two constants are the reason the row widths
(52dp label, 42dp percent) are fixed too: everything in the row has to add up to something that
fits without measurement.

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
