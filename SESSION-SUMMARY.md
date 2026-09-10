# Session summary — Custom Agent Platform

Working notes from one long session. Covers what was built, what was found, what was verified, and
what is still open. Written so a new session can pick up without re-deriving any of it.

---

## Current state

**Branch:** `feat/dev-console-and-tenant-egress-allowlist`, 7 commits ahead of `main`, pushed.
**No PR was ever opened** — `gh` is not installed on this machine and no `GH_TOKEN` is set.

Committed (7):

| Commit | Change |
| --- | --- |
| `ec115a0` | fix(build) — `docker compose up --build` failure |
| `c690ced` | feat(runtime) — read-only turn-by-turn run trace |
| `6a688bf` | fix(runtime) — `request_clarification` enums; HITL was unreachable |
| `d5bc8e0` | feat — per-tenant HTTP egress allowlist + platform network guard |
| `2c318f0` | feat(dev-console) — browser console for testing |
| `f325b5e` | docs(trading-desk) — corrected egress instructions |
| `40bc1a8` | example(trading-desk) — dropped 1m, relabelled timeframe roles, flipped `allowShort` |

**Uncommitted** (two features, both working and tested):
- Authenticated HTTP tools (credentials, 6 auth schemes, AES-GCM at rest)
- The deterministic trading-desk executor, plus the 4h timeframe swap

**Tests:** 134 Java (9 contracts, 44 management, 81 runtime) + 47 Python. All passing.

### Running it

Services run on **remapped host ports** because 3306 and 8080 are occupied on this machine
(native `mysqld`, and a uvicorn app respectively):

```bash
docker compose -f compose.yaml -f <scratchpad>/compose.localports.yaml up -d
# management 18080, runtime 18081, mysql 13306
```

The override lives outside the repo. `dev-console/.env` points at the remapped ports.

---

## What was built

### 1. Docker build fix

Neither Dockerfile copied `agent-contracts`, which was added to the reactor after they were written.
Maven builds the whole reactor model before `-pl` selection, so the missing module directory failed
outright. Both the pom and the sources are now copied.

### 2. Run trace endpoint

`GET /api/v1/agent-runs/{runId}/turns` returns the whole run tree with per-turn token usage, prompt
messages, tool calls and invocations. The data was already in `agent_run_turns`; nothing read it
back, so a published run could only report aggregate usage.

Turns are served as recorded JSON rather than re-bound to `BaseAgentRequest`/`AgentMessage` — those
records are strict enough that one stale field would blank an entire turn.

### 3. HITL was unreachable — fixed

`clarificationTool()` declared `responseType` and `category` as bare `{"type":"string"}` while
`BuiltInToolExecutor` hard-rejects anything outside the enums. On a real run the model guessed
`json`, then `string`, then `TEXT` — three failures, no pause, turns burned to the limit. With the
enums declared the same run correctly reached `WAITING_FOR_HUMAN`.

### 4. Per-tenant egress allowlist

`AGENT_HTTP_TOOL_ALLOWED_HOSTS` was one process-wide list doing two unrelated jobs. Split:

- **Tenant data** — `tenant_egress_hosts`, administered via `/api/v1/egress-hosts`, `AGENT_ADMIN`
  only. Checked at publish (`422 tool-host-not-allowed`) and again at execution.
- **Platform policy** — after DNS resolution, any host resolving to loopback, link-local
  (`169.254.169.254`), private or CGNAT ranges is refused whatever a tenant registered. Redirects
  are no longer followed.

Making the property tenant-writable without the second half would have handed every tenant an SSRF
primitive.

### 5. Dev console

`dev-console/` — Vite + React + TS, four dependencies. Tools, Agents, Run (draft + published), HITL
inbox, Egress. A dev proxy is required because **neither service sends CORS headers**.

### 6. Authenticated HTTP tools

`HttpToolExecutor` previously read only `method` and `url` — no agent could reach any API needing a
key. Now six schemes: API key (header/query), static bearer, HTTP Basic, OAuth2 client credentials,
Google service account (RS256 JWT, JDK-only, no new dependency).

Secrets are AES-GCM encrypted with `AGENT_CREDENTIAL_KEY` (same value both services), never returned
by any endpoint. **`configuration` is returned verbatim by `GET /api/v1/tools`**, so it may only hold
a credential *reference*.

`tokenUrl` is tenant-supplied config producing an outbound request, so it goes through the egress
guard — otherwise it is a direct path to instance metadata.

### 7. Cost analysis → the hybrid decision

