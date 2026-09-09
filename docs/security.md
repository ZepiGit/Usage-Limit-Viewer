# Security

This app holds long-lived OAuth credentials for paid AI subscriptions and does nothing else
with them but read quota. That single sentence sets the entire security problem: the tokens
are valuable, they have to survive on the device for weeks, and they have to be usable by a
background job at three in the morning while the phone is locked in a pocket.

## Threat model, in plain terms

**What is being protected.** Access and refresh tokens for the connected accounts. A refresh
token is the crown jewel: it is long-lived and will mint access tokens indefinitely. Access
tokens for ChatGPT, Claude, Google and xAI can, depending on their scopes, do a great deal
more than read a usage number — the app requests the scopes the first-party clients request,
because the quota endpoints will not answer otherwise, and those scopes include inference. A
leaked token is therefore not "someone sees my usage percentage", it is "someone spends my
subscription and possibly reads my data".

**Who is being defended against, and how seriously:**

- *Another app on the same device.* Fully in scope, and the primary reason for the design.
  Android's per-app sandbox already isolates the files; the Keystore adds that even a copied
  ciphertext file is inert elsewhere.
- *Someone with the device backup, a cloud restore, or an ADB backup.* In scope. Backup and
  device transfer are disabled outright, and the Keystore key is not exportable, so a copied
  ciphertext cannot be decrypted on another device even if the file escapes.
- *Someone with brief physical access to an unlocked device.* Partly in scope. They can open
  the app and see usage; they cannot read a token through the UI, because no screen renders
  one. They can, of course, use the accounts directly through the real apps — no local defence
  helps there.
- *Network attackers.* In scope. Everything is HTTPS with the platform trust store, tokens
  never traverse a plaintext hop, and endpoints discovered at runtime are validated before use.
- *A rooted device, or a compromised OS.* Explicitly out of scope. With root, an attacker can
  ask the Keystore to decrypt on the attacker's behalf, because the app can. Nothing an app
  does defeats this, and pretending otherwise would be theatre.
- *The providers themselves.* Out of scope by definition — the app talks to them as you.

**What the app does not hold.** No password ever passes through it: authentication happens in
the system browser (Custom Tabs, never a WebView, precisely so the app is not in a position to
observe a password and so the user can see a real address bar). There is no app account, no
server-side copy of anything, and no analytics identity.

## Credential storage

`KeystoreCredentialStore` is the only thing that ever writes a token to disk.

An AES key is generated on first use in the `AndroidKeyStore` provider under the alias
`usage_limits_credentials_v1`, with `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE` and
`setRandomizedEncryptionRequired(true)`. `setKeySize` is not called, so the key is the platform
default of **128 bits** — AES-128-GCM, a perfectly sound AEAD choice, but worth stating rather
than letting a reader assume 256. Raising it means `.setKeySize(256)` under a new alias, since
an existing key cannot be resized, plus a migration for anyone already logged in; that
migration is the only reason it has not been done already. The key material never enters the app's address
space — encryption and decryption happen inside the Keystore, and on devices with a secure
element or TEE the key is hardware-bound. It cannot be exported by the app, by root-level file
access, or by a backup.

Each account's credentials are serialised to a small JSON object (access token, refresh token,
id token, expiry, and the token endpoint for providers that discover it at runtime), encrypted,
and stored in a private `SharedPreferences` file `usage_limits_credentials` under the key
`cred_<credentialReference>`. Writes use `commit()` rather than `apply()`: a credential that
was reported saved but lost to a process death would strand the account, and the write is
already on an IO dispatcher.

**The GCM IV is generated fresh per encryption and stored in the clear**, base64-encoded and
prefixed to the ciphertext as `iv:ciphertext`. This is correct and deliberate. A GCM nonce is
not secret — its security requirement is *uniqueness* under a given key, not confidentiality.
What must never happen is IV reuse, which under GCM is catastrophic: two messages encrypted
with the same key and IV leak their XOR and, worse, allow forgery of the authentication tag.
`setRandomizedEncryptionRequired(true)` is what guarantees this cannot happen — the platform
refuses to let the caller supply an IV for encryption and generates a random one itself, so
there is no code path in which the app could reuse one. Storing it alongside the ciphertext is
then simply how the value gets back for decryption, exactly as every AEAD format does. The
128-bit GCM tag also means a tampered record fails to decrypt rather than yielding a corrupted
token; `load()` catches that and returns `null`, which surfaces as "sign-in expired".

