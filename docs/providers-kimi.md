# Kimi Code

The fifth provider. It signs in with Kimi's RFC 8628 device flow, under this app's own name,
and takes a key from the user's console as the second way in.

## The identity question, answered

For a while this was the one provider without OAuth, on the reading that its device flow
belonged to `kimi-cli` and that driving it meant impersonating that client. Reading the
programs that actually drive it showed the reading was half right:

- The client id — `17e5f671-…` — is **public and shared**. The first-party CLI, CLIProxyAPI
  (`internal/auth/kimi/kimi.go`), pi and others all present the same one to
  `auth.kimi.com`. There is no per-program client id, and none is expected.
- What Moonshot gates on is the **`X-Msh-Platform` header** on `api.kimi.com/coding`, which
  names the calling program, and its `User-Agent`. The allowlist is by program name, and
  Moonshot extends it on request: pi ([moonshotai/kimi-code#2185][pi]) and Cline
  ([moonshotai/kimi-cli#2322][cline]) were added under their own names; CLIProxyAPI sends
  `X-Msh-Platform: CLIProxyAPI` and is thanked by Moonshot in its README.
- What Moonshot forbids — in its membership guide and in every one of those threads — is
  **spoofing**: presenting another program's identity. That is the thing this project's rule
  against circumventing provider protections also forbids, and it is the thing this app does
  not do.

So the app sends its own name, `X-Msh-Platform: UsageLimits` with a `UsageLimits/<version>`
user agent, on every request to Kimi — the device authorization, the poll, the refresh and
the usage read alike — and never `kimi_cli`. That is honest. What it is not yet is
allowlisted: until Moonshot adds the name, the coding API may answer the usage read with
`403 access_terminated` for an OAuth token, in which case the app reports "Access denied" and
offers the key below. Asking for the allowlist entry is an issue on `moonshotai/kimi-code` in
the shape of [#2185][pi]; the request states the program name, the header values and that it
reads `/v1/usages` only.

[pi]: https://github.com/moonshotai/kimi-code/issues/2185
[cline]: https://github.com/moonshotai/kimi-cli/issues/2322

## Auth flow

1. `beginLogin()` POSTs `client_id` as a form to
   `https://auth.kimi.com/api/oauth/device_authorization`. The response carries `device_code`,
   `user_code`, `verification_uri` (and usually `verification_uri_complete`, which pre-fills the
   code), `expires_in` and `interval`.
2. The user code is shown and the verification page opened; the device code rides along in the
   challenge, never on screen. Only the half before the separator is displayed
   (`KimiProvider.displayCode`).
3. `completeLogin()` polls `https://auth.kimi.com/api/oauth/token` with
   `grant_type=urn:ietf:params:oauth:grant-type:device_code`, the device code and the client
   id, at the provider's interval (never below five seconds). Kimi answers a pending poll with
   **200 and an `error` body** (`authorization_pending`, `slow_down`), as CLIProxyAPI reads it;
   a failed status is treated as pending too, for a server that follows the RFC's 400. A poll
   that does not get through keeps the loop going as well. `expired_token` and `access_denied`
   end it; the deadline bounds it either way.
4. The token response is `access_token`, `refresh_token`, `token_type`, `expires_in`, `scope`.
   Refresh is an ordinary `refresh_token` grant against the same endpoint, sent once (see
   `HttpClient.oneTimeGrant`); a response that omits the refresh token means "keep the old
   one".

### The key, still

A key the user creates in their own Kimi Code console and pastes into the app — offered as a
button under the device-code screen and beside "try again". It is stored in the Android
Keystore and the iOS keychain exactly like every other credential, is never logged and never
written to Room, and is validated against the usage endpoint before an account row is created —
a key that cannot read usage is not a connected account, and storing it would leave a
permanently failing row the user then has to work out how to remove.

A key-connected account has nothing to refresh: `refresh()` returns it unchanged rather than
throwing, because the sync engine calls it whenever it suspects staleness and for a key that
suspicion is never right. A revoked key surfaces as a 401 on the usage call, which is the path
that already marks an account as needing attention.

## Usage endpoint

`GET https://api.kimi.com/coding/v1/usages`, with `Authorization: Bearer <token or key>` and
the app's identity headers.

### The field-semantics trap

The response carries two kinds of window, and they report consumption in **opposite terms**:

| Block | Has | Does not have |
|---|---|---|
| `usage` — the weekly membership allowance | `limit`, `used`, `resetTime` | `remaining` |
| `limits[].detail` — the rolling rate limits | `limit`, `remaining`, `resetTime` | `used` |

A parser that reads only `remaining` computes nothing for the weekly window, so an account
that has spent its entire week renders as an untouched bar. This is not hypothetical: other
clients shipped exactly that and fixed it after capturing the live response. `KimiUsageParser`
accepts either field and prefers a reported `used` over one derived from `remaining`, and
`KimiUsageParserTest` fails four ways if that is reduced back to one field.

Three further rules the parser holds:

- A window whose `limit` is zero or absent is **dropped**, not drawn. Dividing by it yields
  infinity, and a bar drawn from that reads as an exhausted account that is not exhausted.
- The window's duration is read from the entry, never assumed from its position in the array.
  Classifying by position is how a monthly bucket ends up labelled as a week.
- `resetTime` is accepted in seconds or milliseconds and normalised by magnitude, since an
  epoch stamp in either unit is plausible and guessing wrong misplaces the countdown by a
  factor of a thousand.

## Identity

`externalAccountId` is Kimi's own identifier when the response carries one. When it does not,
a truncated SHA-256 of the key stands in — one way, never the key itself, and never anything
that could be turned back into a credential. It only has to be stable and unique, so that
pasting the same key again updates the account it belongs to rather than creating a second one
beside it.

## Not covered

Moonshot / Kimi Open Platform is a different surface: API-key only, and its
`GET /v1/users/me/balance` reports a cash and voucher **balance**, not a per-window quota.
This app is about limits and when they reset, so that surface is out of scope; an account with
one has nothing for the overview to draw.

## Which account a credential belongs to

Neither a pasted key nor Kimi's access token carries an identity of its own, so the account has
to be named from something else. Two candidates, and the order between them decides what
happens on the day the user rotates their key:

1. **The id Kimi states in its usage response** — `userId`, `user_id`, `accountId`,
   `account_id`, or `user.id` nested. This survives a rotation.
2. **A one-way digest of the key** — truncated SHA-256 on Android, FNV-1a on iOS. The two
   differ, which is harmless: the id is local to a device and nothing compares them across
   platforms. It does NOT survive a rotation.

The provider's id wins wherever the response states one. Naming the account after the digest
instead looks correct until the second sign-in: a user who creates a fresh key in the console
comes back with a different digest, so the app files a SECOND account for the same
subscription — and leaves the first one behind holding a key that no longer works, failing on
every sync with nothing on screen explaining why.

The digest stays as the fallback because a response that names nobody still has to produce a
stable account, and the key is then the only thing left to derive one from. It is never
reversible into the key, and the key itself never becomes an account id.

Both halves are pinned by tests that fail against a digest-only implementation:
`KimiIdentityTest` on Android, `testAPastedKeyAccountIsNamedByTheProviderNotByTheKey` and
`testRotatingTheKeyKeepsOneAccount` in the kit.
