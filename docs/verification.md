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

## What is still not verified

Login, token refresh and credit redemption have never been run end to end from a device. That gap is stated plainly rather than papered over. What *has* been verified is narrower and real: the response shapes the parsers consume were fetched from live accounts, and the parsers were checked against them. Because the endpoints promise nothing, those captures are evidence about today's payloads, not a guarantee about tomorrow's; when a provider changes, the remedy is to re-capture and re-check, not to raise confidence.

## A deliberate omission

The reference implementation reaches Anthropic through TLS fingerprint mimicry and pinned header ordering, because Anthropic's edge runs bot detection. This app does not do that and will not: that is evasion. It sends an ordinary HTTPS request. The consequence is stated honestly rather than worked around — if the edge rejects an ordinary client, Claude support is structurally blocked rather than merely untested, and the remedy is to ask for a supported route or to drop the provider. The same principle runs through this document: an app whose promise is truthful numbers does not get to misrepresent itself to the endpoint in order to keep them.