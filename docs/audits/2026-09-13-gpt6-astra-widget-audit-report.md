# UsageLimitViewer — Widget Root-Cause Audit and Optimized Remediation Plan

Audit date: 13 September 2026
Auditor: GPT-6-Astra (external deep-research run; prompt: `2026-09-13-gpt6-astra-widget-audit-prompt.md`)
Audited repository: ZepiGit/Usage-Limit-Viewer
Exact audit pin: 1cb94c205b87e651a7b5629d469706c66d65923f
Method: Read-only inspection of pinned GitHub source, existing tests, and official framework references. No builds, tests, provider requests, or repository modifications were performed. Only this report was generated outside the repository.

The Windows checkout named in the brief was not accessible from that session. Consequently, `git rev-parse HEAD` was not executed there. GitHub resolved 1cb94c2 to the hash above, and refs/heads/main pointed to that same hash when checked. This establishes the remote source audited, not the revision of an APK/IPA installed on the reporting device.

Priority follows the brief: P0 means an explanation of a core reported symptom, not a security incident or proof that every user experiences it. Confidence describes the evidence for the stated mechanism; no device reproduction is claimed. Proposed tests have not been executed.

## 1. Executive summary

S1, Android: Saved MOST_CRITICAL configuration is explicitly reinterpreted as CLOSEST_RESETS; account leadership can therefore change without a configuration write. An exact ACCOUNT selection does not exhibit an evidenced fallback to another account. WGT-001

S1, iOS: Legacy widgets consume the new all-accounts export without restoring their documented critical-account ordering. Configurable widgets select exact IDs, but repeatedly reading inputs while constructing a timeline can mix generations; missing selections become misleading empty states. WGT-002 · WGT-008 · WGT-010

S2: Android has a deterministic AUTO-tone discontinuity at zero opacity and no wallpaper-aware contrast calculation. iOS host rendering modes can legitimately recolor widgets; opaque track/fill colors do not preserve quota legibility in monochrome rendering. Spontaneous repeated black/white flipping with unchanged configuration and host appearance is not established. WGT-003 · WGT-004

S3: Clock-driven freshness, freshness-color precedence, reset-state retention, timestamp ownership, and the Android compact minimum-size layout need correction. WGT-005–WGT-007 · WGT-009 · WGT-011

Do not start by replacing Glance, splitting the ring base class, or adding an allegedly missing iOS reload call: those remedies do not match the inspected code. The project already declares Glance 1.2.0, and the iOS container already reloads timelines after snapshot publication.

## 2. Symptom traceability matrix

| Platform | Symptom | Explanations and coverage | What remains unexplained |
|---|---|---|---|
| Android | S1: displayed content changes | Partial: WGT-001 preserves the stored string but changes its meaning. Closest-reset ordering legitimately changes when future reset ordering changes. Compact tiles omit the identity of their leading account. | No evidence that two valid ACCOUNT configurations exchange IDs, or that one receiver changes into another kind. Need installed-version and before/after instance/configuration evidence. |
| iOS | S1 | Partial: WGT-002 explains legacy focus following overview order; WGT-008 is a conditional mixed-generation timeline risk; WGT-010 explains content disappearing into an incorrect empty state. Dynamic closest-reset ordering and shared-preset edits are intentional. | No demonstrated cross-instance preset-reference corruption. Whole-widget changes could be a host stack transition; placement type and a recording are needed. |
| Android | S2: dark/light text changes | Partial: WGT-003 reproduces the AUTO discontinuity when a light panel crosses zero opacity. A genuinely missing configuration also selects the default palette. | An existing committed configuration is not shown to emit a synthetic null/default first frame. Repeated flipping without configuration changes requires host/render traces. |
| iOS | S2 | Partial: WGT-004 explains system recoloring and lost contrast; WGT-003 explains fixed light ink on clear backgrounds. | The seeded claim that Home Screen text mixes `.secondary` with fixed ink at the cited lines is false: those lines are in a Lock Screen accessory. An ordinary appearance toggle alone is not proved to switch the fixed Home Screen palette between black and white. |
| Android | S3 | WGT-003, WGT-005, WGT-006, WGT-011: contrast, offline stale presentation, contradictory status colors, and unreadable narrow layouts. | OEM-specific resize/collection behavior, action routing under real launcher reuse, and exact device font/size clipping need runtime checks. |
| iOS | S3 | WGT-003–WGT-010: contrast, monochrome tracks, stale timelines, misleading colors/resets, mixed input generations, wrong freshness attribution, and empty-state errors. | Extension termination, refresh latency under system budgeting, and older-OS small-widget link behavior were not reproduced. |

## 3. Findings

### WGT-001 — Android silently reinterprets a persisted selection policy

Platform: Android · Priority: P0 · Confidence: High for the mechanism; whether the product intended this migration is a policy question.
Symptoms: S1 · Classification: Application compatibility/selection-policy regression, not a Glance race.

Root cause and evidence. `WidgetScope.kt:19–23` maps the persisted name MOST_CRITICAL to CLOSEST_RESETS; it also defaults null and unknown names to CLOSEST_RESETS. `WidgetUpdater.observe` calls this decoder before building the widget. `WidgetData.kt:97–115` implements materially different ordering for criticality and future resets. The behavior is deliberate enough that `WidgetSelectionContractTest.kt:34–42` explicitly expects the alias. Thus neither a database write nor corruption is required for a saved policy to change meaning.

Trigger conditions. A pre-existing row contains scope="MOST_CRITICAL". Account A is exhausted but resets later; healthy account B resets sooner. At this pin, B leads. Following subsequent reset boundaries, the leading account can change again. On the compact widget, `UsageWidgets.kt:143–199` displays percentages/reset/status but no leading-account name, making a legitimate leadership change look like unrelated content replacement.

