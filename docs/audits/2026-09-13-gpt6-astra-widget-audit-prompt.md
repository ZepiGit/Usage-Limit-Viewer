# Deep Research Audit Prompt — Widget Defect Root-Cause Analysis (UsageLimitViewer)

Copy everything below this line into GPT-6-Astra as the research brief.

---

You are a deep-research auditor. Your mission: find the **root causes** of the current home-screen widget defects in **UsageLimitViewer** and produce a report that an implementing engineer can act on **directly — without re-doing any of your research**. You investigate; you do not fix, and you do not modify any files.

## 1. Environment

- Repository: `C:\Users\miche\Desktop\UsageLimitViewer` — local checkout of `ZepiGit/Usage-Limit-Viewer`. Confirm the audit pin with `git rev-parse HEAD`; it should be `1cb94c2` on `main`. State the exact hash you audited in the report.
- Dual-platform app that tracks AI-provider usage limits (Claude, Codex, Kimi, Antigravity): **Android** (Kotlin, Jetpack Glance widgets, Room, WorkManager) and **iOS** (SwiftUI, WidgetKit, App Intents, SwiftPM packages `UsageLimitsKit`).
- The main app works. **Only the widget layer is in scope.**
- Rules: read-only audit. No file changes, no builds, no live provider calls. Static analysis plus research against official framework documentation.

## 2. Reported symptoms (from the user, 2026-09-13)

Verbatim (German): *"Widgets verändern random welches Widget angezeigt wird, Schrift switcht von schwarz auf weiß und weitere Bugs."*

Interpreted as three symptoms:

- **S1** — Placed widgets randomly change **what** they display: content that does not match the widget's configured scope/account/preset, or that appears to swap between widgets. No exact repro steps were observed, so your report must state the trigger conditions for every finding.
- **S2** — Widget **text flips between dark (black) and light (white) ink**.
- **S3** — "other bugs": an open sweep of the widget layer for additional material defects (stale content, unreadable contrast, layout jumps, missing refresh, misrouted taps, crashes). Report only defects that materially mislead or annoy a user, not style nits.

## 3. Architecture map (verified — use this as your entry points)

### Android widget stack (Jetpack Glance; per-instance config in Room)

- `app/src/main/kotlin/com/usagelimits/widget/UsageWidgets.kt` — `CompactUsageWidget` (1×4 tiles), `DetailedUsageWidget` (2×4 account cards), `LocalWidgetStyle` composition local.
- `app/src/main/kotlin/com/usagelimits/widget/RingWidgets.kt` — `MinimalUsageWidget` / `MiniRingsWidget`, both subclassing the shared base `AccountRingsWidget(mini: Boolean)`.
- `app/src/main/kotlin/com/usagelimits/widget/WidgetUpdater.kt` — the only bridge: `observe(context, glanceId)` maps `GlanceId → appWidgetId` via `GlanceAppWidgetManager`, then `combine`s three flows — `repository.observeAccountUsage()`, `widgetConfigDao.observe(appWidgetId)`, `settingsStore.settings` — into a `WidgetView(snapshot, style, metrics)`. `refreshAll()` calls `updateAll` on each of the four widget kinds; no `updatePeriodMillis` anywhere (updates are sync-driven).
- `app/src/main/kotlin/com/usagelimits/widget/WidgetStyle.kt` — ink derivation: panel ARGB + opacity stored per widget; `lightInk = AUTO → (transparent || !isLightColor)` with `luminance() > 0.4` threshold, overridable via `WidgetTextTone` LIGHT/DARK.
- `app/src/main/kotlin/com/usagelimits/widget/WidgetData.kt` — `WidgetDataBuilder.build(...)` selects rows by `WidgetScope.fromName(config?.scope)`, optional `accountId`/`provider`, custom account ids; staleness from `Severity.staleAfterMs(settings.syncIntervalMinutes)`.
- `app/src/main/kotlin/com/usagelimits/widget/WidgetConfigActivity.kt` — per-widget config UI; reads/writes `widgetConfigDao` keyed by the launcher's `appWidgetId` (`EXTRA_APPWIDGET_ID`).
- Config entity/DAO: `core/database/Entities.kt`, `Daos.kt` (`WidgetConfigEntity`, `WidgetConfigDao`); scope model: `core/model/WidgetScope.kt`; metrics: `widget/WidgetMetrics.kt` (`layoutMetricsJson`).
- Manifest: four receivers (`CompactUsageWidgetReceiver`, `DetailedUsageWidgetReceiver`, `MinimalUsageWidgetReceiver`, `MiniRingsWidgetReceiver`), all four `res/xml/widget_*_info.xml` declare `android:configure="com.usagelimits.widget.WidgetConfigActivity"`.
- Docs of record: `docs/widgets.md`, `docs/widget-polish-progress.md`. Tests: `app/src/test/kotlin/com/usagelimits/widget/` (e.g. `WidgetStyleTest`, `WidgetRefreshCoverageTest`, `WidgetSelectionContractTest`, `WidgetUpgradeTest`, `WidgetDataBuilderTest`, `WidgetMetricsTest`).

