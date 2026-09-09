# Architecture

The whole app is one idea repeated at every level: whatever a provider hands back, turn it
into the same small set of normalised types as early as possible, and let everything above
that point be provider-agnostic. A screen never asks "which provider is this?" to decide how
to draw a bar, and a widget never learns that Antigravity reports a remaining *fraction* while
Codex reports a consumed *percent*.

## Layers and dependency direction

Everything lives in a single Gradle module under `com.usagelimits`, with the package structure
carrying the layering:

```
core/model      pure Kotlin types — no imports at all, not even kotlinx
core/network    HttpClient, JsonSupport, ProviderException, ProviderEndpoints
core/auth       OAuthCredentials, CredentialStore, KeystoreCredentialStore
core/oauth      Pkce, JwtClaims, LoopbackServer
core/time       Instants, Countdown
core/database   Room entities, DAOs, UsageRepository
core/settings   SettingsStore (DataStore)
core/sync       SyncEngine, SyncWorker, NotificationPublisher
core/di         AppContainer
providers/      UsageProvider + one package per provider (provider + parser)
feature/        ViewModels and Compose screens
ui/             theme, shared components, adaptive layout helpers
widget/         Glance widgets and the reducer that feeds them
navigation/     the app shell
```

The dependency rule is that arrows point *down* this list, and it holds where it matters:

- `core/model` imports **nothing**. Not Android, not kotlinx.serialization, not even the rest
  of `core`. That is what makes `Severity`, `WindowCategory` and the window arithmetic
  testable in a plain JVM test with no Robolectric, and it is worth defending.
- `providers/` depends on `core/` and on nothing above it. A provider cannot reach a screen,
  a widget, or the database.
- `widget/` imports only `core.model`, `core.time.Countdown`, `core.database.AccountUsage`
  and `core.sync.SyncWorker`. It has no import of `core.auth` and no import of `providers` —
  the point of §"Two stores" below, and a property you can re-verify with one grep.

Two edges deliberately point back up, and both are at the composition seam rather than inside
a layer:

- `core/sync/SyncEngine` and `core/di/AppContainer` import `providers.ProviderRegistry`. The
  alternative — a registry interface in `core` implemented in `providers` — buys an indirection
  that no second implementation would ever use.
- `core/sync/SyncWorker` imports `widget.WidgetUpdater`, because "the cache moved, wake the
  widgets" has to be expressed somewhere and the worker is where the pass ends.

There is one genuine leak worth naming: `feature/accounts/AddAccountViewModel` imports
`CodexProvider` to call `displayCode()`, because Codex packs a user code and a device id into
one challenge field and only Codex knows how to split it again. It is a small wart. The clean
fix is for `LoginChallenge.DeviceCode` to carry the displayable code separately from whatever
opaque state the provider needs to carry forward, and that is what should happen the next time
a device-flow provider is added.

## The `UsageProvider` abstraction

`UsageProvider` is the contract that keeps provider JSON out of the app:

```kotlin
interface UsageProvider {
    val providerId: ProviderId
    suspend fun beginLogin(): LoginChallenge
    suspend fun completeLogin(challenge: LoginChallenge, redirectResponse: String? = null): OAuthCredentials
    suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials
    suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile
    suspend fun fetchUsage(account: ProviderAccount, credentials: OAuthCredentials): UsageResult
    val supportsResetCredits: Boolean get() = false
    suspend fun consumeResetCredit(account: ProviderAccount, credentials: OAuthCredentials)
}
```

Read it for what is absent as much as for what is present. There is no `complete()`, no
`chat()`, no `send()`. The interface cannot express an inference request, which is the
structural version of the promise the README makes in prose.

Each implementation owns every provider-specific detail — endpoint, headers, payload shape,
the non-standard way OpenAI signals "still pending", the fact that Anthropic's token endpoint
wants JSON rather than form encoding — and hands back only `UsageResult`, which is a list of
`UsageWindow` plus a list of `ResetCredit`. Parsing is split into a separate object per
provider (`CodexUsageParser`, `ClaudeUsageParser`, `AntigravityQuotaParser`,
`XaiBillingParser`) that is pure Kotlin over a `JsonObject`, so the interesting half of each
provider is unit-testable without an HTTP stack or an Android runtime.