Minimal fix. In `WidgetScope.fromName`, return MOST_CRITICAL for that exact persisted name. Keep CLOSEST_RESETS as a separate explicit choice and, if desired, the new-install default. Restore a visible legacy/critical choice in WidgetConfigScreen so an existing value is not an invisible setting.

Optimized fix. Introduce a selection resolver that distinguishes Configured, MissingConfiguration, and UnsupportedScope. Apply defaults only during explicit creation, not as repair for an existing widget whose row is missing. Keep future-reset sorting and urgency sorting independent; retain stable user order for ties. Add a compact focus label when more than one account can supply the headline.

Regression risk. Existing tests codify the alias and must change intentionally. Rows already explicitly saved as CLOSEST_RESETS cannot safely be inferred to have originated from the old policy; do not mass-convert them.

Tests. Extend WidgetSelectionContractTest: legacyMostCriticalNameRetainsUrgencySemantics must lead with A; explicitClosestResetsStillLeadsWithSoonestReset must lead with B; accountSelectionSurvivesOtherAccountResetBoundary must remain on the chosen ID.

So what: Preserve the meaning of stored scope names before attempting race fixes.

### WGT-002 — iOS legacy widgets inherit overview order instead of their stated focus policy

Platform: iOS · Priority: P0 · Confidence: High for the data path; medium that critical ordering remains the intended legacy product contract.
Symptoms: S1 · Classification: Application contract drift.

Root cause and evidence. `UsageLimitsContainer.swift:506–531` publishes `GlanceModel.build(... scope: .allAccounts ...)`. `UsageWidget.swift:61–77` returns that snapshot directly for legacy snapshots and timelines. `WidgetFocus` in `UsageWidget.swift:182–217` takes `accounts.first`. Yet the small-widget description in `UsageWidget.swift:436–442` describes a critical-account focus. Legacy `UsageWidget`, `UsageClearWidget`, and `UsageRingWidget` remain registered in `UsageWidget.swift:967–1100`. There is no final legacy selection step between the all-account export and those views.

Trigger conditions. A legacy/static widget is placed, multiple accounts exist, and overview ordering differs from urgency ordering. Reordering the overview changes its displayed lead after publication, even though no widget edit occurs. An account-specific configurable widget is a different path and is not implicated.

Minimal fix. Either restore a critical-order selection pass in UsageProvider or explicitly document and label the legacy widgets as overview-order summaries. The former preserves the source's stated behavior.

Optimized fix. Give each legacy kind an explicit SelectionPolicy, and route both legacy and configurable providers through one pure selector. Do not simply call the current `snapshot.selecting(scope: .mostCritical)`: `WidgetSelection.swift:15–20` currently treats .mostCritical and .closestResets identically. Implement distinct urgency and reset comparators first, with stable ties, without changing the all-accounts export needed by configurable widgets.

Regression risk. Changing the shared export back to critical order would break the documented "All accounts follows Overview" behavior. Fix the consuming legacy provider, not the export's order.

Tests. Extend WidgetSelectionContractTests with testSelectingMostCriticalDoesNotUseResetOrder, testLegacyProviderSelectsCriticalAccountFromAllAccountsExport, and testConfiguredAllAccountsPreservesOverviewOrder. Use three different orders for overview, urgency, and reset time.

So what: Make the legacy widget's focus policy explicit instead of inheriting whichever order the shared exporter currently uses.

### WGT-003 — Transparent backgrounds have an unsafe contrast contract

Platform: Both · Priority: P0 · Confidence: High.
Symptoms: S2 partially; S3 · Classification: Application contrast-policy defect with an explicit wallpaper-information limitation.

Root cause and evidence. `WidgetStyle.kt:17–80` computes luminance from the uncomposited panel color, intentionally ignoring opacity. AUTO becomes light ink whenever opacity reaches zero. A white panel at positive opacity therefore selects dark ink, while the same panel at zero selects light ink. At very low positive opacity on a dark wallpaper, dark ink may be nearly invisible. A fully transparent panel on a light wallpaper has the inverse problem.

The iOS Home Screen views use fixed light `UsageColors.textPrimary/textSecondary` from `UsageTheme.swift:12–21` even when their background is clear: `UsageWidget.swift:450–511`, `UsageWidget.swift:702–758`, and `ConfiguredWidgets.swift:145–187`. Its configuration provides transparency but no corresponding text-tone choice.

Trigger conditions. Android: choose a light panel, AUTO, and change opacity across zero; or place a low-opacity widget over a wallpaper of the opposite brightness. iOS: use a clear widget over a bright region in full-color rendering. These are deterministic appearance conditions, not evidence of random stored-style mutation.

Minimal fix. Keep Android LIGHT/DARK overrides intact and expose equally explicit tone choices for transparent iOS widgets. Make the Android AUTO rule continuous at zero by basing it on a chosen backdrop assumption, not a special isTransparent branch. Explain that assumption in the configuration UI.

Optimized fix. Support two contracts: opaque AUTO can compute contrast against a known surface; see-through mode needs an explicit backdrop/tone choice or a protective local scrim. Resolve primary, secondary, and severity text against the same effective surface. Do not claim to infer the wallpaper behind a widget from the system light/dark setting. Do not sample private wallpaper content.

Regression risk. Changing AUTO alters the appearance of existing clear widgets. Preserve explicit LIGHT/DARK rows exactly; version or explicitly communicate the default-policy migration.

Tests. Extend WidgetStyleTest with autoToneDoesNotDiscontinuouslyFlipAtZeroOpacity, explicit-tone round trips at 0/5/50/100 opacity, and contrast checks over defined black/white backdrops. Add corresponding iOS palette tests and host screenshots for full-color clear widgets.