### Why not `EncryptedSharedPreferences`

Jetpack's `EncryptedSharedPreferences` wraps the same two primitives — a Keystore-held key and
an AEAD cipher — behind a `SharedPreferences` façade. It was not used for two reasons. First,
the version in the dependency set (`androidx.security:security-crypto:1.1.0-alpha06`) is both
perpetually alpha and now deprecated by Google, with no supported successor for this use case;
depending on it would mean building the app's most security-critical component on something
upstream has stopped maintaining. Second, it would not have removed much work: the store still
needs its own per-record serialisation, its own reference-keyed layout, and its own
suspend-function surface, so what remains is the twenty lines of Cipher plumbing above —
plumbing that is now explicit and reviewable rather than hidden behind a façade whose
guarantees change between alphas. The trade is that this code must be right; the mitigations
are that it is small, uses the platform's own randomised-IV enforcement rather than
hand-rolling nonce management, and never touches key material itself.

### Why the key is not gated on user authentication

`KeyGenParameterSpec` supports `setUserAuthenticationRequired(true)`, which would make the key
usable only shortly after a device unlock or a biometric prompt. That option is deliberately
**not** set.

The reason is that it is incompatible with the product. The app's core promise is that the
home-screen widget is current when you glance at it, which means WorkManager must refresh
tokens and fetch usage on a schedule — including while the device is locked and the user is
asleep. An auth-gated key would make every background pass fail with a `UserNotAuthenticated`
exception, so the app would only ever be as fresh as your last unlock, which is precisely when
you are *not* looking at the widget.

What that costs, stated plainly: on an unlocked or compromised-at-runtime device, the
credential ciphertext can be decrypted by this app's own process without a user gesture. The
Keystore still prevents key extraction and still binds the ciphertext to this device and this
app — what is given up is a second factor at decrypt time, not the key's protection.

If this trade ever needs revisiting, the honest design is two keys: an auth-gated key wrapping
the credentials used for interactive actions, and an ungated key for a narrower background
capability. That only works if a provider issues separately-scoped tokens, and none of these
four do — so it is not available today.

## What tokens never touch

Each entry below is a structural property, not a rule someone has to remember.

- **Logs.** There is no `android.util.Log` call, no `println`, and no OkHttp logging
  interceptor anywhere in `app/src/main` — verifiable with one grep. `OAuthCredentials`
  additionally overrides `toString()` to print `accessToken=***`, so even an accidental string
  interpolation of the whole object in some future code cannot leak one.
- **Room.** No entity has a token column. `AccountEntity` stores a `credentialReference`,
  which is a lookup name of the form `<providerId>_<localId>`. Exporting the entire database
  yields usage numbers and e-mail addresses, and no credential.
- **Widgets.** The `widget` package does not import `core.auth` at all, and the only data it
  can reach is `UsageRepository` through `WidgetUpdater`. See `docs/widgets.md`.
- **Notifications.** `NotificationPublisher` composes strings from an account *label* and a
  window label — `"Claude Max · Weekly at 12% left"`. It has no access to a credential and no
  reason to want one.
- **Analytics and crash reporting.** There are none. No Firebase, no Crashlytics, no
  Sentry — nothing in the dependency list reports anywhere. A token cannot appear in a crash
  report that does not exist.
- **Backups and device transfer.** `allowBackup="false"`, plus `data_extraction_rules.xml`
  excluding `root`, `database`, `sharedpref` and `file` domains for both cloud backup and
  device transfer. Keystore-wrapped ciphertext would be undecryptable after a restore anyway;
  disabling backup means it is not copied around in the first place.
- **User-visible error messages.** Every `ProviderException` is mapped through
  `userMessage()` before it reaches the UI, which returns one of eight fixed strings — the only
  variable part in any of them is an HTTP status code. Neither a raw provider body, nor a URL,
  nor a header, nor a token can reach a snackbar or a notification, even if a provider echoes
  something sensitive in an error body.
- **`Bundle`s, `Intent`s and saved state.** Credentials are only ever passed as function
  parameters between `SyncEngine` and a provider. Nothing puts one in a navigation argument,
  a `SavedStateHandle`, or a `PendingIntent`.

