# Verification

Usage Limits is a cross-platform app (Android and iOS) that shows how much quota remains on four AI subscriptions — OpenAI Codex, Anthropic Claude, Google Antigravity and xAI Grok — and when each resets. Every usage endpoint it reads is an internal endpoint of a first-party CLI: there is no compatibility promise, no schema, no changelog. The failure mode this document is about follows directly from that. A parser that guesses wrong does not crash. It renders a number, and the number is wrong, and nothing in the app's behaviour tells the user so. Verification here has one purpose: to make the code's guesses visible as guesses, then break them against something they did not come from. There are three layers, weakest to strongest.

## Layer 1 — hand-written fixtures

These encode what the code expects. They are cheap to write and quick to run, and they remain useful as regression tests once a defect is understood. What they cannot do is catch a wrong assumption, because the fixture and the parser come from the same guess: the author who believes a reset time is an ISO-8601 string writes a fixture containing an ISO-8601 string, and the parser passes. The evidence in this project is blunt — every real defect found passed its hand-written fixtures. Treat this layer as a record of intent, not as evidence about the endpoints.

## Layer 2 — production shape fixtures

These are payloads captured from live accounts, with the values reduced to synthetic ones but the shape kept exactly as fetched: field names, types, null placement, nesting. They close the gap the first layer cannot, because they let the real world disagree with the guess. Since the values are synthetic, they prove nothing about arithmetic on real accounts; what they prove is that the parser reads the real shape correctly — which is precisely where the silent falsehoods live.

## Layer 3 — adversarial audit

An independent reading of the code against the captured payload, tasked as follows: find inputs that produce a plausible-but-wrong number rather than a crash. This is the only layer aimed squarely at the failure mode, because "plausible but wrong" is its success criterion rather than an accident of it. It catches composition defects the fixture layers are not shaped to find: a parser can be correct on every field and the app can still lie — by pooling figures across accounts, pairing a reset with the wrong account, or silencing a notification path.

## What the layers caught

No real defect appears in the first layer's column; every defect below passed the hand-written fixtures. The pattern in the table is the point: the shape fixtures caught the world disagreeing with the guess, and the audit caught the app lying with correctly read data.

| Defect | Caught by | If shipped |
|---|---|---|
| A weekly window arrived in the "primary" slot with a null secondary | Production shape fixtures | Position-based reading would label a week as a five-hour limit |
| `reset_at` arrived as an epoch number where every hand-written fixture had used an ISO-8601 string | Production shape fixtures | The string assumption had never been exercised against a real payload |
| Antigravity's `remainingFraction` arrived as the integer `1` for an untouched limit | Production shape fixtures | A completely free limit would read as fully spent |
| xAI reported `USAGE_PERIOD_TYPE_WEEKLY` rather than the string `"weekly"` | Production shape fixtures | An enum arrives where the code expected a bare word |
| Claude's payload carried 20 top-level keys, of which 8 were window-shaped codenames the registry had never seen | Production shape fixtures | Eight windows the registry did not recognise |
| The two Codex reset-credit sources carry different fields — the dedicated endpoint has no applicable count, the embedded copy has no credit rows | Production shape fixtures | A reader written against one source misreads the other |
| A credit balance (`extra_usage`) sat one field away from being parsed as a rate limit | Adversarial audit | A plausible mapping from credits to quota exists in the code's structure |
| The headline percentage was pooled across accounts | Adversarial audit | A number attributable to none of the accounts it summarised |
| The "next reset" belonged to a different account from the status displayed beside it | Adversarial audit | Status and reset disagree about which account they describe |
| Several notification paths stayed silent instead of firing | Adversarial audit | A limit event the user is never told about |
| Concurrent token refreshes were not serialised, against providers that rotate the refresh token on use | Adversarial audit | Presenting a spent refresh token commonly revokes the whole grant — losing access to a paid account, with nothing locally corrupted to point at |
| The widget re-derived the snapshot's severity on read, using rules that had drifted from the app's | Adversarial audit | The same data reads one way in the app and another on the home screen |
| The iOS widget took any remaining percentage at or below 1 to be a fraction already | Verified audit (3/3) | 1 % left rendered as "100%" with a full bar — the worst number the app can show |
| Every Antigravity account added on iOS failed every refresh: the login never resolved the GCP project the quota RPC is addressed by | Verified audit | A permanent error card for a provider that works on Android |
| The low-quota episode was per account, not per window | Verified audit, then Gemini map | The five-hour window running out silenced the weekly window's 20 %, 10 % and 0 % crossings for good |
| The publisher cancelled the notification on any refresh with nothing new to say | Verified audit | An unread "Weekly exhausted" deleted from the shade within thirty minutes, never re-posted |
| A standing "reset credit available" finding was re-posted every sync | Verified audit | A dismissed notification that came back every thirty minutes until the feature was switched off |
| The overview sorted by `Severity.ordinal`, and "Next reset" was a fleet-wide minimum beside one account's window | Verified audit | Stale and never-fetched cards above the exhausted one; "Next reset 12m" next to a weekly limit that returns on Tuesday |
| The Room foreign-key guard and the write it protected were two statements | Delegated audit, then a demonstrating test | "Refresh now" then "Remove account" raised the very constraint violation the guard existed to prevent |
| `commit()`'s result was discarded when saving a rotated refresh token | Delegated audit | A spent token on disk and the live one only in memory: the account dead on next launch, with no record of why |
| The xAI parser read spend from `used` alone and substituted zero when absent | Verified audit, patched by GPT-6-Astra | "0 % used / 100 % remaining" in healthy green for an account 90 % spent |
| A local process could stall the Android loopback listener for ever by connecting and sending nothing | Review of my own fix | A sign-in that can neither finish nor time out — worse than the denial of service the fix addressed |
| The iOS sign-in race was resolved by whichever path FAILED first | Reading the code | A port already in use ended a sign-in that the other path needed no port to complete |