So what: Treat transparency as a contrast decision, not merely an alpha slider.

### WGT-004 — iOS accented rendering can collapse track and fill into the same ink

Platform: iOS · Priority: P0 · Confidence: High for the framework mechanism and equal-opacity source inputs; medium for the exact appearance on the user's unobserved host.
Symptoms: S2 partially; S3 · Classification: Expected framework recoloring plus a fixable widget-rendering pattern.

Root cause and evidence. `ConfiguredWidgets.swift:160–170` draws an opaque full-circle track and an opaque colored arc. The legacy ring in `UsageWidget.swift:678–695` and bars in `UsageWidget.swift:298–309` similarly distinguish track and fill primarily through RGB colors. There is no rendering-mode-specific track treatment in the inspected widget views.

Apple's current accented/Liquid Glass guidance states that tinted/clear host appearances can replace backgrounds and recolor primary/accented content white while preserving opacity. Inference from those rules: two opaque shape layers distinguished only by their original color can become indistinguishable, making a partly filled ring appear full. The host-driven recoloring itself is not application configuration corruption.

Trigger conditions. The widget is displayed in an accented/monochrome host mode rather than ordinary full color. This must be distinguished from a simple system light/dark toggle and verified against the device's selected Home Screen appearance.

Minimal fix. In bar/ring components, give the track a genuinely lower opacity than the fill in monochrome modes. Do not rely on assigning the two layers to different accent groups alone: that is insufficient where both groups render white.

Optimized fix. Add a widget-local appearance resolver using widgetRenderingMode, with availability-safe handling. Preserve quota amount through geometry and alpha; preserve stale/error/reconnect status through text or a distinct status mark, not hue alone. Configure provider-image treatment deliberately. Retain a separate full-color palette and the system-appropriate accessory presentation.

Regression risk. A global forced `.dark` scheme does not solve accented rendering and can damage accessory behavior. Avoid changes to the app-wide theme to fix a widget-host issue.

Tests. Add Apple-SDK rendering tests/previews for 0/3/50/100 percent in full-color, accented, and accessory contexts. Assert visibly distinct track/fill and a non-color stale/error cue. Confirm the same cases in SpringBoard; pure package tests cannot validate host tinting.

So what: Make fill amount and status survive the host removing your original colors.

### WGT-005 — Freshness does not have a complete clock-driven update path

Platform: Both · Priority: P1 · Confidence: High.
Symptoms: S3; delayed visible changes can contribute to the perception of S1/S2 · Classification: Fixable scheduling/data-age pattern, within framework timing limits.

Root cause and evidence. Android's `WidgetUpdater.kt:77–104` evaluates `System.currentTimeMillis()` only when its three input flows emit. Time alone is not an input. `SyncWorker.kt:57–99` constrains both periodic and manual sync to network connectivity. The compact provider has no periodic widget update declared. Once no other event causes a render, crossing a freshness boundary does not invalidate the displayed state.

On iOS, `UsageWidget.swift:68–119` creates legacy entry dates around nextResetAt, not each visible account's fetchedAt + staleAfter. Views compute age at entry.date, not at the time a person happens to look. With no nearby reset and no new publication, the cached verdict can persist until the next requested routine reload. Configurable timelines stop their regular entries at one hour; they also do not guarantee an entry at a later stale boundary.

Glance lifecycle guidance describes a bounded composition session, not a permanent observer. WorkManager can defer constrained work. WidgetKit lets the system choose reload timing. These limitations do not justify omitting cache-only age transitions.

Trigger conditions. Leave a healthy widget visible, prevent new data/config/settings emissions, and cross the stale threshold—especially offline on Android or with delayed app/background refresh on iOS.

Minimal fix. Add cache-only Android repaint work without a network constraint, and schedule iOS entries at relevant stale boundaries. Display a correctly owned absolute fetch timestamp so a delayed transition does not imply fresh data.

Optimized fix. Create a pure nextPresentationBoundary calculation. Coalesce Android boundaries across placed widgets into one unique, reschedulable work request, with a conservative periodic fallback only while widgets exist. On iOS, include age/reset boundary entries and a terminal aged state from the immutable cache, independently of the requested reload date. Neither approach should make provider calls or use an always-running timer. Exact deadlines remain unguaranteed.

Regression risk. Per-widget polling can waste battery; removing network constraints from the provider-sync worker would be the wrong fix.

Tests. Extend WidgetRefreshCoverageTest for cache-only refresh coverage. Add WidgetTemporalStateTest and WidgetTimelinePlanningTests: at staleAt−1, staleAt, and staleAt+1, the presentation changes correctly; offline repaint invokes no provider; a delayed reload does not leave every supplied future entry healthy.

So what: Separate "fetch new usage" from "stop presenting cached usage as current."

### WGT-006 — Freshness warnings and quota colors can contradict each other

Platform: Both · Priority: P1 · Confidence: High.
Symptoms: S3; severity recoloring is not a primary-ink black/white switch · Classification: Application presentation precedence defect.

Root cause and evidence. In `UsageWidgets.kt:328–363`, a stale/error warning is based on account state, but percentages use `style.barText(row)` and the bar uses the row's quota-only severity. `WidgetStyle.kt:106–114` gives nearly full rows a teal override before considering any validity state. Thus an out-of-date account can still show reassuring quota colors beneath its warning.

The iOS views pass an aged severity to their bar/ring, but `UsageTheme.swift:72–76` returns teal for remaining >= 99.5 before examining severity. A stale or error account with a cached full quota therefore remains teal. This is a current residual defect despite historical comments explaining earlier stale-color fixes.