## OAuth: PKCE, state, and browsers

Both redirect-based flows (Claude, Antigravity) use PKCE with S256. `Pkce.generate()` draws 32
bytes from `SecureRandom` and base64url-encodes them without padding, producing a 43-character
verifier — the minimum length RFC 7636 allows, carrying a full 256 bits of entropy. The
verifier stays in the provider instance's memory for the duration of the flow, is never
persisted or logged, and is cleared in a `finally` block whether the redirect arrives or not.
An authorization code intercepted on the way back is therefore useless without it.

CSRF is handled by a 32-byte random `state`, compared with `Pkce.constantTimeEquals`. Timing
is not a realistic threat for a local comparison, but state validation is the control for the
entire flow, so it does not depend on early-exit equality. Claude's flow has a wrinkle worth
noting: Anthropic sometimes returns `code#state` in a single parameter, so the provider strips
the fragment before exchange and validates *every* state value that came back — query
parameter and fragment alike — against the one it generated. Any mismatch, or the absence of
any state at all, aborts.

Authentication always happens in the system browser through Custom Tabs, with a plain
`ACTION_VIEW` intent as the fallback. A WebView would put this app in the position of being
able to observe the user's password, and would deny them the address bar they need to tell a
real login page from a fake one.

## Transport

`HttpClient` is the only outbound HTTP path in the app and it refuses to send a plaintext
request. The exception, and there is exactly one, is a loopback host — the parsed host must be
exactly `localhost`, `127.0.0.1` or `::1`, which is the OAuth redirect that arrives on the
app's own listening socket. This started as a `startsWith("http://localhost")` prefix test,
which would also have accepted `http://localhost.attacker.example/` — an ordinary remote host.
Comparing the parsed host is the difference between a rule and the appearance of one. That connection never leaves the device and cannot be given a TLS certificate anyone
would trust, which is why RFC 8252 §7.3 endorses it. Everything else must be `https://` or the
request throws before a byte is sent.

The `LoopbackServer` binds explicitly to `InetAddress.getByName("127.0.0.1")` with a backlog of
one, so nothing on the network can reach it, serves exactly one request, and is closed in a
`finally` block. It parses only the request line.

The page it answers with is **not** static, and an earlier version of this document called it
that. It interpolates the provider's `error_description`, which is attacker-influenced: any
local app can open `http://127.0.0.1:54545/?error=<img src=x onerror=…>` while the listener is
up and get script execution at that origin. Those values are now HTML-escaped. Response
splitting was never possible, since `Content-Length` is computed from the rendered body.

xAI is the one provider whose endpoints are discovered rather than hardcoded, via its OIDC
discovery document. Because that document arrives over the network, both endpoints it names
are checked by `XaiProvider.validateEndpoint` before anything is sent to them: the URL must be
`https`, and the host must be exactly `x.ai` or a dot-anchored subdomain of it. The anchoring
is what stops `notx.ai` and `x.ai.example.com` from passing. Without this check, anything able
to influence that document — a compromised CDN, a captive portal, a poisoned cache — could
name its own `token_endpoint` and the app would post a refresh token straight to it.

No certificate pinning is implemented; the platform trust store is used as-is. An earlier
version of this document justified that by saying pinning buys little against an attacker who
can install a CA. That reasoning was wrong, because the threat it names does not reach this
app. There is no `network_security_config`, and `targetSdk` is 35, so the default policy for
API 24 and above applies: only the **system** CA store is trusted. A CA the user adds — or is
socially engineered into adding — is already not trusted for these connections, pinned or not.

The case pinning would genuinely address is a CA in the *system* store: an MDM-managed device
whose administrator installed one, or a rooted device where that store can be written. That is
precisely the "rooted device, or a compromised OS" row of the threat model above, which this
app explicitly does not take on. Someone who can add a system CA can equally ask the Keystore
to decrypt on the app's behalf, so a pin is not what would be standing between them and the
tokens.

What is left is cost, and it is real. Four providers' chains rotate independently on schedules
none of them publish, all but the OAuth token endpoints are undocumented internal APIs, and
there is no channel to ship a new pin faster than a store release. A stale pin is a total,
self-inflicted outage for that provider, indistinguishable to the user from the provider being
down. Not pinning four undocumented, independently-rotating endpoints with no rotation channel
is the right call.