Running the trading agent every 5 min = **~28.8 M tokens/day**. Measured where it goes:

| Component | Share |
| --- | --- |
| System prompt + tool schemas, re-sent every turn | **77%** |
| Market data payload | 10% |
| Everything else | 13% |

The market data was never the problem. Conclusion: the agent is a rules engine in an LLM costume —
every input is numeric, every rule is an explicit predicate. Agreed plan: **deterministic executor
every 5 min (0 tokens), LLM reviewer once a day**.

### 8. The 4h timeframe

Swapped `1m` → `4h` (five timeframes stay five; `1m` was already dropped from the agent's
requirements and no strategy read it). Updated in all five places that enumerate timeframes.

### 9. The deterministic executor

`examples/trading-desk-agent/executor/` — ~2s per tick, zero tokens.

- `strategies.json` — nine strategies as data; 21 operators, each with a `stopRule`
- `dsl.py` — operators + evaluator, reports which condition failed and its actual value
- `regime.py` — regime classifier + the catalogue's scoring rubric
- `ledger.py` — 53-column row per tick; `JsonlLedger` (tested) and `SheetsLedger` (**untested**)
- `runner.py` — the tick; single-instance lock, dry-run, fixture replay
- `outcomes.py` — forward-return backfill and report
- `orders.py` — counts orders placed vs merely planned

---

## Bugs found, with evidence

**Cost-blind target.** The desk computes `net = (reward×qty − costs) / (risk×qty + costs)` with costs
in *both* terms, so a target set at exactly `minRewardToRisk` is always rejected. Every trade failed
until the algebra was solved properly (1.206 → 2.047).

**One stop rule for nine strategies.** `plan_prices` used the 15m swing for everything. For a
breakout that put the stop **765–1023 points** out against a **242–258 point** ATR budget, and the
desk rejected five setups that had scored 90–100 with R:R 2.043. Each strategy now carries its own
`stopRule`; the same case now trades with a 162-point stop.

**Weak-trend classifier ignored DI.** `_weak_trend` checked only price vs the 1h ema50, so price
below the average with bullish momentum was labelled `WEAK_DOWNTREND` while `bias()` on the same tick
said `BULLISH` — **22% of a 20-hour session**. The score then gave a long full alignment marks under
a label saying the trend was down. Now requires DI agreement; genuine disagreement falls through to
`UNCERTAIN`. Also added the missing "or structure mixed" clause.

**`valuePrefix` whitespace.** `setting()` stripped it, so `"Token "` produced the malformed header
`Tokenabc`. Caught by a test.

---

## What 20 hours of live dry-running showed

86 ticks, 20.7 hours, **35% coverage** (the Mac slept — 13 gaps over 11 min, one of 301 min).

- **Zero orders.** Root cause was the stop-rule bug, not a strict catalogue.
- `STRONG_UPTREND`/`STRONG_DOWNTREND`: **0 of 86 ticks**, so `trend_continuation` was never once
  eligible. `RANGE_BOUND` also never occurred.
- Regimes: UNCERTAIN 48%, WEAK_DOWNTREND 23%, WEAK_UPTREND 19%, BREAKOUT_EXPANSION 10%.
- Volume is a quiet killer: 5m ≥1.2 on only 20% of ticks, 15m ≥1.5 on 28%.

After the outcome backfill ran on 61 of those ticks:

| Hypothesis source | n | Median 1h move |
| --- | --- | --- |
| selected | 7 | **−1.500 ATR** |
| near_miss | 8 | +0.364 ATR |
| none | 46 | +0.402 ATR |

**The five blocked setups would have lost money** (median −0.16 R, none reached target or stop in
4h). Fixing the stop rule recovers trades, not winners. And the desk's *confident* signals were its
worst — on this sample the catalogue is worse than doing nothing.

Sample sizes are far too small to act on. The point is that the claims are now falsifiable.

---

## Decisions taken (and why)