### iOS widget stack (WidgetKit)

- `ios/UsageLimits/Sources/Widget/UsageWidget.swift` (~1070 lines) — `UsageProvider: TimelineProvider` plus static widgets `UsageWidget`, `UsageClearWidget`, `UsageRingWidget`; per-family views including lock-screen accessories. Note it mixes fixed-palette `UsageColors.*` foregrounds with SwiftUI's appearance-dependent `.secondary` (e.g. around lines 634/641/653).
- `ios/UsageLimits/Sources/Widget/ConfiguredWidgets.swift` — `ConfiguredUsageProvider: AppIntentTimelineProvider` with `UsageWidgetIntent` (account/preset selection), `WidgetAccountQuery` / `WidgetPresetQuery` entity queries, `ConfiguredUsageWidget` / `ConfiguredRingWidget`.
- `ios/UsageLimits/Sources/Screens/WidgetPresetsScreen.swift` — preset management; the only place calling `WidgetCenter.shared.reloadAllTimelines()`.
- `ios/UsageLimitsKit/Sources/UsageLimitsKit/Presentation/` — `GlanceModel.swift`, `GlanceSnapshotCodec.swift`, `WidgetSelection.swift` (snapshot the widgets render). Palette: `ios/UsageLimits/Sources/Theme/UsageTheme.swift`.
- Tests: `WidgetPresetStoreTests.swift`, `WidgetSelectionContractTests.swift`.

## 4. Scope and priorities

- **P0** — root causes of S1 and S2, on each platform. For each platform state explicitly whether the symptom can occur there and why.
- **P1** — S3 sweep of the widget layer on both platforms.
- Out of scope: main-app screens, parsers, auth — except where widget rendering depends on them (snapshot construction, severity mapping, sync cadence that feeds widget updates).

## 5. Known items — do NOT re-report

- Prior audit findings **ULV-001…ULV-009** (2026-09-13; quota parsing/sync correctness — e.g. Codex unknown-window drop, iOS cancellation classified as transport failure, Kimi 5h default). Known.
- The fixed regressions listed in `docs/release-readiness.md` and historical bugs already documented in code comments — e.g. ring widget once missing from `WidgetUpdater.allWidgets`, a single `runCatching` collapsing `refreshAll`, hardcoded "Weekly" label, clipped hand-rolled bars, white refresh arrow on light panels, the pre-migration transparent flag.
- Re-report any of these **only** with evidence they are still broken at the pinned commit.

## 6. Seeded hypotheses — verify or refute each (V / R / P verdict with evidence); do not treat them as conclusions, and find causes beyond them

Android:

- **H1 — Config-race fallback (S1).** `combine` in `WidgetUpdater.observe` can emit with `config == null` before the Room row exists (or if it never does) → `WidgetScope.fromName(null)` falls back to auto = "most critical" account → a placed, configured widget renders unconfigured content that later corrects itself. Check `WidgetConfigDao` semantics, when rows are created (`WidgetConfigActivity`), and what happens when a user backs out of configuration.
- **H2 — appWidgetId lifecycle (S1).** Config is keyed by launcher `appWidgetId`; after reboot, launcher change, or backup/restore, IDs are reassigned and stored rows orphaned → widgets silently fall back to auto scope. Check for any re-binding or migration logic.
- **H3 — Shared-base cross-wiring (S1).** `MinimalUsageWidget` / `MiniRingsWidget` share `AccountRingsWidget(mini:)`; verify Glance's per-instance vs per-kind state handling cannot cross-wire `updateAll`/config between the two kinds or between instances of one kind.
- **H4 — Ink-flip first emission (S2).** `WidgetUpdater.load(...).first()` may return the default `WidgetStyle` (dark charcoal panel → white ink) when config isn't loaded yet; the corrected emission then flips text to dark on a light panel → visible black↔white flip on update. Also check `textTone` persistence across reconfiguration, and the transparent-panel AUTO assumption (dark wallpaper) against light wallpapers.
- **H5 — Severity-driven color changes (S2).** `textColor(severity)` / `Severity.STALE` mapping can change ink color when the staleness boundary (`Severity.staleAfterMs`) is crossed between emissions. Confirm or deny as a contributor.

