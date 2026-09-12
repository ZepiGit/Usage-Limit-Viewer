# xAI / Grok

Implementation: `app/src/main/kotlin/com/usagelimits/providers/xai/XaiProvider.kt`,
`XaiBillingParser.kt`, and the `ProviderEndpoints.Xai` block in
`app/src/main/kotlin/com/usagelimits/core/network/ProviderEndpoints.kt`.

## 1. Status

**Nothing in this document has been executed against a real xAI account.** No login has been
completed and no billing payload has been fetched from this codebase in the environment it was
written in. Every flow below is implemented from the reference client (see
[Provenance](#7-provenance)) and is *unverified end to end*.

What is implemented:

- The RFC 8628 device authorization grant, with endpoints resolved from xAI's OIDC discovery
  document and both of them validated before a byte is sent to them.
- Full poll handling: `authorization_pending`, `slow_down`, `expired_token`, `access_denied`,
  and unknown terminal codes.
- Identity from the ID token where one is issued, with `/v1/me` as the fallback.
- Both billing views, parsed independently and merged, with every amount treated as integer
  cents.

What is unverified beyond "it has never run":

- Whether xAI's discovery document still names a device authorization endpoint, and whether
  both endpoints still live under `x.ai`. If either moves to a different registrable domain,
  `validateEndpoint` will refuse it and login will fail closed — deliberately, but it will
  fail.
- Whether `authorization_pending` arrives as a 200 with an error body, as a 400 with an error
  body, or as a bare 403. The poll handles all three, which means it also cannot distinguish a
  genuine permission failure mid-poll from a slow user; that case runs to the expiry deadline.
- The exact field names in `/v1/billing`. `monthlyLimit`, `used`, `onDemandCap`,
  `onDemandUsed` and `billingPeriodEnd` are the shapes the reference client reads; the
  parser accepts both spellings of each and drops anything it does not recognise. The
  captured production shape carries spend as `includedUsed` (and `totalUsed`), which the
  parser now reads alongside `used`. When a limit is present with no spend figure under any
  of those names, the window is emitted with an UNKNOWN percentage — until 10 September both
  platforms substituted zero and showed a 90 %-spent account as untouched. The credit view is
  the mirror image: an absent `creditUsagePercent` inside the live period IS a zero, because
  proto3 omits it — see "The implicit zero" under normalisation.
- Whether `/v1/me` is reachable with the granted scopes. It is only used when no ID token is
  issued, so a failure there is invisible in the common case.

## 2. Auth flow

The app uses the **RFC 8628 device authorization grant**, and of the four providers here this
is the one whose native flow is *already* the right one for a phone. There is no redirect at
all: no loopback port to bind, no fixed port pinned by a registration, nothing that has to
survive the app being backgrounded while a browser is open, and no assumption that the browser
is even on the same device. The user reads a short code, types it at a URL xAI supplies, and
the app polls. The reference client uses the same flow, so unlike Codex there was no
alternative to reject — this is simply the flow xAI offers, and it happens to be the best fit.

The steps as implemented:

1. `discover()` fetches `https://auth.x.ai/.well-known/openid-configuration` and reads
   `device_authorization_endpoint` and `token_endpoint`.
2. **Both are validated before use.** `validateEndpoint` requires an `https` scheme and a host
   that is exactly `x.ai` or a dot-anchored subdomain of it. The dot anchor is what stops
   `notx.ai` and `x.ai.example.com` from passing. This is the control that makes runtime
   discovery safe at all: without it, anything able to influence the discovery response — a
   compromised CDN, a captive portal, a stale cache — could name its own `token_endpoint` and
   the app would POST a refresh token straight to it. The function is deliberately pure,
   network-free and uses `java.net.URI` rather than `android.net.Uri`, so the rule is directly
   unit-testable on a plain JVM.
3. `beginLogin()` POSTs a form body of `client_id` and `scope` to the device endpoint and
   reads `user_code`, `device_code`, `verification_uri`, optional
   `verification_uri_complete`, `expires_in` (default 15 min) and `interval` (default and
   floor 5 s).
4. The challenge packs `userCode|deviceCode|tokenEndpoint` into one field. Only the first
   segment is ever displayed — `XaiProvider.displayCode` exposes it, though the current
   `AddAccountViewModel` splits on the separator inline for every non-Codex device flow.
   The resolved token endpoint travels with the challenge so that `completeLogin` stays
   stateless and cannot end up polling an endpoint that a second discovery call might have
   changed underneath it.
5. The UI shows the code and opens `verification_uri_complete` when xAI supplied one — it
   embeds the code, so the user does not have to type it — falling back to `verification_uri`.
6. `completeLogin()` re-validates the packed endpoint (the challenge is a value object that
   may have been held across a process death; stored input is treated as untrusted) and polls
   the token endpoint with `grant_type=urn:ietf:params:oauth:grant-type:device_code`,
   `device_code` and `client_id`, with HTTP retries disabled so the generic retry cannot
   swallow a pending response.
7. Both platforms read the OAuth error body on HTTP 200, 400 and 403. Only
   `authorization_pending` keeps the current interval; `slow_down` permanently adds five
   seconds to each subsequent wait. `expired_token`, `access_denied` and unknown terminal
   errors end the attempt immediately. Transport failures and 5xx can continue within the
   deadline. Polls have no additional HTTP retry loop.
8. The whole loop is bounded by the `expires_in` deadline.

The token endpoint is **pinned into the stored credentials** (`OAuthCredentials.tokenEndpoint`)
at login, so a later refresh costs one request instead of two and never depends on discovery
being reachable. It is nonetheless re-validated on every use, because it comes back out of the
credential store. Only a credential set predating that pinning falls back to re-discovering.

Identity prefers the ID token: the claims arrived over TLS from the token endpoint in response
to a request this app made, so reading `sub`, `email` and `name` costs no extra round-trip and
no extra token exposure. `sub` is the stable key and the email is only a fallback, since a
user can change an address while the account stays the same. `GET /v1/me` is used only when no
ID token was issued for the granted scopes.

`ProviderProfile.plan` is left null. xAI meters money rather than a named tier, and neither
the token nor the billing payloads carry a plan, so nothing is guessed.

## 3. Endpoints and headers

| Method and URL | Purpose | Headers sent | Provenance |
|---|---|---|---|
| `GET https://auth.x.ai/.well-known/openid-configuration` | Resolve the device and token endpoints | `Accept`, `User-Agent` | OAuth standard (OpenID Connect Discovery 1.0 / RFC 8414) |
| `POST <device_authorization_endpoint>` (discovered) | Start the device login | `Accept`, `User-Agent`; form body | OAuth standard (RFC 8628 §3.1) |
| `<verification_uri>` / `<verification_uri_complete>` (from the response) | Page the user approves on | — (opened in a browser) | OAuth standard (RFC 8628 §3.2, §3.3.1) |
| `POST <token_endpoint>` (discovered, then pinned) | Poll for approval, and refresh | `Accept`, `User-Agent`; form body | OAuth standard (RFC 8628 §3.4–3.5, RFC 6749 §6) |
| `GET https://api.x.ai/v1/me` | Account identity fallback | `Authorization`, `Accept` | Documented provider API |
| `GET https://cli-chat-proxy.grok.com/v1/billing?format=credits` | Weekly credit view | `Authorization`, `x-xai-token-auth`, `x-grok-client-version`, `accept: */*`, `user-agent` | Internal endpoint observed in the first-party CLI — NOT a stable public API |
| `GET https://cli-chat-proxy.grok.com/v1/billing` | Monthly spend view | same as above | Internal endpoint observed in the first-party CLI — NOT a stable public API |

The two billing rows are the only non-standard endpoints in this provider — everything on the
auth side is ordinary OAuth, which is unusual among the four and is most of why this provider
is the least fragile of them.

Billing headers mirror the first-party CLI, which is what the proxy answers billing JSON for:
lowercase header names, `accept: */*`, `x-xai-token-auth: xai-grok-cli` and
`x-grok-client-version`. The version is centralised in `ProviderEndpoints.Xai.CLIENT_VERSION`
and never inlined at a call site, so a version bump is a one-line edit. The user agent
(`grok-pager/0.2.91 grok-shell/0.2.91 (android; aarch64)`) is a compatibility signal that
still names the platform honestly; there is no TLS fingerprint spoofing or bot-detection
evasion here or anywhere else in the app.

Scope: `openid profile email offline_access grok-cli:access api:access`. `offline_access` is
what yields the refresh token.

## 4. Usage payload

xAI is the odd provider out: it does not report quota as a percentage of a rate limit, it
reports **spend**. Two views exist, describing different periods, and the app reads both.

The weekly credit view — the one xAI endpoint that hands back a ready-made percentage:

```json
{
  "creditUsagePercent": 34.0,
  "currentPeriod": {
    "start": "2026-09-02T00:00:00Z",
    "end":   "2026-09-09T00:00:00Z",
    "type":  "weekly"
  }
}
```

The monthly spend view, in which **every amount is an integer number of cents**:

```json
{
  "monthlyLimit": 10000,
  "used": 4200,
  "onDemandCap": 5000,
  "onDemandUsed": 0,
  "billingPeriodStart": "2026-09-01T00:00:00Z",
  "billingPeriodEnd":   "2026-10-01T00:00:00Z"
}
```

*(Both synthetic. Invented amounts and periods, no real account.)*

| Payload field | Maps to | Direction and units |
|---|---|---|
| `creditUsagePercent` / `credit_usage_percent` | `usedPercent` of the `xai-credits` window | **Percent CONSUMED**, 0–100, used unchanged. `34.0` renders as "66 % remaining". Absent → **zero when the reported period contains now**, and no window otherwise — see "The implicit zero" below. |
| `currentPeriod.start` and `.end`, or the flat `usagePeriodStart` / `usagePeriodEnd` | `periodSeconds` (derived) and `resetAt` | Both absolute instants. The span is **measured** as `end − start`, not assumed from the `"weekly"` label; `resetAt` is `end`. An absent, unusable or inverted pair leaves the period null → `OTHER`, still rendered but not claimed to be weekly. |
| `currentPeriod.type`, or the flat `usagePeriodType` | category when no span can be measured | `USAGE_PERIOD_TYPE_WEEKLY` in production; matched by substring (`week`, `month`), never by the exact literal. |
| `monthlyLimit` / `monthly_limit` | denominator of the `xai-monthly` window | **Integer cents.** |
| `used` | numerator of `xai-monthly`, clamped to the limit | **Integer cents**, total spend for the period. |
| `onDemandCap` / `on_demand_cap` | denominator of the `xai-on-demand` window | **Integer cents.** Zero or absent → no on-demand window at all. |
| `onDemandUsed` / `on_demand_used` | numerator of `xai-on-demand` | **Integer cents.** Absent → derived as `max(0, used − monthlyLimit)`, since on older payloads everything above the allowance is by definition on-demand spend. |
| `billingPeriodEnd` / `billing_period_end` | `resetAt` for both monthly windows | Absolute instant → epoch millis. |
| — | `periodSeconds` for both monthly windows | Fixed at 2 592 000 s (30 days). The endpoint reports period *stamps*, not a length, and a real calendar month is 28–31 days; 30 days sits inside the band `WindowCategory.fromPeriodSeconds` recognises as `MONTHLY`, so the window is categorised correctly whatever month it is. |

**No money is ever displayed.** Percentages are ratios of two cent amounts, so the unit
cancels out and the app never has to decide on a currency, a locale or a rounding rule for a
figure it might get wrong. Every division in the file goes through one `percentOf` helper, so
a zero or missing denominator can only ever produce a *null* percentage — never `NaN` or
`Infinity`, either of which would render as a nonsense bar and, worse, would compare as
"healthy" against the severity thresholds.

The same `nowMs` note as Antigravity applies: both parse functions take a clock for signature
symmetry with the other providers, and neither currently needs it, because xAI states its
period ends absolutely.

## 5. Normalisation rules

**Three windows, with fixed ids.** `xai-credits` ("Weekly credits"), `xai-monthly` ("Monthly
included") and `xai-on-demand` ("On-demand"). The ids are constants
(`XaiBillingParser.CREDITS_WINDOW_ID` and siblings) rather than derived from the payload,
because unlike the other three providers there is nothing in these payloads to derive an id
from.

**Classification.** The credit window's category is derived from its measured period, so a
change upstream reclassifies itself instead of mislabelling a fortnight as a week. The two
billing windows are `MONTHLY` by construction.

**The implicit zero.** xAI's billing message is proto3, and `credit_usage_percent` is an
implicit-presence float there: a value of exactly zero is not written to the wire at all. So
the one week in which nothing has been spent — the first week, the week after a reset —
arrives with the field *missing*, and the earlier rule "absent means no window" made the
weekly row vanish at precisely the moment it read 100 % remaining; the account showed only
its monthly limit. The provider's own web client reads the omitted scalar as zero, and so does
the parser now — but only when the payload proves it describes the period that contains now
(`start ≤ now ≤ end`). A period in the past, or none at all, still yields no window rather than
an invented bar. This is the one place `nowMs` is consulted in the xAI parser.

**The weekly row is read from both views.** The unified-billing shape of `/v1/billing` carries
`creditUsagePercent` and the flat `usagePeriod*` trio beside its monthly figures, so
`parseBilling` yields the weekly window as well. `merge` keeps the credit view's copy when
both answer, and the row survives if the credit view alone stops answering.

**Included and on-demand are two bars, not one**, because they run out independently: an
account can have burned its entire monthly allowance and still be able to spend on demand. The
included bar is **clamped at 100 %** — spend past the allowance is on-demand spend, and
without the clamp an overspending account would read "140 % used", which is both wrong for
that window and hides the overage from the row that actually meters it. The on-demand window
is omitted entirely when the cap is zero or absent, since showing an empty bar for a facility
the account does not have is pure noise.

**De-duplication on merge.** `merge()` concatenates credits first, then billing, and keeps the
first occurrence of each id. The two endpoints are projections of one resource — `?format=credits`
is the same billing record under a different view — so if the credit view ever starts
reporting a monthly figure too, the account gets one bar rather than two contradictory ones.
Credits-first ordering means the view that reports a real percentage stays authoritative for
anything it covers.

**Partial failure is a first-class case.** The two views are fetched independently and each
failure is caught separately. A `null` result means "this view failed", which is *not* the same
as an empty list, which is a legitimate answer meaning "nothing to report". If one view fails
the account still shows real numbers for whatever answered; only when **both** fail does the
last error propagate, so that a total outage surfaces as a failure rather than as an account
that appears to have no limits at all.

**No reset credits.** `supportsResetCredits` is explicitly false. xAI has no such facility and
the app does not invent one.

## 6. Known risks and upstream-change exposure

- **Discovery moves off `x.ai`.** `validateEndpoint` refuses it and login fails with a clear
  message. This is a deliberate fail-closed: the alternative is trusting a network-fetched
  document about where to send a refresh token. If xAI legitimately moves domains, the fix is
  a considered one-line change to `ISSUER_HOST`, made by a person who has checked — not an
  automatic follow.
- **Discovery drops the device endpoint.** Login breaks entirely; there is no fallback flow,
  because there is no redirect-based alternative implemented for this provider.
- **`slow_down` semantics change, or the interval floor is too low.** The interval is clamped
  to a 5 s floor and only ever increases, so the worst case is polling more slowly than
  necessary. A hot poll loop is not reachable from a bad or missing `interval` value.
- **A billing field is renamed.** Cost is bounded per field: no `creditUsagePercent` costs the
  credits row; no `monthlyLimit` *and* no `used` costs both monthly rows; a missing
  `onDemandCap` costs only the on-demand row. Each loss is one bar, and the remaining bars
  keep working.
- **Amounts stop being integer cents** (switching to floats, or to dollars). This would *not*
  throw and would *not* look wrong: every percentage here is a ratio of two amounts, so as
  long as both the numerator and the denominator change units together the output is
  unchanged. It only breaks if the two views diverge in units, which no code path can detect.
  This is the quiet risk in this provider and the reason the units are stated so plainly in §4.
- **One billing endpoint disappears.** Cost: its rows. The account still renders from the
  other view. Both gone: the last error propagates and the account keeps its previous numbers
  with an error attached, isolated per account by `SyncEngine`.
- **The billing proxy starts requiring a newer `x-grok-client-version`.** One-line edit in
  `ProviderEndpoints.Xai`; the version is centralised precisely so this stays a one-line edit.
- **`cli-chat-proxy.grok.com` starts gating on client identity more strictly.** The app sends
  the CLI's identity headers but does not disguise itself further. If that is not enough, the
  correct outcome is a clear error rather than an evasion attempt.
- **Zero windows parsed.** As with the other providers, an account that parses to no windows
  renders with `Severity.ERROR` even though the sync recorded `OK`. For xAI this can only
  happen if both views return payloads that parse but contain none of the recognised fields —
  a shape change rather than an outage.

## 7. Provenance

Behaviour derived from, and re-read at, these commits:

- **CLIProxyAPI @ `7fac6b15`** (2026-09-09) — `internal/auth/xai/xai.go` and
  `internal/auth/xai/types.go` for the client id, the issuer and discovery URL, the scope
  string, the device-code grant type, and the poll error handling
  (`authorization_pending`, `slow_down`, `expired_token`, `access_denied`).
- **CLIProxyAPI Management Center @ `ed5f1c48`** (2026-09-08) — `src/utils/quota/constants.ts`
  for the two billing URLs, `/v1/me`, the `x-xai-token-auth` and `x-grok-client-version`
  headers and the client version string.
- **CLIProxyAPI-Quota-Inspector @ `1895bc54`** — *not a source for this provider.* The
  repository contains no xAI or Grok code at all. An earlier draft cited it as a cross-check
  on the billing field names and the integer-cents representation, which was wrong.

**Single-sourced, and it already cost something.** The billing payload shape rests on the
Management Center alone. Two details were originally missed and only found by re-reading it:
the `config` envelope both endpoints wrap their body in, and that money fields arrive either
as a bare number or as `{"val": 10000}`. Together those produced zero windows against a real
payload while every hand-written test passed.

The client id is the public identifier the first-party CLI ships; there is no client secret in
this provider. No credential material, captured payload or account identifier from any real
account appears in this repository, in its tests, or in this document.

See also `docs/provider-auth-research.md` for the cross-provider comparison of the four login
flows, and `docs/security.md` for the credential-storage argument.