Trigger conditions. Android: render a stale/error account retaining readable rows. iOS: render any aged/error account whose displayed remaining percentage is at least 99.5. A successful redraw does not repair this, because the precedence is wrong within the redraw itself.

Minimal fix. Derive an effective row presentation state before calling the color functions. Freshness/error/reconnect validity overrides must be evaluated before healthy/full-quota decoration.

Optimized fix. Separate dataValidity from quotaSeverity. While data is valid, keep each row's own severity, so a depleted monthly quota does not falsely turn a healthy five-hour row red. When data is invalid, use a neutral/error treatment plus a textual status while preserving the last-known numeric amount and its timestamp. Bars and rings should consume the same policy.

Regression risk. Replacing every row severity with the account's worst quota severity would introduce another misleading cross-window color mapping. Also preserve the special teal state for valid full quotas.

Tests. Extend WidgetStyleTest with staleFullQuotaIsNotHealthyTeal, errorFullQuotaKeepsInvalidDataTreatment, and healthyShortWindowDoesNotInheritExhaustedLongWindowColor. Add equivalent iOS palette tests. Extend selection/data-model tests to pass validity separately from quota severity.

So what: Invalidity must outrank decorative quota colors without merging the meanings of independent quota windows.

### WGT-007 — iOS configured widgets discard an overdue reset while retaining its quota

Platform: iOS · Priority: P1 · Confidence: High.
Symptoms: S3 · Classification: Application temporal-model defect.

Root cause and evidence. `ConfiguredWidgets.swift:81–104` constructs future entries by calling `.selecting(... now: date)`. `WidgetSelection.swift:22–28` filters reset dates to those strictly after that future date, but retains the cached rows. `UsageWidget.swift:246–257` can show "Reset · refresh pending" only if it receives a reset timestamp that is already due. Selection has removed exactly that timestamp.

Example: selected account A has an exhausted five-hour row resetting at 14:00 and a weekly reset on Friday. At the 14:00:02 entry, the five-hour percentage can still be zero, while the summary now names Friday or no reset. The validity of the displayed short-window reading is no longer explained. The configured ring additionally formats `limit.resetAt` with `.time` in `ConfiguredWidgets.swift:177–181`, without an overdue state or a day for a distant reset.

Trigger conditions. A configured widget crosses a cached reset before the app publishes a post-reset reading. A future-dated reset several days away also triggers the ring's ambiguous time-only presentation.

Minimal fix. Carry a distinct reset/pending-refresh state based on the displayed row and its fetch timestamp. Use the existing overdue-aware/date-aware formatting behavior for configured rings rather than plain `.time`.

Optimized fix. Compute ResetPresentation from (row.resetAt, account.fetchedAt, entryDate), independently of nextFutureResetAt used for account ordering. A row fetched before its reset remains "reset passed; awaiting fresh data" until a newer reading confirms its post-reset state. Never synthesize 100% availability merely because the scheduled reset passed.

Regression risk. Reintroducing past timestamps into the ordering key would break closest-reset ordering. Keep presentation validity and sorting timestamps separate.

Tests. Extend WidgetSelectionContractTests and new timeline tests with testPassedShortResetDoesNotBecomeWeeklyResetForCachedShortQuota, testResetBoundaryRetainsPendingRefreshState, and testFreshPostResetSnapshotClearsPendingState. Test fixed-account selection separately from dynamic reset sorting.

So what: Keep the reset that invalidates the shown reading even after it stops being a future sorting candidate.

### WGT-008 — iOS builds one timeline from repeatedly reloaded inputs

Platform: iOS · Priority: P1 · Confidence: Medium for user-visible occurrence; high for the repeated-read mechanism.
Symptoms: S1 conditionally; S3 · Classification: Application snapshot-consistency risk and avoidable I/O.

Root cause and evidence. `ConfiguredWidgets.swift:81–104` reads an initial entry, then reads another entry for every planned date. Each entry reloads the preset file asynchronously and reloads the snapshot file. With the ordinary five dates this is six snapshot reads and six preset loads; the extra reset entry raises it to seven of each. Presets are loaded even when the selection does not use a preset.

If the app publishes or saves a preset between those awaits, one returned timeline can contain different input generations. They are not shared mutable views: each completed entry is a value. The defect is mixing generations during construction, which can later replay an unintended transition at an arbitrary scheduled entry date.

Trigger conditions. Timeline construction overlaps an app snapshot publication or preset save. With stable inputs the repeated reads produce equivalent snapshots and there is no content mixing. No claim is made that this explains every reported swap.

Minimal fix. Read the snapshot once per timeline request and load the selected preset once, only for .custom. Pass these values to a pure makeEntry function for each date.

Optimized fix. Capture a request-scoped input value containing the resolved selection and source revision. Derive all future temporal states from that capture. If snapshot and preset files are independently versioned, record both revisions and handle missing account references explicitly; reading each file once is not a transaction across both files. Add semantic revision-based reload coalescing after successful publication, not a process-global cached selection.

Regression risk. Freezing a preset must not mean retaining it across future provider requests. A subsequent timeline request must reload the latest selected preset.

Tests. Extend WidgetPresetStoreTests with an injected reader that returns alternating revisions. Add testTimelineUsesOneSnapshotRead, testNonCustomTimelineDoesNotReadPresetStore, and testAllEntriesUseCapturedPresetRevision in a new provider-planning test seam. Assert one snapshot read and zero/one preset reads, not a timing benchmark.

So what: Load once per request, derive many entries, and let the next request—not an arbitrary future entry—adopt new input generations.

### WGT-009 — iOS attributes the newest account's timestamp to older visible data