**Why the UI never sees provider JSON.** If a screen could reach a raw payload it would
eventually branch on one — "if this is Antigravity, divide by 100" — and that branch would
then be duplicated in the widget, and the two would drift. Because the boundary is a data
type rather than a convention, the drift is impossible: `OverviewScreen` receives
`UsageWindow`s and has no way to ask what shape they arrived in. It also means adding a fifth
provider is one new package plus one line in `ProviderRegistry`, with no screen or widget
change at all.

Parsers are defensive by policy. Every field lookup goes through `JsonSupport`'s vararg
accessors, which accept snake_case and camelCase spellings alike, unknown keys are ignored,
and a window that cannot be understood is dropped rather than rendered from an assumed zero.
An upstream rename costs one row, not a failed sync.

## The normalised model

Five types carry everything, and each exists for a reason.

**`ProviderAccount`** is identity, keyed by `provider + externalAccountId` rather than by
e-mail — the same address can back several accounts and an address can change while the
account does not. It carries a `credentialReference`, which is a *name* for an entry in the
credential store rather than a credential; that is the seam the whole security story hangs
from. Provider-specific extras that are not secret (the Antigravity GCP project id, the
ChatGPT account id header value) live in a small `attributes` map so a provider can stash
what it needs without the model growing a per-provider field.

**`UsageWindow`** is one bar. It stores `usedPercent` (0–100 *consumed*, because that is what
every provider reports) and derives `remainingPercent`, because "82% remaining" is what the
user reads. Storing both would risk them disagreeing; deriving means one rounding, once. The
`category` — `FIVE_HOUR`, `WEEKLY`, `MONTHLY`, `OTHER` — is computed from the declared window
*duration*, never from the window's position in the payload, because upstream puts a monthly
window in the "secondary" slot on team plans. `OTHER` is a real, renderable outcome: an
unrecognised window is shown with whatever label the provider gave it rather than being forced
into the wrong bucket or dropped.

**`UsageSnapshot`** is one fetch for one account: when, whether it worked, the windows, and any
reset credits. Its `status` distinguishes `OK`, `PARTIAL` and `FAILED`, and — this is the part
that matters for the feel of the app — a `FAILED` snapshot keeps the *previous* windows. The
screen then says "updated 37 minutes ago, refresh failed" instead of going blank, which is both
more useful and more honest.

**`ResetCredit`** models the one genuinely consumable thing any provider exposes: a Codex
rate-limit reset. Only credits that are both of the Codex type and reported as available are
modelled, and `supportsResetCredits` is false everywhere else — the app does not invent a
concept a provider does not have.

**`Severity`** is the single scale everything colours by, with thresholds defined once:
above 50% healthy, 20–50% medium, above zero but at or below 20% low, zero exhausted, plus
`STALE` and `ERROR`. The enum is ordered best-to-worst so "the worst window in this account"
is `maxOf`, and both the app and the widgets read the same enum — which is why a card and a
widget tile can never disagree about whether something is low. One honest gap:
`Severity.STALE_AFTER_MS` (one hour) is declared but not yet applied anywhere. Old data is
currently shown with its true age via `Countdown.freshnessLabel` rather than being downgraded
to `STALE`, and `STALE` appears only as the fallback for an account that has never synced.

## Two stores, and why widgets can only reach one

Credentials and cache are separated by design, not by convention:

- **`KeystoreCredentialStore`** holds OAuth credentials, encrypted with an AES-GCM key that
  lives in the Android Keystore and cannot be exported. Ciphertext goes to a private
  `SharedPreferences` file. Keys are `cred_<credentialReference>`.
