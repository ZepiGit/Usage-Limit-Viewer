# Claude (Anthropic subscription)

Implementation: `app/src/main/kotlin/com/usagelimits/providers/claude/ClaudeProvider.kt`,
`ClaudeUsageParser.kt`, and the `ProviderEndpoints.Claude` block in
`app/src/main/kotlin/com/usagelimits/core/network/ProviderEndpoints.kt`.

## 1. Status

**Nothing in this document has been executed against a real Anthropic account.** No login has
been completed and no usage payload has been fetched from this codebase in the environment it
was written in. Every flow below is implemented from the reference client (see
[Provenance](#7-provenance)) and is *unverified end to end*.

Claude also carries the project's **single largest feasibility risk**, and it is worth stating
before anything else. The reference implementation reaches Anthropic through uTLS TLS
fingerprint mimicry and strict header ordering, because Anthropic's edge runs bot detection.
**This app deliberately does none of that.** It sends a plain, honest HTTPS request from
OkHttp with a normal Android TLS stack. If Anthropic's edge rejects that, the app reports a
clear error and the account shows as failed. It does not, and will not, spoof a fingerprint,
match a header order, solve a challenge or otherwise evade a bot-detection system. That is a
policy decision, not an omission, and it means Claude support may simply not work — the honest
version of "unverified" here is closer to "may be structurally blocked" than for the other
three providers.

What is implemented:

- Authorization code + PKCE over a loopback redirect, including the `code#state` quirk and a
  constant-time state comparison.
- JSON token exchange and refresh against `platform.claude.com`.
- Profile and usage reads against `api.anthropic.com` behind the OAuth beta header.
- A full parser with tests covering the fixed window list, the Fable de-duplication, unknown
  key tolerance, both key spellings and exhaustion.

What is unverified beyond the bot-detection question:

- Whether the loopback port `54545` is still what Anthropic's client registration pins. The
  app must bind exactly that port; if it is wrong, the redirect never arrives and login times
  out after five minutes with "Sign-in was cancelled".
- Whether `iguana_necktie` is still the live key for the Fable weekly window (§4).
- Whether the `axios/1.15.2` user agent on the token endpoint is still accepted, or still
  required.

## 2. Auth flow

The app uses the **authorization-code flow with PKCE and a loopback redirect** (RFC 8252
§7.3). Unlike Codex and xAI there is no device-code alternative offered for this client, so
the browser round-trip is not a choice between two flows — it is the only flow available.

Why the loopback variant rather than a custom URI scheme, which is the more usual Android
answer: the redirect URI `http://localhost:54545/callback` is pinned by Anthropic's client
registration. The app cannot substitute `usagelimits://callback` and have the authorization
server accept it, so it binds the port the registration names and runs a one-shot local
listener for the duration of the login. This is the flow the reference desktop client uses,
and here Android is adapting to it rather than the other way round.

The steps as implemented:

1. `beginLogin()` generates a PKCE pair and a CSRF state (`Pkce.generate`,
   `Pkce.generateState`) and holds both **in memory on the provider instance**. They are
   deliberately not carried inside `LoginChallenge.Redirect`, because that value travels
   through the UI layer and the verifier is the one item in this flow that must never leave
   the process. They are cleared as soon as the redirect resolves, in a `finally`.
2. The authorization URL is built with `code=true` first, then `client_id`,
   `response_type=code`, `redirect_uri`, `scope`, `code_challenge`, `code_challenge_method`
   and `state`. `code=true` is a non-standard parameter Anthropic requires to select the
   code-returning variant of the endpoint; without it the flow fails later and opaquely, which
   is why it is explicit and first in the map.
3. The UI opens that URL in the system browser. The user authenticates with Anthropic
   directly — the app never sees a password.
4. `completeLogin()` starts a `LoopbackServer` on port 54545 and waits up to five minutes for
   the redirect. The server is closed in a `finally` whether or not the browser comes back; a
   stale listener would make the next login attempt fail to bind the pinned port.
5. An `error` parameter in the redirect becomes `LoginCancelled` with the provider's
   description.
6. **The `code#state` quirk.** Anthropic sometimes returns `code#state` in a single parameter.
   The token endpoint rejects a code with the fragment still attached, so the code is split at
   `#`; and when a fragment is present, it — not the query parameter — is the state the
   exchange must echo back.
7. CSRF check: *every* state value the browser returned, through whichever channel, must equal
   the one this instance generated, compared with `Pkce.constantTimeEquals`. An empty set of
   returned states is also a failure. Any mismatch aborts before the code is spent.
8. The code is exchanged at `https://platform.claude.com/v1/oauth/token` with a **JSON** body
   carrying `grant_type`, `code`, `redirect_uri`, `client_id`, `code_verifier` and `state`.

Two details differ from every other provider here. The token endpoint is on
`platform.claude.com`, not `api.anthropic.com` — the two hosts are not interchangeable and
using the API host fails. And the body is JSON, where RFC 6749 specifies form encoding and the
other three providers use it; `HttpClient.jsonBody` is used instead of `formBody` for exactly
this reason.

Refresh is the same endpoint and the same JSON convention, with `grant_type=refresh_token`. A
response that omits a new refresh token means "keep the old one", and the code carries it
forward.

Identity costs one request: `GET /api/oauth/profile` returns
`account: {uuid, email, display_name, has_claude_max, has_claude_pro}`. The `uuid` is the
stable key; the email address is only a fallback, because an account whose profile omits the
uuid can still be stored and re-synced rather than failing to be added at all. The plan label
is derived from the two booleans — `has_claude_max` → "Max", `has_claude_pro` → "Pro",
otherwise null. Nothing is guessed.

## 3. Endpoints and headers

| Method and URL | Purpose | Headers sent | Provenance |
|---|---|---|---|
| `GET https://claude.ai/oauth/authorize` | Browser authorization | — (opened in the system browser) | OAuth standard (RFC 6749 §4.1.1, RFC 7636, RFC 8252) **plus the non-standard `code=true` parameter** |
| `http://localhost:54545/callback` | Loopback redirect target | — (local listener) | OAuth standard (RFC 8252 §7.3); the specific port is pinned by Anthropic's client registration |
| `POST https://platform.claude.com/v1/oauth/token` | Code exchange and refresh | `Content-Type: application/json`, `Accept`, `User-Agent: axios/1.15.2`; **JSON** body | OAuth standard (RFC 6749 §4.1.3 and §6) — with a JSON body instead of the form encoding the RFC specifies |
| `GET https://api.anthropic.com/api/oauth/profile` | Account identity and plan | `Authorization`, `anthropic-beta: oauth-2025-04-20`, `Content-Type`, `Accept` | Internal endpoint observed in the first-party CLI — NOT a stable public API |
| `GET https://api.anthropic.com/api/oauth/usage` | Usage windows | same as the profile call | Internal endpoint observed in the first-party CLI — NOT a stable public API |

The client id `9d1c250a-e61b-44d9-88ed-5944d1962f5e` is the public identifier the official
client ships. There is no client secret in this provider.

`anthropic-beta: oauth-2025-04-20` gates the OAuth-token surface of `api.anthropic.com`;
without it these two paths 404. It is sent on the read endpoints only. Conversely
`User-Agent: axios/1.15.2` is sent on the **token calls only**, where the endpoint refuses a
request with no user agent at all — the read endpoints get no user-agent header from this app.
Neither header is an attempt to disguise the client; they are the minimum the endpoints accept.

Requested scopes are `user:profile user:inference user:sessions:claude_code user:mcp_servers
user:file_upload`. That list is wider than a usage viewer needs — `user:inference` in
particular is a capability this app never exercises, since it makes no completion requests of
any kind. It is requested because the client registration binds the scope set; narrowing it
is not something the client can do unilaterally. The app's own restraint is structural
instead: `UsageProvider` has no request, completion or chat entry point anywhere in its
interface.

## 4. Usage payload

`/api/oauth/usage` is **flat**. There is no wrapper object: each window sits at the top level
under its own key, and every one of them reports `utilization` and `resets_at`. A separate
`limits[]` array describes per-model scoped windows.

```json
{
  "five_hour":       { "utilization": 42.5, "resets_at": "2026-09-09T17:30:00Z" },
  "seven_day":       { "utilization": 47.0, "resets_at": "2026-09-14T00:00:00Z" },
  "seven_day_opus":  { "utilization": 10.0, "resets_at": null },
  "iguana_necktie":  { "utilization": 5.0,  "resets_at": "2026-09-14T00:00:00Z" },
  "limits": [
    { "kind": "weekly_scoped", "percent": 12.0, "is_active": true,
      "scope": { "model": { "display_name": "Fable" } } }
  ]
}
```

*(Synthetic. Invented numbers, no real account.)*

| Payload field | Maps to | Direction and units |
|---|---|---|
| `<window>.utilization` | `UsageWindow.usedPercent` | **Percent CONSUMED**, 0–100, copied through unchanged. `utilization: 42.5` renders as "57.5 % remaining", because the UI displays the derived `remainingPercent`. |
| `<window>.resets_at` / `resetsAt` | `UsageWindow.resetAt` | Absolute instant → epoch millis via `Instants.parse`. An explicit `null` yields a null `resetAt` and the window still renders — it simply shows no countdown. |
| the window's **key** | `UsageWindow.periodSeconds` and `category` | Derived, not read: the payload never states a duration. `five_hour` → 18 000 s → `FIVE_HOUR`; every other key Anthropic exposes is a seven-day window → 604 800 s → `WEEKLY`. |
| the window's **key** | `UsageWindow.id` | Underscores become hyphens, e.g. `seven_day_opus` → `seven-day-opus`. |
| `limits[].percent` | `UsageWindow.usedPercent` for the Fable row | **Percent CONSUMED**, same direction as `utilization`. |
| `limits[].kind`, `limits[].scope.model.display_name`, `limits[].is_active` | selection only | Used to find the Fable entry; see §5. |

The recognised window keys and their labels live in
`ProviderEndpoints.Claude.USAGE_WINDOW_KEYS`, in display order: `five_hour` ("5h limit"),
`seven_day` ("Weekly"), `seven_day_oauth_apps`, `seven_day_opus`, `seven_day_sonnet`,
`seven_day_cowork`, and `iguana_necktie` ("Weekly (Fable)").

**Correction to the original specification.** The brief named the Fable weekly window
`seven_day_fable`. That key is **not** what upstream serves. The current key is
**`iguana_necktie`** — an internal codename, not a typo and not a placeholder — and that is
what `ProviderEndpoints.Claude.USAGE_WINDOW_KEYS` and `ClaudeUsageParser.FABLE_WINDOW_KEY`
match on. The app labels it "Weekly (Fable)" in the UI, so the codename never reaches the
user, and the parser tolerates its absence, so if Anthropic renames it again the cost is one
missing row rather than a failed parse. This is also the field most likely to change, since a
codename is by nature temporary.

## 5. Normalisation rules

**A fixed key list, and unknown keys are ignored.** The parser walks
`USAGE_WINDOW_KEYS` in order and reads only those keys. Anything else at the top level —
`organization`, a scalar, a window Anthropic adds next month — is skipped silently. That
asymmetry is intentional: a new window upstream costs nothing (it is simply not shown until
the key is added here), and a removed window costs exactly one row. Neither can break the
screen. `ClaudeUsageParserTest` asserts both directions, including a fixture with an unknown
`thirty_day_quokka` window and a non-object top-level value.

**Period is derived from the key, not the payload.** Anthropic states no duration anywhere in
the usage response. Rather than leave every window `OTHER`, the parser assigns 18 000 s to
`five_hour` and 604 800 s to everything else, which is true of every key Anthropic currently
exposes. This is the one place in the project where a duration is asserted rather than read,
and it is asserted from the key name — so a future `thirty_day_*` key would be mislabelled
weekly if it were added to the list without also adding its period. Noted here so the next
person adding a key knows to check.

**Fable de-duplication.** Fable's weekly quota can arrive twice: once under the opaque
`iguana_necktie` key, and once as an entry in `limits[]` with
`kind == "weekly_scoped"` and `scope.model.display_name` of "Fable" (or "Fable 5"). Showing
both would render one quota as two bars with two different numbers. The rule is: **when a
matching `limits[]` entry exists, it supersedes the key.** The `limits[]` entry is the better
source — it is self-describing, it names the model, and it carries an `is_active` flag —
so `iguana_necktie` is dropped from the output and a single window with id `seven-day-fable`
and label "Weekly (Fable)" takes its place, carrying the entry's `percent` and its
`resets_at`. When no matching entry exists, the `iguana_necktie` key is kept as-is under the
same "Weekly (Fable)" label with id `iguana-necktie`. A candidate entry must carry a non-null
`percent` to be considered at all; among several candidates the one marked `is_active` wins,
otherwise the first. Both branches are directly tested.

**Grouping** is not used for Claude. Every window is a top-level quota on one account, so
`UsageWindow.group` is left null and the UI renders one flat list in the declared order.

**Exhaustion** is `utilization >= 100`. There is no parent flag to consult, unlike Codex.

**No reset credits.** `supportsResetCredits` stays false and `UsageResult.resetCredits` stays
empty. Anthropic exposes no such facility and the app does not invent one.

## 6. Known risks and upstream-change exposure

- **Bot detection at the edge — the headline risk.** If `api.anthropic.com` or
  `platform.claude.com` starts refusing requests that do not present the reference client's
  TLS fingerprint and header order, this provider stops working and there is no in-scope fix.
  The failure surfaces as `Forbidden` → "Access denied for this account", or as a
  `MalformedPayload` if a challenge page is returned in place of JSON. The account keeps its
  last known numbers and is marked failed; every other account and provider continues to sync
  normally. The app will not respond to this by evading.
- **`iguana_necktie` renamed again.** Cost: one row disappears — unless a matching `limits[]`
  entry exists, in which case the Fable row survives via the array path and nothing is lost at
  all. This is the most likely single change and the app is already positioned for it.
- **A window key is added.** Cost: nothing visible; the new window is ignored until the key is
  added to `USAGE_WINDOW_KEYS`. One-line fix in `ProviderEndpoints`.
- **`utilization` renamed, or its direction inverted.** A rename costs the percentage on every
  window (rows still render with unknown values). An *inverted direction* — a provider
  switching to reporting what is left — would not throw and would not look wrong: every bar
  would silently show the complement of the truth. There is no defence against this in the
  payload itself, which is why the direction is stated explicitly in §4 and asserted in the
  parser tests.
- **The loopback port changes, or the redirect never arrives.** Login times out after five
  minutes with "Sign-in was cancelled". Nothing is left bound: the server is closed in a
  `finally`.
- **`code=true` stops being required, or a second such parameter appears.** The former is
  harmless; the latter breaks login opaquely, which is the failure mode that parameter exists
  to avoid in the first place.
- **The beta header version rolls forward.** Both read endpoints 404, which `HttpClient` maps
  to `Unexpected("HTTP 404")` → "Refresh failed". One-line fix.
- **Whole-payload shape change.** `MalformedPayload`, caught per account by `SyncEngine`; the
  account keeps its previous numbers with "Unexpected response from provider" attached.
- **Zero windows parsed.** If every recognised key disappears at once, the account renders
  with no windows; `UsageSnapshot.severity` falls back to `ERROR` even though the sync itself
  recorded `OK`. An empty window list is the signal to check for an upstream rename.

## 7. Provenance

Behaviour derived from, and re-read at, these commits:

- **CLIProxyAPI @ `7fac6b15`** (2026-09-09) — `internal/auth/claude/anthropic_auth.go` for the
  client id, the `code=true` authorize parameter, the `platform.claude.com` token endpoint,
  the JSON exchange body, the pinned loopback port, the scope list, the `code#state` handling
  and the `axios` user agent. The same file is the source of the uTLS/header-ordering
  behaviour this app declines to copy.
- **CLIProxyAPI Management Center @ `ed5f1c48`** (2026-09-08) — `src/utils/quota/constants.ts`
  for the profile and usage URLs, the `anthropic-beta` value, the flat window key list, and
  the `iguana_necktie` key that corrects the brief's `seven_day_fable`.
- **CLIProxyAPI-Quota-Inspector @ `1895bc54`** — cross-check on the `/api/oauth/usage` field
  names, the `utilization` direction, and the shape of `limits[]` including `kind`,
  `is_active` and `scope.model.display_name`.

No credential material, captured payload or account identifier from any real account appears
in this repository, in its tests, or in this document.

See also `docs/provider-auth-research.md` for the cross-provider comparison of the four login
flows, and `docs/security.md` for the credential-storage argument and the reasoning behind
refusing the fingerprint-mimicry route.