| Decision | Choice |
| --- | --- |
| Turn data for published runs | Add a read-only runtime endpoint (`/turns`, not `/events` — those tables are unused) |
| Egress writes | `AGENT_ADMIN` only — an author who approves their own destination is not a control |
| Credential storage | Encrypted in the management DB, env-provided AES-GCM key, `key_id` for rotation |
| Credential management access | Open to any tool-authoring role (user's call; egress allowlist is then the only barrier) |
| Auth flows | Static + machine-to-machine; user-delegated OAuth deliberately excluded |
| Desk architecture | Hybrid: deterministic executor + daily LLM reviewer |
| Strategy catalogue | Data-driven predicate DSL |
| Ledger | Google Sheets + object storage for blobs |
| Desk state | Derived from the ledger, not a mutable tab |
| Reviewer host | Platform scheduled agent (bridged by OS cron until the scheduler exists) |
| Reviewer autonomy | On/off switch; bounded envelope when on |

Two plan documents were published as artifacts:
- Scheduling / unattended runs: https://claude.ai/code/artifact/d2dfd208-6286-4f41-b583-0b81c872e154
- Two-loop trading desk: https://claude.ai/code/artifact/02413b1a-b0ed-44cc-9125-6289d63e5be5

---

## Verified vs unverified

**Verified against live systems:** the Docker build; the trace endpoint on real runs; a full HITL
round trip (pause → inbox → answer → resume → SUCCEEDED); egress publish-time and execution-time
enforcement including revocation; credential CRUD with no secret ever returned and no plaintext in
the DB; an authenticated tool call reaching httpbin with the right header; a Google service-account
JWT signed and rejected only by Google (fake key); the executor placing `PAPER-0A41D290C5B6`; the
outcome backfill over 61 real ticks.

**Not verified:** `SheetsLedger` (no service-account key — it is written but has never run);
the Google Drive tool end to end (same reason); per-commit builds on the branch (only the tip).

---

# Open points

### Blocking / needs a decision

1. **No PR exists.** Branch is pushed; `gh` is not installed and no token is set. Open it at
   `https://github.com/manishkashyap/BuildCustomAgents/pull/new/feat/dev-console-and-tenant-egress-allowlist`.
2. **Two features are uncommitted** — authenticated tools, and the executor + 4h swap. Both tested.
3. **`allowShort` contradiction.** `desk-config.json` says `false`; the agent context in commit
   `40bc1a8` says `true`. The desk wins, so four of nine strategies can never place. Pick one.
4. **`SheetsLedger` is unproven.** Needs a service-account key and `pip install -r
   executor/requirements.txt`. Until then the executor writes JSONL.
5. **A fake `google-drive` credential sits in `DEV_LICENSE`** from testing. Names are unique per
   tenant — rotate it in place, do not re-create.

### Platform gaps found but not fixed

6. **No authentication on tool endpoints the runtime calls.** Since HTTP tools cannot send an
   `Authorization` header, any endpoint they call must accept unauthenticated requests. The real fix
   is authenticated HTTP tools as a platform feature.
7. **`CustomToolController` enforces no roles at all** — zero checks, versus eleven in
   `CustomAgentController`. All four roles can do everything to tools.
8. **`AgentExecutionService.get` returns `TokenUsage.ZERO`**, so fetching an already-started run
   reports zero tokens. The console sources usage from `/turns` and says so inline.
9. **DNS rebinding.** The egress guard checks resolved addresses but does not pin the connection.
10. **Tool failure classification was built then reverted** at the user's request (the `502` was the
    wrong status). The underlying observation stands: every tool failure returns
    `ToolProgress.completed(errorNode)`, so an unreachable tool is handed to the model as a puzzle
    and `max-turns` is the only exit.
11. **Scheduling (phases 1–4) is not built.** `AgentRunStatus.PENDING` exists in the enum and the V5
    constraint but is never set anywhere. Execution is fully synchronous.
12. **The documented `/agent-runs/{rootRunId}/events` timeline is unimplemented**, and its
    `agent_run_events` / `agent_run_steps` tables have no entities. Same for `pause`, `resume`,
    `assignment`.

### Trading desk

13. **The daily reviewer agent does not exist.** Phases 6–9 of the hybrid plan.
14. **Executor is BTCUSDT-only and still in `--dry-run`.** Keep it there — the strongest signal in
    the data is that selected strategies moved −1.5 ATR against themselves.
15. **Outcome sample sizes are far too small** (4–9 observations per condition). Let it run a week
    before changing any threshold.
16. **Skipped ticks write no ledger row**, so lock contention and data failures are invisible.
17. **Raw payload snapshots are not stored**, so a changed catalogue cannot be replayed over history.
18. **Machine sleep costs ~65% of ticks.** `caffeinate -s`, or launchd with `StartInterval`.
19. **Prompt caching is absent from both provider adapters** — every request re-sends the full
    prefix at full price. Platform-wide win, unrelated to the desk.

### Housekeeping

20. Ports are remapped via a scratchpad override; free 8080/3306 to return to the documented ports.
21. Docker Desktop stopped twice mid-session; restart it before any build.
22. `~/desk/` holds the wrapper scripts, ledger and outcomes — outside the repo.