## Concurrency and credential lifecycle

Token refresh is serialised per credential reference by a mutex, with the expiry re-checked
inside the lock, because these providers rotate refresh tokens on use. The race and its
consequences are described in `docs/architecture.md`; the security-relevant summary is that
two concurrent refreshes could otherwise leave the store holding a token the provider has
already invalidated, which presents to the user as an inexplicable "sign-in expired" and
invites them to re-authenticate more often than necessary. Key generation is guarded by its
own mutex for the same reason: two first-use encryptions must not race to create the key.

**Ordering on login and logout is deliberate, and the two orderings are opposite for the same
reason** — a stored credential must never outlive the account row that names it.

- *Login* (`AddAccountViewModel`): the account row is written first, then the credential.
  A crash between the two leaves an account with no credential, which the app reports as
  "sign-in expired" and the user fixes by reconnecting. The reverse would leave a credential
  no row references, invisible in the UI and therefore never deletable.
- *Logout* (`UsageViewModel.removeAccount`): the credential is deleted first, then the
  snapshot and the account row. A crash between the two leaves a visible account with no
  credential — recoverable, and again fixed by reconnecting.

Re-authenticating an existing account reuses its `credentialReference` (`upsertFromLogin`
keeps the existing one), so the old ciphertext is *overwritten* rather than orphaned beside a
new entry.

One gap worth recording: `CredentialStore.references()` exists so that orphaned entries could
be swept, but nothing calls it. If a future change ever breaks the ordering guarantees above,
there is currently no janitor to notice. A sweep at app start comparing `references()` against
the account table would close it cheaply.

## Accepted risks

Four decisions trade some security for the app being able to work at all. Each is listed with
what was rejected and why.

### 1. The Antigravity client secret is in the APK

`ProviderEndpoints.Antigravity.CLIENT_SECRET` contains a real `GOCSPX-…` string, which
directly contradicts the usual rule that an APK must contain no client secrets.

It is defensible because this is a Google **installed-application** client secret, a category
that is explicitly not confidential: RFC 8252 §8.5 states that native app secrets cannot be
kept secret and must not be treated as such, and Google's own installed-app documentation says
the same. The value already ships inside the Antigravity desktop client and can be extracted
from it in minutes. It is required because Google's token endpoint rejects the exchange for
this client type without it, and the Antigravity quota scopes (`cclog`,
`experimentsandconfigs`, and the `v1internal` methods) are bound to this specific client id —
a self-registered client, however correctly configured, cannot reach them.

What actually protects the flow is PKCE. Possession of the client id and this string does not
let an attacker complete an authorization: they would still need a code issued against a
`code_challenge` they cannot answer, delivered to a redirect URI on the victim's own loopback
interface.

Rejected alternatives: *(a)* register our own Google OAuth client — the honest, correct-looking
option, which does not work, because the quota scopes are not grantable to it; *(b)* omit the
secret and hope — the token endpoint returns `invalid_client`; *(c)* obfuscate or split the
string in the binary — security theatre that makes the code worse and stops nobody, and worse,
it would misrepresent the value as secret when it is not; *(d)* drop Antigravity support
entirely — the real alternative, and the one to take if Google ever treats this client as
confidential.

Residual risk: the client id and secret identify the app to Google as the Antigravity client
during login. If Google restricts or rotates that client, the provider stops working. That is
a functionality risk, not a credential-leak risk — no user token is exposed by this value.

### 2. Codex device flow: the PKCE pair is generated server-side

In OpenAI's device flow, the *device token* endpoint returns the authorization code together
with the `code_verifier` and `code_challenge` — the app exchanges a PKCE pair it did not
generate.

This inverts what PKCE normally guarantees. Ordinarily the verifier proves that the client
redeeming the code is the same client that started the flow, because only it ever knew the
verifier. Here the provider knows both halves and hands them over together, so the "proof" is
a value the app received rather than one it created. Practically it still means the pair only
ever travels over the app's own TLS connection to `auth.openai.com`, and the code is bound to
the device authorization the user approved — but it should not be described as client binding,
because it is not.