Platform: iOS · Priority: P1 · Confidence: High.
Symptoms: S3 · Classification: Application freshness-attribution defect.

Root cause and evidence. `WidgetSelection.swift:23–24` sets updatedAt to the maximum fetched timestamp among selected accounts. The builder in `GlanceModel.swift:287–315` follows the same aggregate pattern. `UsageWidget.swift:506–510` displays this timestamp on a small widget that presents only the leading account; `UsageWidget.swift:888–898` uses it in the multi-account header.

Trigger conditions. Account A leads but was fetched at 11:00; account B was fetched at 12:00. The small widget shows A's numbers with "As of 12:00." Partial sync success or different provider update histories are sufficient. A single selected account does not have this cross-account problem.

Minimal fix. For the small/focused view, use focus.account.fetchedAt. For a multi-account summary, either label the maximum truthfully as "Latest account update" or use a conservative timestamp for the data actually shown.

Optimized fix. Replace the ambiguous aggregate meaning with explicit fields such as focusFetchedAt, oldestVisibleFetchedAt, and lastPublicationAt. The UI chooses the field that matches its claim. Account data without a known fetch time must not be made fresh by another account's timestamp.

Regression risk. Globally replacing every max with min would conflate provider fetch age with successful app publication time. Keep the separate concepts and update corresponding codec defaults only if new fields are persisted.

Tests. Extend WidgetSelectionContractTests with testFocusTimestampBelongsToFocusAccount, testNewerHiddenAccountCannotFreshenVisibleAccount, and testMissingFetchTimestampRemainsUnknown. Include both configured and legacy small-widget presentation tests.

So what: Date the number being shown, not the newest number somewhere else in the selection.

### WGT-010 — iOS loses the reason a selection is empty

Platform: iOS · Priority: P1 · Confidence: High.
Symptoms: S1 partially through disappearing content; S3 · Classification: Application error-state/presentation defect.

Root cause and evidence. `ConfiguredWidgets.swift:93–103` converts preset-read failure into []; an unresolved preset/account produces an empty selection. `WidgetSelection.swift:22` returns .empty without retaining the reason. The bar view then constructs `WidgetEmptyStateView`, whose default argument re-reads the global snapshot in `UsageWidget.swift:337–381`. A valid global snapshot with other accounts falls into the default "No accounts yet" branch even when the actual issue is "selected account no longer exists." The ring grid in `ConfiguredWidgets.swift:145–187` has no empty-state branch and can render no account content at all.

Trigger conditions. Remove a widget's selected account, delete or corrupt its preset, choose an account/custom mode without its optional entity, or lose access to its source. Other unrelated accounts may still work normally in the app.

Minimal fix. Add an explicit empty-selection explanation for both bars and rings. Preserve read errors instead of treating them as an empty preset list. Do not substitute an unrelated account.

Optimized fix. Carry a closed render-state value in the entry: ready, noAccounts, missingAccount, missingPreset, sourceUnavailable, and corruptSource. Include enough selection context to offer "Edit widget" versus "Open app." Views must render the captured outcome rather than performing a second file read while being archived.

Regression risk. Do not restore the old behavior of inferring device lock simply from file presence. A generic I/O error is not necessarily a locked-device condition. Preserve privacy-safe messages without printing identifiers or filenames to the home screen.

Tests. Extend WidgetPresetStoreTests to verify that provider integration preserves a thrown corruption result. Extend WidgetSelectionContractTests with missing selected IDs while unrelated accounts remain. Add rendering assertions that bar and ring widgets explain the missing selection rather than saying there are no accounts or becoming blank.

So what: Preserve why content is unavailable from input resolution through the final widget view.

### WGT-011 — Android compact layout cannot fit its declared minimum width

Platform: Android · Priority: P2 · Confidence: High for the width contradiction; exact clipping depends on launcher/font metrics.
Symptoms: S3 · Classification: Application layout contract defect.

Root cause and evidence. `widget_compact_info.xml:8–14` allows 180 dp width. `UsageWidgets.kt:143–199` always lays out four equally weighted tiles, three 8 dp gaps, 20 dp total outer horizontal padding, and a 40 dp refresh target. Each tile adds 20 dp horizontal padding in `UsageWidgets.kt:210–217`.

At 180 dp, the width left for the four tiles is 180 − 20 − 24 − 40 = 96 dp, or 24 dp per tile. After tile padding, only 4 dp per tile remain for text. The exact truncation varies, but four labels and percentage/reset values cannot be readable in that space. SizeMode.Exact does not invent a smaller content layout.

Trigger conditions. Resize the compact widget toward its declared minimum width. Larger font scales make the problem worse before reaching the minimum.

Minimal fix. Raise the supported minimum to a measured feasible width, accepting that this limits resize options.

Optimized fix. Use actual available width to choose compact layouts: a focus/value view at narrow widths, a reduced two-metric summary at intermediate widths, and four tiles only when their minimum content widths fit. Keep the refresh action accessible and label the owning account. Do not silently remove essential status to squeeze in decoration.

Regression risk. Raising minimum width does not by itself repair an already placed smaller widget; the renderer still needs a fallback. Existing placement metrics infer grid cells but cannot prove available text width.

Tests. Add WidgetCompactLayoutTest around a pure layout selector: 180dpNeverSelectsFourPaddedTiles, layoutBudgetIncludesRefreshAndGaps, and largeFontUsesReducedContentLayout. Render supported layouts at representative launcher sizes and font scales; pure arithmetic tests cannot confirm visual text fit.

So what: Make the layout's information budget agree with the widths the launcher is allowed to supply.

## 4. Implementation order and optimized solution architecture

### 4.1 Delivery sequence

