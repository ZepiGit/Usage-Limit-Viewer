# Kimi Code

The fifth provider, and the only one that does not sign in with OAuth.

## Why not OAuth

Kimi Code has an RFC 8628 device-code flow. This app does not use it, and the reason is an
access control rather than a technical obstacle:

- The public client id used by every published integration — `17e5f671-…` — is **kimi-cli's
  own**. There is no separately registered client id available to third-party clients.
- `api.kimi.com/coding/v1` additionally gates on an `X-Msh-Platform` header against a
  server-side allowlist. Values outside it are answered with `403 access_terminated`.

So driving that flow from this app would mean presenting another program's client identity and
platform header in order to pass a check the provider put there deliberately. The open request
asking Moonshot for a third-party client id — [moonshotai/kimi-code#1795][issue] — describes
shipping that combination as impersonation, and it has not been granted.

That is squarely inside this project's rule against circumventing a provider's own protection
mechanisms, so the flow is not used, and will not be until a client id exists that this app is
entitled to present.

[issue]: https://github.com/moonshotai/kimi-code/issues/1795

### What is used instead

A key the user creates in their own Kimi Code console and pastes into the app. It is stored in
the Android Keystore and the iOS keychain exactly like every other credential, is never logged
and never written to Room, and is validated against the usage endpoint before an account row
is created — a key that cannot read usage is not a connected account, and storing it would
leave a permanently failing row the user then has to work out how to remove.

There is nothing to refresh: an API key carries no expiry and no refresh grant. `refresh()`
returns the credential unchanged rather than throwing, because the sync engine calls it
whenever it suspects staleness and for this provider that suspicion is never right. A revoked
key surfaces as a 401 on the usage call, which is the path that already marks an account as
needing attention.

## Usage endpoint

`GET https://api.kimi.com/coding/v1/usages`, with `Authorization: Bearer <key>`.

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

## Which account a key belongs to

A pasted key carries no identity of its own, so the account has to be named from something
else. Two candidates, and the order between them decides what happens on the day the user
rotates their key:

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