- **Room** (`usage_limits.db`) holds account metadata, one usage snapshot per account, and
  per-widget configuration. By construction there is no column anywhere that could contain a
  token — `AccountEntity` stores the credential *reference*, and the snapshot table stores
  normalised windows serialised as JSON.

The consequence is that reading the entire database tells an attacker how much Claude quota
you have left and nothing else. That is what makes it safe to let widgets read it directly:
`WidgetUpdater` goes through `UsageRepository` only, `WidgetDataBuilder` is a pure function
over already-loaded `AccountUsage` values, and the `widget` package does not import
`core.auth` at all. A token cannot reach the launcher process because there is no call chain
that would carry one there — see `docs/widgets.md` for the full path.

Backups and device transfer are disabled for both stores (`allowBackup="false"` plus
exclude-everything data extraction rules). Keystore-wrapped ciphertext would be useless after
a restore anyway, and the usage cache is re-derived on the next sync.

## The sync pipeline

```
   provider HTTPS endpoints (OAuth + usage)
                 |
                 v
   +-----------------------------------------------+
   |  providers/<id>/  XProvider + XUsageParser     |  the only place raw
   |  implements UsageProvider                      |  provider JSON exists
   +----------------------+------------------------+
                          |  UsageResult(windows, resetCredits)
                          v
   +-----------------------------------------------+      +--------------------------+
   |  core/sync/SyncEngine                          |<---->|  core/auth               |
   |   - one coroutine per account (isolated)       | creds|  KeystoreCredentialStore |
   |   - per-reference refresh mutex                |      |  AES-GCM, Android Keystore|
   |   - failure keeps previous numbers             |      +--------------------------+
   +----------------------+------------------------+
                          |  UsageSnapshot            SyncEngine is the ONLY
                          v                           caller of core/auth
   +-----------------------------------------------+
   |  core/database/UsageRepository  (Room)         |  holds no tokens, only a
   |  accounts | usage_snapshots | widget_configs   |  credential *reference*
   +------+----------------------------+-----------+
          |  Flow<List<AccountUsage>>  |  accountUsageOnce()
          v                            v
   +---------------------+     +-----------------------------+
   |  feature/ + ui/     |     |  widget/ WidgetDataBuilder  |
   |  UsageViewModel     |     |  -> Compact / Detailed      |
   |  Compose screens    |     |     Glance widgets          |
   +---------------------+     +-----------------------------+
                                       ^
                                       |  refresh tap enqueues
   core/sync/SyncWorker  (WorkManager) -+  a normal sync pass
```

`SyncEngine` is reached two ways, and the distinction is worth knowing.

`SyncWorker` (WorkManager) runs the periodic pass at the user's interval, floored at 15
minutes, and enqueues a one-shot pass on app start, on `MainActivity.onResume`, and from the
widget's refresh button. That work is unique with `ExistingWorkPolicy.KEEP`, so several
triggers in a row collapse into one pass, and every request carries a
`NetworkType.CONNECTED` constraint.

The foreground actions — pull-to-refresh, a single account's refresh button, the sync that
follows a login, and the re-sync after a reset credit is spent — call `SyncEngine` directly
from the ViewModel's scope instead. That is right for a user-initiated action, which should
run now and report its own outcome rather than being queued behind a constraint, and these
calls still inherit isolation and the refresh mutex because they go through the same engine.
Two consequences follow, and neither is currently handled: a foreground refresh does not wait
for connectivity (it fails fast and shows the error, which is arguably what the user wants),
and — less defensibly — it does **not** refresh the widgets, because `WidgetUpdater.refreshAll`
is called only from `SyncWorker.doWork`. Pull-to-refresh in the app therefore leaves the home
screen showing the older numbers until the next worker pass. Calling `refreshAll` after a
foreground sync, or routing these through the worker, would close that.

