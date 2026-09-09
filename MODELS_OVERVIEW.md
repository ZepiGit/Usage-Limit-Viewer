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