Rejected alternative: the desktop authorization-code flow with a client-generated verifier and
a loopback redirect to `http://localhost:1455/auth/callback`. Rejected for the platform, not
the cryptography: on Android the app is backgrounded the instant a browser opens and may be
killed, the port must be free, and a user who finishes the login in a browser on another
device sends the code to *that* machine's localhost, where nothing is listening. The device
flow has none of those failure modes. `docs/providers-codex.md` has the full comparison.

Residual risk: an attacker who could observe OpenAI's response to the device-token call would
hold everything needed to redeem the code. That requires breaking TLS to `auth.openai.com`, at
which point the access token itself is equally exposed — so the marginal risk over any other
flow is small.

### 3. Fixed loopback ports (54545 for Claude, 51121 for Antigravity)

Both providers pin their redirect URIs in client registration, so the app cannot choose an
ephemeral port; it must bind exactly what the provider will redirect to.

Two consequences. First, availability: if another process holds the port, login fails.
`LoopbackServer.start()` is called from `beginLogin`, so the port is bound *before* the
authorization URL is handed back and the browser is launched. That ordering matters and was
originally wrong: the browser was opened first and the socket bound afterwards, so the flow
could mint a real authorization code and only then discover it had nowhere to land. The
user-facing message is still weaker than this section once claimed — `ProviderException.Unexpected`
maps through `userMessage()` to a fixed string, so the specific port text does not reach the
snackbar. Binding early is the part that actually protects the flow.
Second, and more interesting: while the listener is open, *any* local process could connect to
it and deliver a fabricated `code`/`state`. The state check is what defeats that — an attacker
would have to guess the 32-byte random state to have their code accepted — and the window is
narrow: the socket accepts exactly one request, has a backlog of one, and is closed in a
`finally` block.

That single-accept behaviour is not purely a mitigation, and it is worth stating plainly: a
local app that connect-polls the port can win the one `accept()` the server performs, after
which Claude and Antigravity login can never complete while that app is installed. The failure
is denial of login, not compromise — and the error the user sees blames the wrong thing.

A hostile local app could also squat the port before the user logs in and capture the real
code; PKCE means the captured code is not redeemable without the verifier that never left this
process.

Rejected alternatives: an ephemeral port (the provider would redirect to the registered port
regardless, so nothing would arrive); a custom URI scheme or Android App Link (would have to be
registered by the provider — it is not, and a custom scheme is also the weaker option, being
claimable by any app); a WebView intercepting the redirect internally (rejected on principle —
the app must not be able to see the user's password).

### 4. No TLS fingerprint imitation for Claude

CLIProxyAPI, the reference this provider was read from, routes its Anthropic traffic through
uTLS to mimic a browser's TLS ClientHello and enforces strict header ordering, because
Anthropic's edge runs bot detection. **This app does not do that, and will not.**

The reasoning is that the two behaviours are different in kind. Reading your own quota with
your own OAuth token through a first-party client's endpoint is a grey but arguable use.
Deliberately forging a TLS fingerprint to defeat a provider's bot detection is circumvention:
it is designed to make an automated client indistinguishable from a human's browser against
the explicit wishes of the operator. It would also be a maintenance treadmill against an
adversary that updates, and it would poison the project's ability to describe itself honestly
to those providers.

So the app sends a plain HTTPS request from OkHttp with no TLS trickery of any kind, and if
Anthropic's edge rejects it the correct outcome is a clear error telling the user Claude
cannot be reached — and, if that turns out to be permanent, dropping the provider.

Where exactly the line sits deserves stating, because it is a judgment call rather than a
bright line. The app *does* send the client identifiers these endpoints expect, since several
of them vary their response by client or refuse a request without one: `codex-tui/0.149.1
(Android; arm64) UsageLimits` for Codex (the app's own name appended), the Antigravity and
grok-pager CLI user agents, and — the least comfortable of the four — `axios/1.15.2` on
Claude's two token calls, copied verbatim from the reference client because that endpoint
refuses a request with no user agent at all. Claude's read endpoints send no user agent.
Sending a header a server asks for is ordinary API client behaviour and is visible in one
grep of `ProviderEndpoints`; forging a TLS fingerprint to be *indistinguishable* from a
browser is not, and that is the step this project declines to take.

Residual risk, stated as the README does: this is the single biggest feasibility risk in the
project. It is entirely possible that Claude support simply does not work from a phone on a
mobile network, and no code change within these rules would fix it. Nothing in this repository
has been run against Anthropic's edge, so it remains unverified in both directions.