**Per-account isolation.** `syncAll()` launches one coroutine per account and awaits them all;
`syncAccount()` catches `ProviderException` *and* every other exception, records the failure
against that account, and returns a `SyncOutcome` instead of throwing. A revoked Claude token,
an Antigravity outage, or an outright bug in one parser therefore costs exactly one card. The
worker reports success if *any* account succeeded and asks WorkManager to retry only when they
all failed, since that pattern implies a shared cause (offline, doze) rather than four
independent problems — and retrying when a provider is already failing would only double the
request rate.

**The refresh mutex.** Token refresh is serialised per `credentialReference` through a map of
mutexes, and — importantly — the expiry is re-checked *inside* the lock. The race this
prevents is specific and nasty. Providers rotate refresh tokens: presenting one invalidates it
and returns a new one. Suppose the periodic pass and a pull-to-refresh both notice the access
token is near expiry at the same moment. Both call `refresh()` with refresh token `A`. One of
them wins, gets `B`, and stores it. The other's request either fails, or succeeds and stores a
result derived from an already-spent token; either way the last writer can leave the store
holding a token the provider considers dead, and the account then reports "sign-in expired"
for no reason the user did anything to cause. Re-reading inside the lock closes it: the second
caller wakes up, sees that the stored credentials are now fresh, and uses them instead of
spending anything.

**Stale over empty**, once more, is the third property: `repository.saveFailure()` copies the
previous windows forward and deliberately keeps the *original* `fetchedAt`, so the data ages
truthfully while the error is carried alongside it.

After the engine finishes, the worker refreshes the widgets and hands the whole cache to
`NotificationPublisher`, which folds every finding across every account into at most one
grouped notification posted to a fixed id — so a later sync replaces the earlier alert instead
of stacking a pile.

## Deliberate deviations

### One Gradle module, package-enforced boundaries

The conventional Android answer is `:core`, `:data`, `:feature-x`, `:providers` as separate
Gradle modules, where the build tool refuses a dependency that points the wrong way.

This project uses one `:app` module and enforces the same boundaries by package discipline and
review. The reason is proportion: multi-module pays for itself through parallel and
incremental compilation, and this codebase is roughly fifty source files. Against that, each
extra module costs a build file, a version-catalog wiring, and a slower configuration phase —
and the KSP/Room and Compose compiler plugins would have to be applied in several places.

What it costs: nothing stops someone importing `providers.CodexProvider` from a screen. In
fact someone already has (see the `displayCode` leak above), which is precisely the failure
mode a module boundary would have caught at compile time.

Revisit when any of these becomes true: the module would exceed roughly 150 source files;
a second consumer of the provider layer appears (a Wear app, a desktop build, a KMP target);
build times become a felt problem; or more than one person is working in the repository, at
which point "we agreed the arrow points down" stops being enforcement.

### Manual constructor DI via `AppContainer`

`AppContainer` is about thirty lines: a `by lazy` field per singleton, constructed with the
application context, read from `UsageLimitsApp.container`. ViewModels take it through an
explicit `ViewModelProvider.Factory`.

Hilt would be the default choice. It is not used because the graph is entirely
singleton-scoped, has no build-variant or qualifier branching, and fits on one screen — so
annotation processing would add build time and a class of failure (a missing binding surfacing
as a generated-code error) in exchange for wiring that is currently just readable. Tests get
the same benefit from the other direction: `SyncEngine`, the providers and the parsers all
take their dependencies as constructor parameters, including an injectable `nowMs: () -> Long`
clock, so a test builds exactly the graph it needs and nothing else.

What it costs: the container is a hand-maintained singleton, so a mistake there is a runtime
mistake rather than a compile-time one; anything needing a non-singleton scope has to invent
its own lifecycle; and `WidgetUpdater` has to cast `context.applicationContext` to
`UsageLimitsApp` to reach the graph, which is exactly the sort of thing an injection framework
removes.

Revisit when: scoped bindings appear (a per-login or per-account graph), build variants need
different implementations, the container passes roughly a dozen entries, or `WorkManager`
needs constructor-injected workers — the first genuinely awkward case, since `SyncWorker`
currently reaches the graph through the application object rather than being given it.