### How the audit findings were verified

Single-model audits produce claims, and several of those claims were wrong on inspection — the
migration chain being incomplete, a stale-token overwrite the sync engine's lock already
prevents, a "defect" that was a deliberate security trade-off. So the later audits were run as
a workflow: one reviewer per subsystem, and every finding then handed to three independent
skeptics, each prompted to *refute* it from the source and to default to refuted when they
could not confirm it. A finding survives on a majority. "Verified audit (3/3)" above means all
three failed to refute it. Findings that were fixed after a hand check against the code, when
the skeptic quota ran out, are marked as such in the commit that fixed them.

## Layer 4 — on a device, by a person

The three layers above share a ceiling: not one of them starts the app against a real account. Everything they establish is about *structure* — that the code reads the shapes correctly, composes them honestly, and does not crash. None of it is evidence that a user can sign in and see their own numbers, which is the entire product.

That layer cannot be automated from here, and the reason is not effort. Signing in requires a person to approve a code in their own browser. Refreshing spends a refresh token that all four providers rotate on use, so a test run consumes the maintainer's working credential and, if the app then fails to store the replacement, ends the account's access. Redeeming a reset credit spends a finite, real credit. Two of the three are irreversible actions on someone's paid subscription. They belong to the account holder, not to a build.

So the layer is written as a runbook instead. It is ordered so the cheapest checks fail first, and each step says what a *wrong* result looks like — because the failure mode throughout this project is a plausible number, not an error.

### Before signing in

1. **Install and open.** Both apps should reach the Overview screen with no accounts connected, and no crash. (CI proves this on iOS; on Android it is proven under Robolectric, not on hardware.)
2. **Every tab opens.** Overview, Accounts, Resets, Settings. An empty account list must render as empty, never as an error.

### Signing in — the first thing never yet exercised

3. **Codex and Grok** use the device authorisation grant: the app shows a code, you approve it in a browser, the app polls. *Wrong result:* the code never appears, or approval succeeds but the app keeps polling.
4. **Claude and Antigravity** use a loopback redirect the app receives itself on `127.0.0.1`. *Wrong result to watch for specifically:* the sheet closes and nothing happens, or the app hangs. Two defects in exactly this path were fixed by reading rather than by a test, because no test here can open that socket — a cancelled listener that never resumed, and a port conflict that reported nothing for five minutes. Neither has ever run.
5. **Sign in twice on one provider.** Both accounts must appear separately. If the second replaces the first, identity is collapsing on the wrong key.

### The numbers themselves

6. **Compare each account against the provider's own UI**, not against what looks plausible. This is the only check that can catch a parser that is confidently wrong, and it is worth doing per provider rather than for one.
7. **Check the reset times against the wall clock**, then again an hour later. A countdown that has not moved is a frozen composition; one that reads "resets in 12m" an hour after it should have reset is the widget staleness bug's shape.
8. **Add both widgets to the home screen.** The figures must match the app's. If they disagree, the widget is re-deriving something instead of reading the published snapshot.

### The destructive ones, last and deliberately

9. **Token refresh** happens on its own when an access token expires — roughly an hour. The honest way to observe it is to leave the app installed and open it the next day. *Wrong result:* every account reads "Sign-in expired". If that happens, the rotated token was not stored, and step 10 is off the table until it is fixed.
10. **Reset-credit redemption**, only on a Codex account that genuinely holds one, and knowing the credit is spent either way. The button is gated on `applicable_available_count`, so it should be absent when no credit applies — that absence is itself worth confirming before pressing anything.

### If a step fails

Capture the payload rather than the impression. A wrong number is evidence only when paired with what the endpoint actually returned; without it the next change is another guess, which is the failure this whole document exists to prevent.

## What is still not verified

Login, token refresh and credit redemption have never been run end to end from a device — steps 3 through 5, 9 and 10 above. That gap is stated plainly rather than papered over. What *has* been verified is narrower and real: the response shapes the parsers consume were fetched from live accounts, and the parsers were checked against them. Because the endpoints promise nothing, those captures are evidence about today's payloads, not a guarantee about tomorrow's; when a provider changes, the remedy is to re-capture and re-check, not to raise confidence.

A re-check against live accounts was attempted after the parser work and did not succeed: Codex answered 401, Claude 429, and xAI 401 with `auth_kind=none … no auth context`, meaning the request left without a credential attached. The attempt was abandoned rather than iterated on, because each retry is an authenticated request against a real paid account and repeated failures carry their own cost. The captured fixtures date from when that path did work, and they stand; what does not stand is any claim that the parsers were re-confirmed against live data afterwards.

## A deliberate omission

The reference implementation reaches Anthropic through TLS fingerprint mimicry and pinned header ordering, because Anthropic's edge runs bot detection. This app does not do that and will not: that is evasion. It sends an ordinary HTTPS request. The consequence is stated honestly rather than worked around — if the edge rejects an ordinary client, Claude support is structurally blocked rather than merely untested, and the remedy is to ask for a supported route or to drop the provider. The same principle runs through this document: an app whose promise is truthful numbers does not get to misrepresent itself to the endpoint in order to keep them.