iOS:

- **H6 — Mixed color sources (S2).** Fixed `UsageColors` ink vs appearance-dependent `.secondary` in the same views → a system dark-mode/light-mode toggle (or per-widget appearance) flips parts of the text while the rest stays. Check whether widgets pin a color scheme, use `containerBackground(for: .widget)` variants, or read the environment appearance.
- **H7 — Timeline/preset mutability and reload coverage (S1).** If `getTimeline` / `timeline(for:)` entries read shared mutable preset state at render time, two configured widgets can render each other's preset; also assess reload coverage (only `WidgetPresetsScreen` calls `reloadAllTimelines()`) and whether App-Intent config changes reliably re-render the widget.
- **H8 — Entity-query fallbacks (S1).** `WidgetAccountQuery` / `WidgetPresetQuery` returning a default or "most critical" entity when the stored id no longer resolves → a configured widget silently shows another account.

## 7. Framework-behavior research (this is where deep research earns its keep)

Several symptoms likely stem from framework semantics rather than app code. Every framework claim must cite official documentation (developer.android.com for Glance / WorkManager / app widgets; developer.apple.com for WidgetKit / App Intents) with a URL, or be explicitly labeled "inference". Specifically establish:

- **Glance**: when `provideGlance` runs and how long the composition lives; whether `collectAsState` updates propagate to the rendered RemoteViews after composition ends; per-instance vs per-kind state; `SizeMode.Exact` interaction with `updateAll`; and what the launcher shows when `provideGlance` suspends indefinitely (e.g. `WidgetUpdater.observe` when `applicationContext as? UsageLimitsApp` returns null — the flow then never emits).
- **WidgetKit**: timeline entry lifetime and reload budgeting; the re-render guarantee after an App-Intent configuration change; when `AppEntity` queries are resolved; appearance (dark mode) handling and `containerBackground` requirements; lock-screen accessory rendering constraints.

Distinguish clearly in every finding: **app bug** vs **framework limitation** vs **fixable usage pattern**.

## 8. Required report format

English, Markdown, one document. Structure:

1. **Executive summary** — ≤10 lines; the causal chains behind S1 and S2 in one or two sentences each.
2. **Symptom traceability matrix** — one row per symptom per platform: which finding IDs explain it, whether fully or partially, and what remains unexplained. This matrix is how the implementer verifies a fix actually kills the symptom, so make it complete.
3. **Findings**, sorted P0 → P3, each with this template:
   - **ID**: `WGT-001`, `WGT-002`, …
   - **Platform**: Android / iOS / both — **Severity**: P0 (core symptom) … P3 (polish) — **Confidence**: high / medium / low
   - **Symptom(s) explained**: S1 / S2 / S3 …
   - **Root cause (mechanism)**: the causal chain step by step, not "bad code".
   - **Evidence**: `file:line` with short excerpts; framework claims cited with URLs.
   - **Trigger conditions**: when a user sees it (and why it may look random).
   - **Recommended fix**: a minimal-change option and, where different, a proper fix — scoped to named functions/files. "Rewrite the widget layer" is not a recommendation.
   - **Regression risk**: what the fix could break; existing tests it interacts with.
   - **Test to add or extend**: map to the existing test files named in §3; give the case name and assertion.
4. **Implementation order** — if findings interact, the order to fix them in, and which are independent.
5. **Rejected hypotheses** — each seeded hypothesis with verdict V/R/P and one paragraph of justification.
6. **Deliberately not re-reported** — the known items from §5 you checked and confirmed as fixed/known.
7. **Open questions** — anything a device log, runtime trace, or user repro would decide, with the exact log/trace to capture.

## 9. Bar for a finding

A finding without `file:line` evidence or without stated trigger conditions is not a finding. Rank causes by likelihood-weighted impact on S1/S2. If the same symptom has multiple contributing causes, list all and say which dominates. End every finding with a one-sentence "so what" the implementer can act on.
