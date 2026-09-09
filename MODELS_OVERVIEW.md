# Gateway models overview

OpenAI-compatible gateway (CLIProxyAPI) reached at `$CLIPROXY_BASE_URL`, authenticated with
`Authorization: Bearer $CLIPROXY_API_KEY`. Both values are read from the environment and never
written into this repository.

## Verified routes

| Route | Status |
|---|---|
| `GET /models` | works — 200, 70 models |
| `POST /chat/completions` | works — used for all delegation |
| `POST /completions` | available, not used (legacy text format) |
| `POST /responses` | available, fallback for models that require it |
| `POST /messages` | **not available on this instance — 404. Never call it.** |

## Models in use

Restricted by instruction to three, and deliberately **no Claude models through the gateway** —
the orchestrator is already Claude, so routing coding work to a Claude model behind the proxy
would add a network hop and a second quota pool for no gain in capability.

| model id | family | strengths | use for |
|---|---|---|---|
| `gpt-6-astra` | OpenAI GPT-6 | strongest general reasoning of the three; largest implicit budget | **PRIMARY_WORKER 1** — multi-file changes, anything needing design judgement |
| `gpt-5.6-sol` | OpenAI GPT-5.6 | fast, solid on well-specified single-file work | **PRIMARY_WORKER 2** — mechanical and localised edits |
| `grok-4.6` | xAI Grok 4.6 | reasoning model — spent 53 reasoning tokens on a two-word reply, so it deliberates before answering | **PRIMARY_WORKER 3** — analysis, review passes, and the independent third opinion |
| `gemini-3.8-flash` | Google Gemini 3.8 | fast and cheap | **LOW_RISK_WORKER** — only for tasks where a mistake is cheap and obvious: comment wording, doc typos, formatting, mechanical renames. Never for logic, parsing or auth. |
| `z-ai/glm-5.3-free` | Z.ai GLM 5.3 (second gateway) | strong on long-running coding work; deliberately slow | **BACKGROUND_WORKER** — large self-contained jobs nothing is waiting on: whole test suites, broad refactors. Never on the critical path. |

`z-ai/glm-5.3-free` is served by a **separate gateway** (`$TOKENROUTER_BASE_URL`, its own key),
not by CLIProxyAPI. The delegation helper maps each model id to its gateway, so a task is routed
by capability without the caller knowing which endpoint answers. That gateway currently exposes
exactly one model.

All four smoke-tested with a minimal `chat/completions` call: HTTP 200, correct reply, usage
reported. None needed the Responses API.

Note on the id: `gemini-3.8-flash-high` does **not** exist on this gateway — it returns
`400 unknown provider for model`. Tier suffixes (`-low` / `-medium` / `-high`) exist only on the
3.6 and 3.7 flash lines; 3.8 ships as a single `gemini-3.8-flash`. That is the id in use.

Two things the usage numbers already tell us. `grok-4.6` reports
`completion_tokens_details.reasoning_tokens` and served 128 cached prompt tokens, so it is a
reasoning model with prompt caching — good for review, more expensive per call for trivial work.
The two GPT ids reported zero reasoning tokens and no cache hit on a cold call.

## What else the gateway exposes

Recorded for context only — none of it is used under the current instruction.

- **Claude family** (~15 ids): `claude-opus-5`, `claude-opus-4-8`, `claude-sonnet-5`,
  `claude-fable-5-1`, `claude-haiku-4-5-20251001`, plus dated 4.x builds. Excluded by instruction.
- **OpenAI family**: `gpt-5.5`, `gpt-5.6-luna`, `gpt-5.6-terra`, `gpt-5.3-codex-spark`,
  `gpt-oss-120b-medium`, `codex-auto-review`, and image models `gpt-image-2` / `gpt-image-1.5`.
  `gpt-5.3-codex-spark` and `codex-auto-review` are the coding-specialised ids here and would be
  the natural third and fourth workers if the restriction were lifted.
- **Gemini family** (14 ids): `gemini-3.1-pro`, `gemini-3.1-pro-low`, `gemini-3.1-flash-lite`,
  `gemini-3-flash`, `gemini-3.1-flash-image`, and the 3.6/3.7 flash lines at
  low/medium/high. (`gemini-3.8-flash` is in use — see above.)
- **Grok family** (~15 ids): `grok-4.5`, `grok-4.3`, `grok-composer-2.5-fast`,
  reasoning/non-reasoning 4.20 variants, plus `grok-imagine-*` image and video models.
  (`grok-4.6` is in use — see above.)
- **Free/community tier**: `openrouter/free`, `z-ai/glm-5.3-free`,
  `nvidia/nemotron-3-ultra-550b-a55b:free`, `minimax/minimax-m3:free`,
  `cohere/north-mini-code:free`, `poolside/laguna-xs-2.1:free`, and others. Useful as
  zero-cost overflow if every primary is rate-limited, at noticeably lower quality.

