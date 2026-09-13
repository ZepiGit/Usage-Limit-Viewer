# Codex (OpenAI / ChatGPT subscription)

Implementation: `app/src/main/kotlin/com/usagelimits/providers/codex/CodexProvider.kt`,
`CodexUsageParser.kt`, and the `ProviderEndpoints.Codex` block in
`app/src/main/kotlin/com/usagelimits/core/network/ProviderEndpoints.kt`.

## 1. Status

**Nothing in this document has been executed against a real ChatGPT account.** No login has
been completed, no usage payload has been fetched, and no reset credit has been spent from
this codebase in the environment it was written in. Every flow below is implemented from the
reference clients (see [Provenance](#7-provenance)) and is *unverified end to end*. Treat the
field names, the status-code semantics and the header requirements as well-sourced claims,
not as observations.

What is genuinely done:

- The device-authorization login, the token exchange and the refresh are implemented in full,
  including the non-standard "still pending" signalling OpenAI uses.
- Identity is read from the ID token; no extra profile round-trip is made.
- `CodexUsageParser` is complete and is the most heavily tested parser in the project —
  window classification, grouping, exhaustion, relative and absolute resets, and both key
  spellings each have cases in `CodexUsageParserTest`, all against synthetic fixtures.
- Reset credits are read, filtered and consumable.

What is unverified, beyond "it has never run":

- Whether OpenAI's device endpoint really returns 403 *and* 404 for pending, or only one of
  them, on a current deployment. The poll treats both as pending, so a genuine permission
  failure during polling is indistinguishable from a slow user and simply runs to the 15-minute
  deadline before reporting a cancelled login.
- Whether `/wham/usage` accepts a request without the `OpenAI-Beta` and `Originator` headers.
  The code deliberately omits them there — see the correction in §3.
- Whether `consumeResetCredit` is genuinely idempotent per `redeem_request_id`. It is treated
  as such (the call is never retried), which is the safe direction to be wrong in.
- `CodexUsageParser.parsePlan` and `availableCreditCount` are implemented and tested but have
  no production caller yet; the plan shown on an account comes from the ID token instead.

## 2. Auth flow

The app uses the **authorization-code flow with PKCE and a loopback redirect** — the flow the
Codex CLI itself runs — with **OpenAI's device authorization flow** as the fallback.

The browser flow is the default because it is the one the user notices least: sign in on
`auth.openai.com`, be redirected back, done. Its one precondition is that the app can bind the
redirect port the CLI's client registration pins, `1455`, and that is the same precondition
Claude (`54545`) and Antigravity (`51121`) have always had; the concerns that made the device
flow the original choice — a backgrounded process, a taken port — turned out to be ones the
loopback listener already handles for the other two providers. The device flow remains for the
one case the browser flow cannot help: the port is taken. It costs the user a code to carry
from this app into the browser, which is exactly the step the browser flow removes.

### 2a. Browser flow (default)

1. `beginLogin()` binds `127.0.0.1:1455` **before** building the URL, so a conflict is known
   now — and answered with the device flow below — rather than after the browser has minted
   a code that has nowhere to land.
2. It generates a PKCE pair and a `state`, holds them on the instance, and returns a
   `LoginChallenge.Redirect` whose URL is `https://auth.openai.com/oauth/authorize` with
   `response_type=code`, the client id, `redirect_uri=http://localhost:1455/auth/callback`,
   `scope=openid profile email offline_access`, the S256 challenge, the state, and the three
   parameters the first-party client sends: `id_token_add_organizations=true`,
   `codex_cli_simplified_flow=true`, `originator=codex_cli_rs`.
3. The UI opens the URL in Custom Tabs. The user signs in; the provider redirects the browser
   to the loopback URL; `LoopbackServer` answers it with a small page and hands the query
   parameters back.
4. `completeLogin()` checks the returned `state` against the one it generated (constant
   time) before reading the code, then exchanges the code exactly as in step 7 below, with
   `redirect_uri=http://localhost:1455/auth/callback` and the verifier it generated itself.

   The check accepts the state exactly, or the state followed by a `.`-separated suffix.
   OpenAI appends onboarding metadata to the value it echoes for some accounts —
   `<state>.onboarding_entrypoint=life_sciences` is the form the Codex CLI itself tolerates
   in `login_callback_result_from_state` — and an exact comparison answered those redirects
   with `400` and then waited for one that never came. The random part is still required in
   full, so the tolerance gives a forged redirect nothing (`Pkce.stateMatches`).

PKCE here works as intended: the verifier is created on the device and never leaves it, so a
code intercepted on its way back is worthless on its own.

Two things around the flow, both found by a sign-in that stopped working on a phone:

- **A retry starts clean.** `AddAccountViewModel.startLogin` waits for the previous attempt
  to finish unwinding before it begins the next one. Cancelling alone was not enough: the old
  attempt's listener is closed in a `finally` that runs on another thread, so a retry could
  find port 1455 still bound — which this provider answers by quietly switching to the device
  flow — or have its freshly stored PKCE pair wiped by the old attempt's clean-up, which
  failed the retry with "Login was not started" the moment its redirect arrived. The provider
  also only clears the pair that belongs to the attempt being cleaned up.
- **The device code is a button, not only a fallback.** `CodexProvider` implements
  `DeviceCodeLoginCapable`, and the add-account screen offers "Use a device code" under the
  browser wait and under a failed attempt. The browser flow needs the browser to reach this
  app on `localhost`, and not every phone lets it — a browser that will not open a plaintext
  local address, a system that kills the app while the browser is in front. The Codex CLI
  ships `--device-auth` for the same reason. "Try again" after a failed device-code attempt
  repeats the device code, not the browser.

### 2b. Device flow (fallback, when port 1455 is taken)

1. `beginLogin()` POSTs `{"client_id": …}` to the device *usercode* endpoint.
2. The response yields a user code — accepted as either `user_code` or `usercode`, because
   upstream has shipped both spellings — plus a `device_auth_id` and a poll `interval`
   (default 5s when absent).
3. The user code and the device id are packed into one string as `code|deviceAuthId` and
   returned as a `LoginChallenge.DeviceCode`, so `completeLogin` needs no instance state.
   Only the half before the separator is ever shown to the user
   (`CodexProvider.displayCode`). The challenge expires 15 minutes from issue.
4. The UI shows the code and opens `https://auth.openai.com/codex/device`. The user types the
   code there and approves.
5. `completeLogin()` polls the device *token* endpoint with `{device_auth_id, user_code}` at
   the provider's interval, with HTTP retries disabled so the generic retry/backoff cannot
   swallow a pending response. **403 and 404 both mean "not approved yet"** — OpenAI does not
   send the RFC 8628 `authorization_pending` error body — so those two continue the loop. A
   poll that does not get through (a transport failure, a 5xx) continues it as well: the user
   is in the browser while this runs and one lost poll is not a failed login. Anything else
   is terminal. The loop is bounded by the 15-minute deadline, after which it raises
   `LoginCancelled`.
6. A 2xx returns an `authorization_code` **and the PKCE `code_verifier`**, both of which the
   provider generated.
7. `exchangeCode()` POSTs a form body to `https://auth.openai.com/oauth/token` with
   `grant_type=authorization_code`, the client id, the code, the returned verifier, and
   `redirect_uri=https://auth.openai.com/deviceauth/callback`. That redirect URI is never
   navigated to; it is sent because the code was issued against it and the exchange is
   rejected otherwise.
8. The token response is normalised into `OAuthCredentials`. `expires_in` is converted to an
   absolute deadline using the injected clock.

**The PKCE caveat, stated honestly.** In a normal PKCE flow the client generates the verifier,
so possession of the code alone is useless to anyone else. Here the *provider* generates both
the verifier and the challenge and hands them back together with the code. The verifier still
never crosses an untrusted channel — it arrives over TLS in response to a request this app
made, holding a device id only this app knows — but PKCE in this flow is not the
client-binding guarantee it normally is. It is closer to a second secret travelling the same
path as the first. This is a property of OpenAI's device endpoint, not a choice the app makes,
and it is one more reason the browser flow — with a verifier this app generates — is the
default and this one the fallback.

Refresh is an ordinary RFC 6749 refresh-token grant. A refresh response that omits a new
refresh token means "keep the old one", and the code carries it forward rather than storing a
null and deauthenticating the account an hour later.

Identity never costs a request: `fetchProfile` parses the ID token and reads the
`https://api.openai.com/auth` claim for `chatgpt_account_id` and `chatgpt_plan_type`, falling
back to `sub` for the account id. The account id is also stored as the `chatgpt_account_id`
attribute, because every usage call has to name it in a header.

## 3. Endpoints and headers

| Method and URL | Purpose | Headers sent | Provenance |
|---|---|---|---|
| `https://auth.openai.com/oauth/authorize` | Browser sign-in page (default flow) | — (opened in a browser); redirects to `http://localhost:1455/auth/callback` | OAuth standard (RFC 6749 §4.1.1, RFC 7636); the extra parameters are the first-party CLI's |
| `POST https://auth.openai.com/api/accounts/deviceauth/usercode` | Start device login (fallback) | `Accept`, `User-Agent` | Internal endpoint observed in the first-party CLI — NOT a stable public API |
| `https://auth.openai.com/codex/device` | Page the user types the code on | — (opened in a browser) | Internal endpoint observed in the first-party CLI — NOT a stable public API |
| `POST https://auth.openai.com/api/accounts/deviceauth/token` | Poll for approval | `Accept`, `User-Agent` | Internal endpoint observed in the first-party CLI — NOT a stable public API (RFC 8628-shaped, but the pending signalling is not RFC 8628) |
| `POST https://auth.openai.com/oauth/token` | Code exchange and refresh | `Accept`, `User-Agent`; form body | OAuth standard (RFC 6749 §4.1.3 and §6, RFC 7636) |
| `GET https://chatgpt.com/backend-api/wham/usage` | Usage windows | `Authorization`, `Content-Type: application/json`, `Accept`, `User-Agent`, `Chatgpt-Account-Id` | Internal endpoint observed in the first-party CLI — NOT a stable public API |
| `GET https://chatgpt.com/backend-api/wham/rate-limit-reset-credits` | Reset credits | the usage headers **plus** `OpenAI-Beta: codex-1` and `Originator: Codex Desktop` | Internal endpoint observed in the first-party CLI — NOT a stable public API |
| `POST https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume` | Spend one credit | same as the reset-credit read | Internal endpoint observed in the first-party CLI — NOT a stable public API |

The client id `app_EMoamEEZ73f0CkXaXp7hrann` is the public identifier the official CLI ships.
It is not a secret and there is no client secret anywhere in this provider.

**Correction to the original specification.** The brief stated that `OpenAI-Beta: codex-1` and
`Originator: Codex Desktop` are required on Codex calls generally. They are not. They are sent
**only** on the two reset-credit endpoints, which is where the reference client sends them;
`/wham/usage` gets neither. `ProviderEndpoints.Codex.RESET_CREDIT_HEADERS` exists precisely so
that this stays true by construction — `fetchUsage` builds its headers from `usageHeaders()`
alone, and the reset-credit calls are the only ones that add the map. Sending an unnecessary
beta header would be a small thing to get wrong, but it would also be a claim about a client
identity the app does not have.

The `User-Agent` (`codex-tui/0.149.1 (Android; arm64) UsageLimits`) is a compatibility marker:
the backend varies its response shape by client. It names the app in plain text rather than
impersonating the desktop client, and the app does no TLS-fingerprint spoofing, header-order
mimicry or bot-detection evasion of any kind for this or any other provider.

## 4. Usage payload

`/wham/usage` returns three families of limit, each shaped the same way — a `primary_window`
and a `secondary_window`, with the exhaustion flags on the parent rather than on the windows:

```json
{
  "plan_type": "team",
  "rate_limit": {
    "limit_reached": false,
    "primary_window":   { "used_percent": 18.0, "limit_window_seconds": 18000,
                          "reset_after_seconds": 3600 },
    "secondary_window": { "used_percent": 63.0, "limit_window_seconds": 2592000,
                          "reset_at": "2026-10-01T00:00:00Z" }
  },
  "code_review_rate_limit": {
    "primary_window": { "used_percent": 30.0, "limit_window_seconds": 18000 }
  },
  "additional_rate_limits": [
    { "name": "Sora video",
      "primary_window": { "used_percent": 12.0, "limit_window_seconds": 18000 } }
  ]
}
```

*(Synthetic. Invented numbers, no real account.)*

| Payload field | Maps to | Direction and units |
|---|---|---|
| `used_percent` / `usedPercent` | `UsageWindow.usedPercent` | **Percent CONSUMED**, 0–100, copied through unchanged. The UI displays `remainingPercent`, which the model derives as `100 − used` clamped to 0–100. A window at `used_percent: 63` renders as "37 % remaining". |
| `limit_window_seconds` / `limitWindowSeconds` | `UsageWindow.periodSeconds`, and via `WindowCategory.fromPeriodSeconds` the `category` | Seconds. 18 000 → `FIVE_HOUR`, 604 800 → `WEEKLY`, 28–31 days → `MONTHLY`, anything else → `OTHER`. |
| `reset_at` / `resetAt` | `UsageWindow.resetAt` | Absolute instant → epoch millis via `Instants.parse`, which accepts ISO-8601 with or without an offset as well as epoch seconds or millis. |
| `reset_after_seconds` / `resetAfterSeconds` | `UsageWindow.resetAt` | Relative offset, resolved against the injected clock. Only consulted when `reset_at` is absent — an absolute stamp always wins. |
| `limit_reached` / `limitReached`, `allowed` | `UsageWindow.exhausted`, and `usedPercent` when no percentage is reported | `limit_reached: true` **or** `allowed: false` means the whole family is spent. Both windows of that family become 100 % consumed and exhausted. |
| `name` / `limit_name` / `limitName` (in `additional_rate_limits[]`) | `UsageWindow.group` and the label prefix | Free text. Absent → `"Additional N"` by position. |
| `plan_type` / `planType` | *(parsed by `parsePlan`, currently unused)* | The plan shown on an account comes from the ID token's `chatgpt_plan_type` instead. |

Every lookup accepts both the snake_case and the camelCase spelling, and
`CodexUsageParserTest` asserts that a fully camelCase payload parses to exactly the same
windows as its snake_case twin.

## 5. Normalisation rules

**Classification is by declared duration, never by slot position.** This is the single most
important rule in the parser. Upstream places a *monthly* window in the `secondary_window`
slot on team plans and a *weekly* one on individual plans, so a parser that trusted the
position would confidently label a month as a week and tell the user their quota resets in
three days. `CodexUsageParser.classify` reads `limit_window_seconds` from each of the two
windows and sorts them into a short slot (`FIVE_HOUR`) and a long slot (`WEEKLY` or
`MONTHLY`). Position is used *only* as a fallback for legacy payloads that omit the duration
entirely, and in that case the windows are also left `OTHER` with a null period and a generic
"Limit" label — an unknown duration stays unknown rather than being guessed at.

**Grouping** mirrors the payload's three families. `rate_limit` windows carry no group and no
label prefix, so ordinary Codex usage reads as plain "5h limit" and "Weekly".
`code_review_rate_limit` becomes the group "Code review" with labels like
"Code review · Weekly". Each entry in `additional_rate_limits[]` becomes its own group named
after the entry. The UI renders one card per group, so a plan with extras gets extra cards
rather than a longer undifferentiated list.

**Ids are stable and unique.** `codex-short` / `codex-long`, `code-review-short` /
`code-review-long`, and `additional-<slugified-name>-<index>-short` / `-long`. The index is in
the id because two additional limits could legitimately share a display name, and a duplicate
id would collapse them into one row.

**De-duplication of reset credits.** Two sources describe the same credits: the dedicated
`/rate-limit-reset-credits` endpoint and a copy embedded in the usage payload as
`rate_limit_reset_credits`. The dedicated endpoint is authoritative and is tried first; the
embedded copy is used only if that call throws, so a failure there costs the *freshness* of
the credit list rather than the entire usage refresh. Credits are filtered to
`status == "available"` and, when the field is present, `reset_type == "codex_rate_limits"` —
offering a spent credit or a credit for an unrelated reset would put a button in front of the
user that cannot work.

**Exhaustion** is `limit_reached || allowed == false || used_percent >= 100`. A family that is
allowed but reports no percentage yields `usedPercent = null`, which the model surfaces as a
null `remainingPercent` and `Severity.ERROR` — "unknown", not "0 % used".

## 6. Known risks and upstream-change exposure

Everything except the token endpoint is an internal API. The realistic failure modes, and what
each costs:

- **A window field is renamed or dropped.** Cost: one row, or one row's percentage. Missing
  `used_percent` with no exhaustion flag yields a window with an unknown percentage rather
  than a wrong one; a missing `limit_window_seconds` yields an `OTHER` window that still
  renders with its label and reset time. Nothing throws.
- **`rate_limit` itself is renamed.** Cost: the whole account renders with **zero** windows.
  This is the one degradation that is worse than it looks: `UsageSnapshot.severity` falls back
  to `ERROR` when there are no windows, so the account shows as failed rather than empty, but
  the sync itself is recorded as `OK`. An empty window list is the signal to check for an
  upstream rename.
- **The whole payload shape changes.** `JsonSupport.parseObject` throws
  `MalformedPayload`, the sync engine catches it per account, and the account keeps its
  previous numbers with "Unexpected response from provider" attached. Other accounts and other
  providers are unaffected — `SyncEngine` isolates each account.
- **The device endpoints change their pending signalling.** If OpenAI adopts the RFC 8628
  `authorization_pending` body on a 200 response, `pollForAuthorization` would return that body
  as if it were an authorization and the exchange would fail with `MalformedPayload` on the
  missing `authorization_code`. Login breaks; nothing else does.
- **The pending-404 detection is string-coupled.** The 404 branch matches on the text `"404"`
  inside a `ProviderException.Unexpected` message that `HttpClient` formats as `"HTTP 404"`.
  That is a real, if small, coupling between two files: changing the message format in
  `HttpClient` would silently turn "keep waiting" into "fail immediately". It is called out
  here rather than hidden because it is the sort of thing that only breaks in production.
- **`/wham/usage` starts requiring the beta headers.** Usage would fail with 403 →
  "Access denied for this account". The fix is one line in `ProviderEndpoints`.
- **Reset credits disappear or change type strings.** Cost: the account detail screen's reset
  credit card reads "None available" and loses its button, and the Overview card for the
  account drops its "N available" line. The Resets screen is not involved — it lists upcoming
  window rollover times and nothing else. Usage is untouched, because credits are fetched
  inside a `runCatching` with the embedded copy as a fallback.
- **Bot detection.** ChatGPT's backend sits behind the same kind of edge protection Anthropic
  uses. The app sends plain, honest HTTPS with a user agent that names it. If the edge rejects
  that, the correct outcome is a clear error, not an evasion attempt; there is no TLS
  fingerprint mimicry, header-order matching or CAPTCHA handling anywhere in this codebase, and
  adding any would be out of scope by policy, not by oversight.

The consume path deserves its own note: it is never retried, on purpose. A retry after the
server has already committed a spend risks burning a second credit, which is a user-visible
loss the app cannot undo. A fresh `redeem_request_id` UUID per attempt is what makes the call
safe on the provider side; the app declines to rely on that assumption twice.

## 7. Provenance

Behaviour derived from, and re-read at, these commits:

- **CLIProxyAPI @ `7fac6b15`** (2026-09-09) — `internal/auth/codex/openai_auth.go` for the
  authorization-code/PKCE flow (the authorize URL, its extra parameters and the `:1455`
  redirect), the token endpoint and the refresh grant;
  `sdk/auth/codex_device.go` for the device flow, the 403/404 pending semantics, and the
  server-generated PKCE pair.
- **CLIProxyAPI Management Center @ `ed5f1c48`** (2026-09-08) — `src/utils/quota/constants.ts`
  for the usage and reset-credit URLs and the header sets; `codex/data.ts`
  (`fetchCodexResetCredits`) for the fact that `OpenAI-Beta` and `Originator` are scoped to
  the reset-credit calls only, which is the correction recorded in §3.
- **CLIProxyAPI-Quota-Inspector @ `1895bc54`** — cross-check on the `/wham/usage` field names,
  in particular `limit_window_seconds` as the classification key and the presence of a monthly
  window in the secondary slot.

The client id and the user-agent string are the public values those clients ship. No
credential material, captured payload or account identifier from any real account appears in
this repository, in its tests, or in this document.

See also `docs/provider-auth-research.md` for the cross-provider comparison of the four login
flows, and `docs/security.md` for the credential-storage argument.