| Change set | Work | Dependencies and acceptance |
|---|---|---|
| A — Selection contract | WGT-001 and WGT-002: preserve persisted scope meaning; separate critical/reset/overview policies; make legacy consumers explicit. | First. Tests must intentionally replace the existing Android alias assertion. Exact ACCOUNT and CUSTOM selection must not change. |
| B — Captured inputs and explicit outcomes | WGT-008 and WGT-010: request-scoped snapshot/preset inputs; pure entry construction; typed unavailable states. | Independent of palette work; precedes timeline redesign. One snapshot read per iOS request; no provider access. |
| C — Temporal presentation | WGT-005, WGT-007, WGT-009: owned timestamps, reset validity, stale boundary planning. | Builds on B on iOS. Finish the pure model before scheduling background work. |
| D — Appearance and validity | WGT-003, WGT-004, WGT-006: deterministic palette, transparent-mode contract, opacity-separated tracks, validity-first colors. | Can run alongside A/B. Uses temporal validity from C for final integration. |
| E — Layout | WGT-011 plus focus identification in compact layouts. | Independent after the selection/focus presentation contract is agreed. |
| F — Host validation | Multiple instances/kinds, reconfiguration cancellation, process restart, offline aging, host appearance, resize, and tap destinations. | Validate the combined change sets on actual supported host/OS combinations; do not treat existing package tests or Apple framework shims as SpringBoard evidence. |

### 4.2 Keep the architecture small

Retain Glance, Room, WorkManager, WidgetKit, the existing snapshot codec, and the ring subclasses. The needed shared concepts are pure models and policies, not a replacement widget framework or a process-global mutable widget cache.

A practical flow on each platform is:

```
Read source + resolve this widget's selection
    -> CapturedWidgetInput
    -> presentation(input, date, appearance, availableSize)
    -> WidgetPresentation
    -> existing Glance/SwiftUI views
```

CapturedWidgetInput should hold a resolved selection, a source revision, the normalized account values, style configuration, and explicit load outcome. WidgetPresentation should identify the owning account/row of every percentage and timestamp, separate validity from quota severity, and expose a closed empty/error state.

The invariants are: a configured account never silently becomes another account; an unchanged configuration never changes primary ink merely because quota changed; a displayed percentage and its reset/fetch timestamp have the same owner; a failed input read never means "no accounts"; a cached pre-reset value never becomes confirmed post-reset availability through clock arithmetic.

### 4.3 Android update path

Keep the existing initial load before provideContent, followed by collection while the session is active. That is the recommended lifecycle shape. Remember the flow using the widget ID/context inside the composition so recomposition does not needlessly recreate subscriptions. Apply equality filtering to avoid redundant presentation work, while retaining real clock-boundary invalidations. Do not share one mutable WidgetView across widget IDs.

For configuration saves, commit the complete row first and request an update of the owning instance. A new targeted helper should map the actual provider to its concrete widget type; the existing all-widget refresh remains appropriate for broad source changes. Isolate failures per instance where updates are enumerated, preserve coroutine cancellation, and record privacy-safe failure reasons. The current per-kind exception isolation is useful but is not evidence that every sibling instance successfully rendered.

Create a separate WidgetPresentationWorker that only reads local data and repaints. It must not require a network or read credentials. Coalesce due freshness/reset transitions into one app-level schedule, cancel it when no widgets remain, and preserve network constraints on SyncWorker. Keep an absolute fetch timestamp as a fallback when the OS delays work. Do not add a one-second polling loop, exact-alarm permission, or a permanent service.

Restoration/deletion handling is defensive follow-up, not a proven explanation of ordinary reboot behavior. A restore implementation must remap all old/new IDs transactionally using the framework's explicit mapping, including cycles/collisions, and show missing configuration when the old row is unavailable. Never copy a random surviving row. Deletion should clean up only the removed instance. These are implementation proposals, not changes performed during this audit.

### 4.4 iOS timeline path

Resolve configuration once per request. Load the source once and the selected preset at most once; do not read any preset for ACCOUNT/PROVIDER/ALL/CLOSEST modes. Build all entries from those captured values with a pure makeEntry(input, date).

Generate entries for meaningful display transitions: selected accounts' stale boundaries, relevant reset boundaries, and date-label changes where the presentation uses day-relative wording. Deduplicate equivalent dates and presentations. Include an aged terminal state rather than relying on a successful reload after the current one-hour horizon. Preserve the explicit difference between a reset ordering key and an overdue displayed-row reading.

Keep publication-driven reloads: the code already calls them. Optimize by requesting them after a successful, relevant snapshot write and coalescing equivalent publications. Avoid inventing a fixed "reload every 15 minutes" delivery guarantee. Extending the supplied timeline can improve correctness under delayed reloads without adding network activity.

For shared presets, editing a preset should continue to affect widgets that explicitly reference it; that is distinct from cross-widget corruption. If independent copies are desired, make "Duplicate preset" or a per-widget immutable selection an explicit product choice rather than silently changing reference semantics.

### 4.5 Regression strategy

The inspected tests establish useful pieces, but not end-to-end host behavior. WidgetSelectionContractTests.swift exercises GlanceModel.build, while configured widgets use GlanceSnapshot.selecting; add parity tests between those two actual paths. WidgetPresetStoreTests proves that the store throws on corrupt data, while the provider currently catches and erases that error; add provider-integration tests. WidgetRefreshCoverageTest proves that all kinds are listed, not that a successful host render follows each request.

Use a compact adversarial fixture with three accounts: one exhausted/later-reset, one healthy/soon-reset, and one stale or unavailable. Add a weekly reset alongside a five-hour reset. Give every account a distinct ID, fetch timestamp, and user order. Place two instances of each kind with opposing palettes and disjoint selections. This fixture separates scope, time, validity, appearance, and identity failures without using any live account.