Raw `GET /models` returns a standard OpenAI list envelope: `{"object":"list","data":[{"id":…,
"object":"model","created":…,"owned_by":…}]}`, 6,135 bytes, 70 entries, no capability metadata —
so model choice here is by reputation and smoke test, not by anything the gateway advertises.

## Reasoning effort

Set per model, sent as `reasoning_effort` on every call.

| model | effort |
|---|---|
| `gpt-6-astra` | `high` |
| `gpt-5.6-sol` | `max` |
| `grok-4.6` | `xhigh` |
| `gemini-3.8-flash` | `high` |
| `z-ai/glm-5.3-free` | `max` |

The gateways accept every one of these values without error. Whether each is honoured is another matter:
`grok-4.6`, `gemini-3.8-flash` and `z-ai/glm-5.3-free` report non-zero
`completion_tokens_details.reasoning_tokens` — GLM spent 66 of them on a two-word reply — so
effort demonstrably reaches those three. The two GPT ids reported **zero** reasoning tokens on a
trivial prompt even at `xhigh` and `max`, but 280–311 on real work, so they report reasoning
only when they actually do some. Worth knowing before attributing an answer's quality to a
setting.

## Observed capability, not advertised

The gateways publish no capability metadata, so this is what the work actually showed.

| model | measured | verdict |
|---|---|---|
| `gpt-6-astra` | 3 tasks, 3 successes, 74–116s, 280–311 reasoning tokens | The workhorse. On the Codex task it inferred a companion change the brief never named — that `attributes` must store only the genuine `chatgpt_account_id`, or the header it was told to fix inherits the `sub` fallback through the back door. That is reasoning about consequences, not pattern-matching, and it is why Astra gets anything where being *almost* right is expensive. |
| `gpt-5.6-sol` | 1 timeout (524) on a 10 KB prompt at `max` | Not yet proven here. The one failure was a gateway timeout, not a wrong answer, and `max` effort on a large prompt is the likely cause. Keep it on genuinely small inputs. |
| `grok-4.6` | 1 success (trivial), 1 timeout (524) on an 11 KB analysis prompt | Same pattern as Sol: fine on small inputs, times out on large ones. The reasoning-token accounting is real, so it is worth keeping for judgement calls — just not for big prompts. |
| `gemini-3.8-flash` | 1 success (trivial), 1 unexplained failure | Held to low-risk work regardless of how it performs. The point is not its accuracy; it is that a wrong answer must be caught by a compile, a test, or a glance at the diff. |
| `z-ai/glm-5.3-free` | smoke test only; 8s for a two-word reply at `max` | Reserved for large background jobs. Its latency is the constraint, so nothing on the critical path waits on it. |

**The pattern worth acting on: prompt size predicts failure better than model choice.** Both 524s
came from prompts over 10 KB. Large context goes to Astra or GLM; the others get small, sharply
scoped inputs.

## Operational notes

Two things about this gateway that cost a round trip to discover.

**It is behind Cloudflare, and Cloudflare fingerprints the client.** Python's `urllib` gets a
flat `403 error code: 1010` ("banned based on your browser's signature") on every call, while
`curl` to the identical endpoint succeeds. The delegation helper therefore shells out to `curl`
rather than using `urllib`. If a future tool starts getting 1010, that is the cause — not the
key, not the route.

**`524` is the failure to expect, not `429`.** No quota error has been seen yet. What has been
seen is Cloudflare `524` — origin took too long — when a slower model at a high effort setting
gets a large prompt. It is transient and another worker usually survives it, so the fallback
treats the whole 5xx/52x family, `curl` exits and timeouts as retryable, not as a request
defect. A malformed request, by contrast, must NOT rotate: another model will fail identically.

**The key never appears in `argv`.** It goes to `curl` through a `0600 --config` file that is
deleted after the call. Passing it as `-H` would have put it in `/proc`, readable by every
process on the machine for the duration.

## Delegation protocol

- The orchestrator (Claude, this session) plans, splits, reviews and merges. It does not run
  work over the proxy itself.
- Implementation subtasks go to a PRIMARY_WORKER via `POST /chat/completions`. Trivial,
  low-blast-radius edits may go to the LOW_RISK_WORKER instead — the test for that is whether a
  wrong answer would be caught immediately by a compile, a test, or a glance at the diff.
- On `429` / `402` / `403` quota / `5xx` / "usage limit" / "rate limit" / empty reply: retry once
  on the next primary in the rotation (`gpt-6-astra` → `gpt-5.6-sol` → `grok-4.6`), then the
  orchestrator takes the task over directly and marks it `[FALLBACK-CLAUDE: reason]`. No task
  ever blocks waiting for quota.
- Every delegated result is reviewed and compiled locally before it is committed. A model's
  output is a proposal, not a merge.
