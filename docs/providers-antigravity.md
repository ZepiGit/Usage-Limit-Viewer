# Antigravity (Google)

Implementation: `app/src/main/kotlin/com/usagelimits/providers/antigravity/AntigravityProvider.kt`,
`AntigravityQuotaParser.kt`, and the `ProviderEndpoints.Antigravity` block in
`app/src/main/kotlin/com/usagelimits/core/network/ProviderEndpoints.kt`.

## 1. Status

**Nothing in this document has been executed against a real Google account.** No login has
been completed, no project has been resolved and no quota summary has been fetched from this
codebase in the environment it was written in. Every flow below is implemented from the
reference client (see [Provenance](#7-provenance)) and is *unverified end to end*.

What is implemented:

- Google's installed-application authorization-code flow with PKCE over a loopback redirect,
  with `access_type=offline` and `prompt=consent` both set deliberately.
- Token exchange and refresh, including the Google-specific behaviour that a refresh response
  never carries a new refresh token.
- Two-step quota reading: GCP project resolution at login, then the quota summary addressed by
  that project, tried across three hosts.
- A parser with tests covering grouping, the remaining→consumed conversion, both key
  spellings, bucket ordering, and the drop-one-bucket and drop-a-whole-group cases.

What is unverified beyond "it has never run":

- Whether `loadCodeAssist` returns a project id for a subscription-based Antigravity account
  in the shape the code expects. This is the highest-consequence unknown for this provider:
  without a project id, an account can authenticate successfully and still never display a
  single number, and the code treats that as a login failure rather than a sync failure.
- Which of the three quota hosts actually answers, and whether all three still exist.
- Whether the `metadata` block `loadCodeAssist` requires is still accepted with
  `IDE_UNSPECIFIED` / `PLATFORM_UNSPECIFIED` / `GEMINI` from a non-IDE client.
- Whether the `window` strings are still `"5h"` and `"weekly"`, or have moved to an enum.

## 2. Auth flow

The app uses **Google's authorization-code flow with PKCE, on a loopback redirect**
(`http://localhost:51121/oauth-callback`, RFC 8252 §7.3). This is the same flow the reference
desktop client uses; unlike Codex and xAI there is no device-code alternative for this client,
so there is no Android-friendlier option to choose. The Android-specific accommodations are in
the details rather than the flow: the browser handles the whole authentication, the app binds
a fixed local port for one redirect only, and the listener is closed in a `finally` so a
failed attempt cannot leave the pinned port occupied and break the next one.

The steps as implemented:

1. `beginLogin()` generates a PKCE pair and a CSRF state and holds both on the provider
   instance, marked `@Volatile` because begin and complete may run on different threads. As
   with Claude they are deliberately kept out of the `LoginChallenge` value, which travels
   through the UI layer.
2. The authorization URL carries `client_id`, `response_type=code`, `redirect_uri`, the five
   scopes joined by spaces, `state`, `code_challenge`, `code_challenge_method`, and then two
   parameters that are load-bearing rather than decorative:
   - **`access_type=offline`** — without it Google issues no refresh token at all, and the
     account would deauthenticate an hour after being added.
   - **`prompt=consent`** — without it Google withholds the refresh token on every login
     *after the first*, because it assumes the caller kept the original. This app stores
     credentials per account and a re-login means the previous set is gone, so consent has to
     be re-requested every time or the second sign-in silently produces an account that dies
     in an hour.
3. The UI opens the URL in the system browser; the user picks a Google account and consents.
4. `completeLogin()` starts a `LoopbackServer` on port 51121 and waits up to five minutes —
   Google's consent screen is slow and the user may need to switch accounts mid-flow.
5. An `error` parameter becomes `LoginCancelled`. A missing state, a state that fails
   `Pkce.constantTimeEquals`, or a missing code all abort before the code is spent.
6. The code is exchanged at `https://oauth2.googleapis.com/token` with a form body carrying
   `code`, `client_id`, **`client_secret`**, `redirect_uri`, `grant_type=authorization_code`
   and `code_verifier`.
7. `fetchProfile()` reads `https://www.googleapis.com/oauth2/v2/userinfo` for `id`, `email`
   and `name`. The `id` is the stable subject; the address is a fallback, because a user can
   change an address while the account stays the same.
8. `fetchProfile()` then resolves the GCP project (§ below) and stores it as the account
   attribute `project_id`.

**The client secret, and why it is in the APK.** `ProviderEndpoints.Antigravity.CLIENT_SECRET`
holds Google's *installed-application* client secret for the Antigravity client. This is a
documented, deliberate deviation from the project's own "no client secrets in the APK" rule,
so the reasoning is set out in full:

- It is **not a confidential credential**. RFC 8252 §8.5 says so explicitly for native apps,
  and Google's installed-app documentation treats it the same way. It ships inside the
  first-party desktop client already and can be read out of it by anyone who cares to.
- It is **required**: Google's token endpoint rejects the exchange without it for this client
  type.
- The **scopes are bound to this client id**. A self-registered Android client would be a
  different client, and the Antigravity quota scopes (`cclog`, `experimentsandconfigs` and the
  internal `cloudcode-pa` surface they unlock) are not grantable to it. Registering our own
  client is not a worse option; it is a non-functional one.
- **PKCE is what actually protects the flow.** An attacker holding this string still cannot
  complete a login: the authorization code is worthless without the `code_verifier`, which is
  generated per attempt and never leaves the device.

The alternatives that were rejected: registering a fresh Google client (cannot reach the
scopes); shipping a small server to hold the secret (the app is local-first and has no
app-owned server, which is a core property, not a convenience); and asking the user to paste
their own OAuth client (unusable, and it would put a *real* confidential secret in the hands
of a UI text field). The trade-off accepted is that the app ships a value Google calls public
and the project's own rule calls a secret. `docs/security.md` carries the same argument in
context.

Refresh, at the same endpoint with `grant_type=refresh_token`, has one Google-specific
wrinkle: **the response never contains a refresh token.** The original stays valid until it is
revoked, so `refresh()` copies it forward unconditionally. Dropping it would deauthenticate
the account after a single hour-long access token expired.

## 3. Endpoints and headers

| Method and URL | Purpose | Headers sent | Provenance |
|---|---|---|---|
| `GET https://accounts.google.com/o/oauth2/v2/auth` | Browser authorization | — (opened in the system browser) | OAuth standard (RFC 6749 §4.1.1, RFC 7636, RFC 8252); `access_type` and `prompt` are Google extensions |
| `http://localhost:51121/oauth-callback` | Loopback redirect target | — (local listener) | OAuth standard (RFC 8252 §7.3); the port is pinned by the client registration |
| `POST https://oauth2.googleapis.com/token` | Code exchange and refresh | `Accept`; form body incl. `client_secret` | OAuth standard (RFC 6749 §4.1.3 and §6) |
| `GET https://www.googleapis.com/oauth2/v2/userinfo?alt=json` | Account identity | `Authorization`, `Accept` | Documented provider API (Google OAuth2 v2 userinfo) |
| `POST https://daily-cloudcode-pa.googleapis.com/v1internal:loadCodeAssist` | Resolve the GCP project | `Authorization`, `Content-Type: application/json`, `User-Agent` | Internal endpoint observed in the first-party CLI — NOT a stable public API (`v1internal` is upstream's own naming) |
| `POST …/v1internal:retrieveUserQuotaSummary` on `daily-cloudcode-pa.googleapis.com`, then `daily-cloudcode-pa.sandbox.googleapis.com`, then `cloudcode-pa.googleapis.com` | Quota summary | `Authorization`, `Content-Type: application/json`, `User-Agent` | Internal endpoint observed in the first-party CLI — NOT a stable public API |

The three quota hosts are tried **in order until one answers**, because the daily and sandbox
hosts are rolled out ahead of the stable one and which host serves a given account varies.
Only the last failure is reported, so a user whose account lives on the stable host does not
see two spurious errors from hosts that were never going to answer.

`User-Agent: antigravity/cli/1.0.13 (aidev_client; os_type=…; arch=…)` is a compatibility
marker — the internal endpoints vary their response by client. The version is centralised in
`ProviderEndpoints.Antigravity.CLI_VERSION` and never inlined at a call site.

Scopes: `cloud-platform`, `userinfo.email`, `userinfo.profile`, `cclog`,
`experimentsandconfigs`. `cloud-platform` is broad — broader than a quota viewer needs — but
it is what the internal quota surface is gated behind for this client, and the scope set is
bound to the client id along with everything else.

**Project resolution.** `loadCodeAssist` is POSTed with a `metadata` block naming the calling
IDE (`IDE_UNSPECIFIED`, `PLATFORM_UNSPECIFIED`, `GEMINI`) — the endpoint rejects an empty
body. The project id is read from `cloudaicompanionProject`, then `projectId`, then `project`,
in that order. This runs **once, at login**, not on every sync, because the quota summary is
addressed by project and there is no lookup from an account to a project at fetch time. A
failure here raises `MalformedPayload` and is therefore fatal to *adding the account*, which
is the right place for it to fail: an account with credentials but no project id could
authenticate forever and never show a number.

## 4. Usage payload

`retrieveUserQuotaSummary` takes `{"project": "<project id>"}` and returns a payload that is
**already grouped**:

```json
{
  "groups": [
    {
      "displayName": "Gemini Pro",
      "description": "Gemini Flash, Gemini Pro",
      "buckets": [
        { "bucketId": "gemini-5h", "displayName": "5h limit", "window": "5h",
          "remainingFraction": 0.53, "resetTime": "2026-09-09T17:30:00Z" },
        { "bucketId": "gemini-weekly", "displayName": "Weekly", "window": "weekly",
          "remainingFraction": 0.56, "resetTime": "2026-09-10T20:00:00Z" }
      ]
    }
  ]
}
```

*(Synthetic. Invented bucket ids and numbers, no real project or account.)*

| Payload field | Maps to | Direction and units |
|---|---|---|
| `buckets[].remainingFraction` / `remaining_fraction` | `UsageWindow.usedPercent` | **This is the direction inversion in this provider.** The field is a fraction 0–1 of what is **REMAINING**. The app's model stores what was **CONSUMED** as a percentage, so the parser clamps to 0–1 and computes `(1 − fraction) × 100`. `remainingFraction: 0.53` becomes `usedPercent = 47.0`, which the UI renders back as "53 % remaining". A bucket with no fraction is skipped entirely — a missing row beats a bar drawn from an assumed zero. |
| `buckets[].window` | `UsageWindow.category` and `periodSeconds` | Free-form string, matched case-insensitively against a small alias set: `5h`/`five-hour`/`five_hour` → `FIVE_HOUR` (18 000 s), `weekly`/`week` → `WEEKLY` (604 800 s). Anything else → `OTHER` with a null period. |
| `buckets[].resetTime` / `reset_time` | `UsageWindow.resetAt` | Absolute instant → epoch millis via `Instants.parse`. Antigravity reports absolute stamps only, so this parser never needs the injected clock — the `nowMs` parameter exists to keep the four parsers' signatures identical. |
| `buckets[].bucketId` / `bucket_id` | `UsageWindow.id` | Used verbatim. Absent → `"<group-slug>-<window-or-index>"`. |
| `buckets[].displayName` / `display_name` | `UsageWindow.label` | Absent → the id, so a row never renders unlabelled. |
| `groups[].displayName` / `display_name` | `UsageWindow.group` | Carried onto every bucket in the group; the UI renders one card per group. |
| `groups[].description` | *(not parsed)* | Upstream uses it for the model list behind a group, e.g. "Gemini Flash, Gemini Pro". Not shown. |

Both spellings parse identically, and `AntigravityQuotaParserTest` asserts that a fully
snake_case payload produces exactly the same windows as its camelCase twin — this matters more
here than elsewhere, because *which* of the three hosts answers appears to decide which
spelling arrives.

## 5. Normalisation rules

**The grouping comes from the server, and that is the whole point.** Antigravity meters dozens
of models against a handful of shared quota buckets. `retrieveUserQuotaSummary` has already
collapsed them, so the parser reads groups and buckets and **never enumerates models**. Using
`fetchAvailableModels` instead — the obvious other route into this data — is what produces
hundreds of near-identical rows for what is really one limit, each showing the same number.
Choosing this endpoint is what lets the app satisfy the requirement of showing each shared
bucket exactly once, without any client-side de-duplication heuristics at all. There is no
de-duplication code in this parser because there is nothing to de-duplicate.

**Bucket ordering inside a group** is fixed: `FIVE_HOUR` first, then `WEEKLY`, then everything
else alphabetically by label. The two known windows are pinned because the UI reads
top-to-bottom as "soonest limit first"; unknown windows sort by label only, so their order
stays stable across refreshes regardless of what order the server emits them in. Group order
is left exactly as the server sent it.

**A bucket that cannot be rendered is dropped, not faked.** No `remainingFraction` means no
row. The siblings in the group survive.

**A group whose buckets all drop out disappears entirely**, rather than rendering as an empty
card with a title and nothing under it.

**Ids.** `bucketId` is used verbatim when present, which keeps rows stable across refreshes
even if display names change. The fallback (`<group-slug>-<window>` or `<group-slug>-<index>`)
is built from the group name so that an unnamed group still yields unique ids rather than
colliding with another group's fallbacks.

**No reset credits.** `supportsResetCredits` stays false. Google exposes no such facility.

**No plan.** `ProviderProfile.plan` is left null: Antigravity reports no plan name anywhere,
and the quota groups are the real plan signal. Guessing a tier from the group names would be
inventing information.

## 6. Known risks and upstream-change exposure

- **`loadCodeAssist` stops returning a project id.** Cost: **new accounts cannot be added.**
  Existing accounts keep working, because the project id is already stored as an account
  attribute. The error message says the account may not have Antigravity enabled, which is the
  likeliest real-world cause. This is the most severe single failure mode for this provider.
- **All three quota hosts fail.** Cost: this account's refresh fails and it keeps its previous
  numbers with an error attached. `SyncEngine` catches per account, so other accounts and
  providers are untouched. Only the last host's error is reported, which is a small
  diagnostic loss — if the daily host returns something interesting and the stable host
  returns a plain 404, the interesting error is discarded.
- **A host is added or removed upstream.** One-line edit to
  `ProviderEndpoints.Antigravity.QUOTA_URLS`; the order of that list is the fallback order.
- **`remainingFraction` renamed.** Cost: **every bucket disappears** — the field is the one
  value a row cannot be drawn without. The account then renders with zero windows, and
  `UsageSnapshot.severity` falls back to `ERROR` even though the sync recorded `OK`. An empty
  window list on a working account is the signal to check for this rename.
- **`remainingFraction` changes direction** (starts reporting consumed instead of remaining),
  or changes range (0–100 instead of 0–1). Neither throws. The first would show every bar
  inverted; the second would clamp every bucket to 0 % remaining and mark the account
  exhausted. There is nothing in the payload that would let the parser detect either, which is
  why the direction and the range are stated explicitly here and asserted in the tests.
- **`window` moves from free-form strings to an enum, or gains a new value.** Cost: those
  buckets become `OTHER` with no period and sort to the bottom. They still render with their
  label, percentage and reset time — an unrecognised window is better shown unclassified than
  dropped.
- **The response stops being pre-grouped.** Cost: no windows at all, and the app would need
  the model-enumeration route it currently avoids. This is the change that would require real
  rework rather than a one-line fix.
- **The client secret is rotated or the client id is retired.** Login stops working for
  everyone at once. Nothing the app can do about it locally; it is the accepted downside of
  depending on a first-party client's registration.
- **Google tightens `prompt`/`access_type` handling.** The symptom would be accounts that work
  for an hour and then report "Sign-in expired — reconnect this account", because no refresh
  token was ever issued.

## 7. Provenance

Behaviour derived from, and re-read at, these commits:

- **CLIProxyAPI @ `7fac6b15`** (2026-09-09) — `internal/auth/antigravity/constants.go` for the
  client id, the installed-app client secret, the authorize/token/userinfo endpoints, the
  loopback port and path, the scope list, and the `access_type=offline` + `prompt=consent`
  pairing.
- **CLIProxyAPI Management Center @ `ed5f1c48`** (2026-09-08) — `src/utils/quota/constants.ts`
  for the `loadCodeAssist` and `retrieveUserQuotaSummary` paths, the three-host fallback
  order, and the `antigravity/cli` user-agent shape.
- **CLIProxyAPI-Quota-Inspector @ `1895bc54`** — the finding that `retrieveUserQuotaSummary`
  returns pre-grouped `groups[]`/`buckets[]` with a `remainingFraction`, which is what makes
  the "never enumerate models" rule in §5 possible, and the cross-check on `bucketId`,
  `window` and `resetTime`.

The client id and the installed-app client secret are the public values the first-party client
ships; the secret's value is not repeated in this document, only in
`ProviderEndpoints.Antigravity` where it is used. No credential material, captured payload,
project id or account identifier from any real account appears in this repository, in its
tests, or in this document.

See also `docs/provider-auth-research.md` for the cross-provider comparison of the four login
flows, and `docs/security.md` for the full client-secret argument in context.