## 5. Seeded hypotheses — verified, rejected, or partial

**H1 — Config-race fallback: P (partial).** A genuinely absent row does produce fallback selection/style, but the actual fallback is closest resets, not most critical. WidgetConfigDao.observe is an observable SQL query; combine consumes actual upstream emissions, and there is no onStart { emit(null) } in the inspected bridge. A previously committed row is not shown to first become null merely because its query takes time. Configuration loads the existing row before constructing the editor; save awaits upsert before requesting refresh. Cancel before save leaves the existing row untouched. A new widget rendered before its initial save is a different trigger from a configured widget losing its row.

**H2 — appWidgetId lifecycle: P (partial).** An actual backup restore can remap IDs; Android explicitly provides onRestored(oldIds,newIds) for persisted per-instance state. No app-owned Room remapping is visible in the inspected receiver classes. However, ordinary reboot is not evidence of ID reassignment, and this app disables backup and excludes database/file/shared-preference data from cloud and device transfer. The repository's polish report also records a successful emulator restart, although that is prior evidence rather than this audit's reproduction. Treat restore recovery as conditional hardening; do not rank it as the demonstrated cause of routine swaps.

**H3 — Shared-base cross-wiring: R (rejected).** The concrete MinimalUsageWidget and MiniRingsWidget classes and receivers remain distinct. mini is constructor configuration, not mutable per-instance account state. The official current Glance updateAll implementation enumerates manager.getGlanceIds(javaClass), so the runtime concrete class matters, not the fact that factories return the base type. Each observation resolves its own supplied Glance ID to an appWidgetId. No application-level kind/instance collision is established. This does not prove that no host/framework bug exists in an untested runtime.

**H4 — Ink-flip first emission: P (partial).** The alleged automatic first-frame default is not established. All inspected providers await WidgetUpdater.load(...).first() before rendering, then use that result as the collection's initial value. A default style comes from a genuinely missing/invalid stored input, not from an unconditional loading value. textTone is read into editor state and persisted on save. WGT-003 does verify the transparent-AUTO assumption and its zero-opacity discontinuity. Also, the failed application cast in observe completes an empty flow; first() throws NoSuchElementException, rather than suspending indefinitely.

**H5 — Severity-driven black/white changes: R (rejected).** Severity changes accent colors, but does not control WidgetStyle.lightInk. Primary/secondary light-versus-dark text derives from panel/tone configuration. Thus staleness can contribute gray/colored transitions, but is not the demonstrated cause of primary black/white flipping. The separate validity-color inconsistency is WGT-006.

**H6 — Mixed iOS Home Screen color sources: R (rejected).** The cited .secondary uses occur in UsageAccessoryRectangularView, which intentionally uses system accessory colors. They are not mixed into the Home Screen card's fixed palette at those locations. The app's .preferredColorScheme(.dark) is not evidence that the separate widget extension is pinned dark, but fixed Home Screen RGB values also do not become dynamic simply because no scheme is pinned. Host recoloring and transparent-panel contrast remain real, separately documented issues: WGT-003/WGT-004.

**H7 — Mutable presets and missing reloads: P (partial).** Entries carry snapshot values; normal views do not resolve shared mutable presets at later display time. Two widgets referencing the same preset changing together after that preset is edited is reference semantics, not evidence they selected each other's preset. Repeated reads during construction create WGT-008. The claim that only WidgetPresetsScreen reloads timelines is refuted: UsageLimitsContainer.publish also calls reloadAllTimelines. App Intent configuration is supplied to the provider through the configured-widget API, but this audit did not establish a universal completion deadline after configuration editing. Do not equate the documented post-perform() interactive-action reload behavior with a proven latency guarantee for configuration changes.

**H8 — Entity-query substitution: R (rejected).** Both queries return only requested IDs that resolve in their current lists. Neither substitutes the first account, a default preset, or a critical account for an unresolved ID. The selector also filters an explicit account exactly. Missing IDs can still cause WGT-010's misleading empty state. Framework query invocation timing or stale persisted entity labels requires a real configuration-edit trace, but no app-coded substitution exists in these methods.

## 6. Framework behavior and research boundaries

| Topic | Established behavior and consequence |
|---|---|
| Glance lifetime | Official Glance source documents worker-backed provideGlance, initial loading before provideContent, an approximately 45-second composition session with extensions for interactions/updates, and no automatic restart of an already active provideGlance on every update. The app's initial-load-plus-observe shape matches that contract. |
| Glance state and updates | The app must persist needed data and request new RemoteViews after relevant changes. A composition-local style is not a shared mutable per-widget registry. A stopped observer cannot keep updating the launcher. |
| Exact size | SizeMode.Exact recreates content for supplied size changes; it does not switch kind or rebind Room IDs. It can incur layout/recomposition work and host-dependent sizing. Responsive variants are a layout/performance option, not a fix for corrupted selection. |
| No-emission loading | The specific failed app cast completes the flow and makes first() throw. A genuinely non-emitting upstream could delay provideContent; the exact host loading/previous/error presentation is not established here. The compact XML supplies a loading layout, but no universal launcher transition is claimed. |
| WidgetKit entry lifetime | Apple explains that view code runs when views are archived; the host later renders the archived representation. Entry values, not a perpetually executing SwiftUI timer, drive the ordinary widget. |
| WidgetKit reloads | Reload policy is a request considered by the system; background work is budgeted, and visibility influences scheduling. The report intentionally gives no universal reload count or delivery deadline. |
| App Intent configuration/entities | Parameters and queries belong to the configuration mechanism. Exact selection is visible in the app code. The precise order/count of resolver calls and a hard post-edit render deadline were not established, so they are not asserted as facts. |
| Background and appearance | Home Screen rendering mode and opacity can override the intended palette. The existing Home Screen roots do adopt containerBackground through the wrapper/direct call. Host recoloring must be accommodated, not attributed to a nonexistent mutable global style. |
| Accessories and taps | The accessory views deliberately use system presentation. Current Apple linking guidance permits links in more contexts than older "small widgets have one tap target" guidance; this report does not claim that every systemSmall link is broken. Validate deployment OS behavior and use a deliberate fallback destination where required. |

The official current Glance source was inspected for lifecycle/class-dispatch semantics; the resolved 1.2.0 binary was not downloaded or executed. Current documentation may describe APIs newer than the pinned app. No dependency upgrade or newly introduced API is required merely to apply the pure-model fixes in this report. Some Apple documentation pages returned only a JavaScript shell or could not be fully retrieved; official WWDC transcripts and the successfully retrieved current appearance/link documentation support the narrower claims made above. Numeric budget folklore and universal configuration latency claims were deliberately omitted.

## 7. Deliberately not re-reported

The four Android widget kinds are present in WidgetUpdater.allWidgets. Refresh exceptions are isolated by kind rather than enclosed in one catch around the whole loop. The compact long-horizon label comes from its row, bars use Glance's LinearProgressIndicator, and the refresh icon uses the instance's primary ink. The legacy transparent flag is honored by WidgetStyle.fromStored, with an existing unit test.

Exact Android account/custom filtering and iOS account/custom filtering do not fall back to unrelated accounts. iOS snapshot publication already requests timeline reloads. UsageLimitsApp.handle includes an account URL route, and Android MainActivity reads account extras in both creation and onNewIntent; therefore "there is no account tap handler" is also not a valid finding. This is source-level verification, not a successful device tap test.

ULV-001…ULV-009 and provider/auth/parsing issues were excluded as requested, not independently re-audited. The release-readiness and polish records were read as historical evidence, not treated as tests executed at this pin. The brief's suggested app/src/test/kotlin/com/usagelimits/widget/WidgetUpgradeTest.kt path returned 404; no result from that test or a schema-migration execution is claimed. WGT-006 is specifically a remaining current precedence defect, not a repetition of the older fixed stale-age implementation.

## 8. Open questions and exact evidence to capture

### 8.1 Does "which widget changes" mean kind, account, row, or host stack?

Capture a screen recording starting with each widget's edit screen, then the home screen before and after the change. Include the widget kind, selected scope/account/preset, whether it is inside an iOS Smart Stack, the selected Home Screen tint/clear appearance, OS version, launcher name/version, and installed app revision. Without these, a kind switch, a legitimate closest-reset lead change, and a row/headline transition cannot be distinguished.

### 8.2 Android instance/configuration evidence

The following capture commands are read-only with respect to app configuration; run them before and after the event and keep the output local:

```
adb shell dumpsys appwidget > appwidget-state.txt
adb shell cmd uimode night > night-mode.txt
adb logcat -v threadtime -d GlanceAppWidget:V WM-WorkerWrapper:V AndroidRuntime:E '*:S' > widget-log.txt
```

Launcher dumps/logs may contain personal widget metadata; redact before sharing. These logs cannot expose a configuration fingerprint that the current app never logs. In an implementation/debug branch, add a WidgetAudit event at configuration load/save, provider entry, and presentation construction containing: event reason, concrete kind, appWidgetId, a locally salted digest of selected IDs, config revision, source revision, panel ARGB/opacity/tone, resolved primary ink, available size, entry time, and stale/reset state. Never log tokens, payloads, emails, or raw account IDs.

The decisive S2 comparison is whether the resolved primary ink/config fingerprint changed. If neither changed but the pixels did, investigate host appearance/RemoteViews handling rather than changing Room selection logic. For S1, compare stored and resolved scope first, then selected-ID digest and leading-ID digest. A scope that is intentionally dynamic needs a different assertion from ACCOUNT/CUSTOM.

### 8.3 iOS provider and host evidence

Add debug-only unified logging around snapshot(for:), timeline(for:), source/preset reads, and successful publication/reload requests. Record kind, family, configuration fingerprint, snapshot/preset revisions, planned entry dates, resolved account digests, fetch timestamps, reset-pending state, and load outcome. Use a configuration fingerprint for correlation; do not assume WidgetKit supplies an application-owned stable instance ID for identical configurations.

Capture appearance values during view archiving: widgetRenderingMode, colorScheme, and container-background visibility where available. Record the actual Home Screen state as well: archived view diagnostics are not a pixel-level host trace. Compare the same synthetic 3%/50%/100% fixture in full-color and accented appearances.

For configuration latency, capture the edit completion time, first provider callback with the new fingerprint, returned timeline fingerprint, and first observed matching frame. This distinguishes entity-resolution failure, missing provider invocation, input-read failure, and a host display delay without guessing which layer failed.

### 8.4 Boundary and multi-instance validation

With synthetic local data, test each widget before/at/after reset and stale boundaries, with no new publication. Test two different custom presets plus two widgets sharing the same preset. Test reconfigure/save and reconfigure/cancel separately. Repeat after app process termination and device restart; treat an actual restore as a separate test requiring old/new ID capture. Verify that changing one widget's light/dark override cannot change another widget's configuration fingerprint.

The audit establishes conditional mechanisms and an implementation plan. It does not establish the observed frequency of races, successful reproduction on the user's launcher, or that the user's installed build is this commit. Those are the remaining evidence gaps—not reasons to introduce speculative architectural rewrites.
