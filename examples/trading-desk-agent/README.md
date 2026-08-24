# Trading desk agent

A systematic multi-timeframe trading agent: it classifies the market regime, ranks a fixed
catalogue of nine strategies, and either submits one risk-checked paper bracket order or returns
`NO_TRADE` with a reason. Five `HTTP` tools, one agent, and a local sidecar that does every
calculation.

| File | What it is |
|---|---|
| `desk.py` | The sidecar. Five endpoints, stdlib only, no dependencies |
| `marketdata.py` | Candle retrieval, the 200-closed-candle contract, per-timeframe analysis |
| `indicators.py` | EMA, SMA, RSI, MACD, ATR, Bollinger, ADX, swings, zones |
| `broker.py` | Paper account, position sizing, the pre-trade risk gate, bracket orders |
| `desk-config.json` | Instrument and every risk limit the desk enforces |
| `tool-market-analysis.json` | `market_get_multi_timeframe_analysis` — all five timeframes in one call |
| `tool-market-quote.json` | `market_get_quote` — bid, ask, spread, top-of-book |
| `tool-broker-account-state.json` | `broker_get_account_state` — equity, exposure, circuit breakers, positions |
| `tool-broker-preview-order.json` | `broker_preview_order` — sizing and every pre-trade check, no side effect |
| `tool-broker-submit-order.json` | `broker_submit_bracket_order` — approval-gated, idempotent, `WRITE` |
| `agent-trading-desk.json` | `Trading Desk` — the agent |
| `seed.sh` | Creates and publishes the tools, creates the agent as a draft |

## Required before anything works

**Allowlist the sidecar host.** The allowlist is empty by default (`application.yml` binds
`AGENT_HTTP_TOOL_ALLOWED_HOSTS` with no fallback), so every HTTP tool fails until you set it:

```bash
AGENT_HTTP_TOOL_ALLOWED_HOSTS=host.docker.internal
```

Add it to your `.env` and restart the runtime, or you get
`HTTP tool host is not allowlisted: host.docker.internal`. Keep the weather hosts too if you want
both examples working — the value is a comma-separated list.

The tool URLs point at `host.docker.internal:8090`, which is how a container reaches a process on
the host. On Docker Desktop that name resolves already. On Linux, add this to the `agent-runtime`
service in `compose.yaml`:

```yaml
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

If you run the runtime outside Docker, change the host in the five tool files to `localhost`.

**Start the sidecar.** It needs outbound access to `api.binance.com`:

```bash
python3 examples/trading-desk-agent/desk.py --port 8090
```

## Seed it

```bash
./examples/trading-desk-agent/seed.sh
```

The agent is left as a `DRAFT`. Pass `--publish` to publish it.

## Why a sidecar instead of a smarter prompt

The brief this agent implements says *calculate indicators using code, not mental arithmetic*, and
*use the LLM primarily to interpret the regime and rank already-defined strategies*. The platform
executes `HTTP`, `BUILT_IN` and `CUSTOM_AGENT` tools — there is no code-execution tool — so an
agent handed 200 candles would have to compute EMA-200 and Wilder's ADX in its head. It would
produce numbers. They would look plausible. Some of them would be wrong, and nothing in the trace
would show which.

So the split is drawn at arithmetic:

- **The sidecar decides every number.** Validation verdict, indicators, position size, fees,
  reward-to-risk, and pass or fail on every risk check.
- **The agent decides every judgement.** Which regime this is, which catalogue strategies apply,
  how to score them, whether the best one is good enough, and where the stop and target belong.

That split is also what makes the risk rules enforceable. `broker_submit_bracket_order` re-runs the
full check set server-side and rejects on its own findings; it does not trust the plan it was
handed. A model that talks itself into a trade still has to get past code it cannot argue with,
including the score threshold and the runner-up margin — those are checked by the broker, not just
requested in the prompt.

## The 200-candle contract

Binance's public REST API is keyless, returns JSON, accepts an explicit candle count, and offers
exactly the five intervals the playbook assigns jobs to (`1m`, `5m`, `15m`, `1h`, `1d`). It also
returns the *forming* candle as the last element, which is the single easiest way to build a
strategy that cannot exist — its high, low and close are still changing.

`marketdata.closed_candles` therefore asks for 205 and keeps only rows whose `closeTime` has
already passed, taking the newest 200. Everything downstream sees closed candles or nothing.

`marketdata.validate` then runs the checks the brief lists — count, chronological order, interval
alignment, boundary alignment, OHLC sanity, non-positive prices, negative or all-zero volume,
duplicates, and staleness against two intervals — and returns them as readable strings. A
timeframe with any issue is dropped from `timeframes` entirely rather than returned with a
warning, so there is nothing for the model to trade on and a caveat to ignore.

Raw candles are never returned. The agent gets roughly 9.5 KB of derived numbers for all five
timeframes instead of 1,000 OHLCV rows.

## Executor constraints these definitions work around

**Every URL placeholder must be a required input.** `UriComponentsBuilder.buildAndExpand` throws
when a `{placeholder}` has no argument, surfacing as `Unable to expand HTTP tool URL template`.
That is why all nine of `broker_preview_order`'s inputs are required, including `runnerUpScore`,
which reads like something you could omit. Pass `0` when there is no second candidate.

**Arguments not named in the template are dropped** on a `GET`, so every schema sets
`additionalProperties: false`.

**A tool declares its response shape, not just its inputs.** Each of the five tools carries an
`outputSchema` alongside its `inputSchema`. Function-calling APIs have no field for a response
shape, so `PublishedToolDefinition.modelFacingDescription()` appends it to the description the
provider receives. That is what keeps the descriptions short: `market_get_multi_timeframe_analysis`
returns about forty fields per timeframe, and naming them in prose ran past the 1,000-character
description limit. As a schema they cost 4,705 characters and the description drops to 668.

Watch for repetition when writing one. The first version of that schema inlined the same timeframe
object five times and came to 17,198 characters — over the 8,000-character per-tool budget, which
rejected it at publish. Hoisting the shape into `$defs` and referencing it five times cut it to
4,705 with no loss of detail.

**A `POST` sends the whole argument object as the body.** `HttpToolExecutor` calls
`.body(executionRequest.arguments())`, so `broker_submit_bracket_order` needs no placeholders at
all — the ten fields arrive as JSON. The endpoint validates them itself.

**A failed tool call arrives as data, not as a run failure.** `AgentExecutionService.dispatch`
catches any `RuntimeException` from an executor and feeds the model
`{"error": "...", "tool": "..."}` as the tool result. The run continues, and the model has to
notice. That is why the agent has explicit rules for an `error` field: a model that reads a failed
call as a neutral reading will invent the rest.

The sidecar still answers `200` with `{"ok": false, "error": ..., "reason": "DATA_INVALID"}` for a
bad symbol or an unreachable exchange, but the reason is precision rather than survival — it
separates "the desk says this symbol is unusable" from "the desk could not be reached at all", and
those call for different reason codes.

**Draft tests mock every non-`READ` tool.** `ToolExecutorRegistry.mustMock` simulates any tool
whose `executionPolicy.operation` is not `READ`, returning `{"testMode": true, "mocked": true,
"status": "SIMULATED_SUCCESS", ...}` with no `brokerOrderId`. The agent has a rule for exactly this:
report `execution.status` `SIMULATED`, `submitted` false. The four read tools call the real sidecar,
which is what you want — a draft test that mocked the market data would test nothing.

**`request_clarification` is not in `allowedTools`.** The runtime injects it on every run
(`AgentExecutionService.clarificationTool()`). Listing it breaks publishing, because dependency
validation looks for a published `custom_tools` row of that name and finds none.

**The turn budget is eight by default** (`AGENT_RUNTIME_MAX_TURNS`). The happy path is five tool
calls — account, analysis, preview, quote, submit — plus the final answer, so it fits. Retrieving
each timeframe separately would spend five turns on data alone, which is the practical reason
`market_get_multi_timeframe_analysis` returns all five in one response rather than taking a
`timeframe` argument.

## Prompt budget

Publishing checks that a definition still fits in a model request. This example sits well inside
every limit:

| | Characters | Budget |
|---|---|---|
| Largest single tool (`market_get_multi_timeframe_analysis`) | 5,715 | 8,000 |
| The agent's own model-facing fields | 26,852 | 60,000 |
| The agent plus all five tools | 48,728 | 120,000 |

About 12,000 tokens of definition per turn, before any candle data. Worth knowing when reading the
turn budget above: definitions are not free, and the assembled figure is what every one of the
eight turns carries.

## Approval, expiry and the stale-fill problem

`broker_submit_bracket_order` is `WRITE` / `HIGH` with `approval.required: true`, so the runtime
pauses and raises a `TOOL_APPROVAL` interaction before it executes. Draft tests raise the same
interaction and then mock the call.

That creates an obvious hazard: the desk's order expiry is 60 seconds, but a human might approve
five minutes later, and by then the plan is a fiction. The approval window is capped at 300
seconds (`HumanInteractionPolicyRules` allows 60 to 604,800), and more importantly the endpoint
re-fetches the live book and re-runs every check on resume. An order approved too late comes back
`REJECTED` on `entry_near_market` rather than filling at a price nobody agreed to. The agent's
instructions call that a correct outcome, not an error to retry.

## Protection is part of the entry, not a follow-up

The brief says to submit the entry, verify the fill, then place the protective stop — and to close
the position if protection cannot be established. That sequence has a hole: between the fill and
the stop, the position is naked, and the second call is exactly the one that can fail.

`broker_submit_bracket_order` takes entry, stop and target together. The paper broker registers all
three atomically on fill, so `protectiveStopOrderId` and `targetOrderId` come back with the fill or
nothing does. The emergency-close path the brief describes cannot be reached because the state it
protects against cannot occur.

## Idempotency

The key is `symbol|strategy|direction|setupTimestamp`, where `setupTimestamp` is the 15m
`lastClosedAtMs` — the close of the candle that produced the setup. The same setup always produces
the same key, and the endpoint returns the original order with `duplicate: true` rather than
opening a second position. The agent is told never to retry a submission after an ambiguous
failure, and to look the key up in `broker_get_account_state.recentOrders` first.

## Risk model

`desk-config.json` holds the numbers. The brief left them as placeholders; these are concrete
defaults, deliberately conservative, and the broker enforces them whatever the prompt says:

| Setting | Value |
|---|---|
| Mode / instrument | `PAPER` / `BTCUSDT` on Binance spot |
| Starting equity | 100,000 |
| Long / short | long only |
| Risk per trade | 0.35% of equity |
| Max position / portfolio exposure | 15% / 30% |
| Max daily loss / consecutive losses | 2% / 3 |
| Minimum reward-to-risk after costs | 2.0 |
| Minimum strategy score / margin over runner-up | 75 / 8 |
| Max spread / estimated slippage | 0.05% / 0.05% |
| Max leverage | 1.0 |
| Cooldown / order expiry | 30 min / 60 s |
| Max stop distance / entry drift | 1.5 ATR / 0.25% |
| Max share of top-of-book | 25% |
| Fees | 10 bps per side |

Sizing starts at `equity x risk% / stop distance` and is then reduced — never increased — by
position exposure, portfolio exposure, buying power, leverage, a cap on how much of the visible
book one order may take, and the exchange lot step, rounding down. The preview reports
`bindingConstraint` so you can see which cap decided the size. It is usually not the risk budget.

## Verified against the live API and the running sidecar

Every endpoint was called for real while writing this, against `BTCUSDT`:

```
GET /analysis                        200   9,588 bytes, 1.5s, all five timeframes valid
GET /analysis?symbol=NOTAPAIR        200   {"ok": false, ... "reason": "DATA_INVALID"}
GET /quote                           200   spread 0.000013%, within the 0.05% limit
GET /account                         200   equity 100000, no breakers, filters from exchangeInfo
GET /order-preview  (RR 1.61)        200   failures: ["reward_to_risk_after_costs"]
GET /order-preview  (RR 2.42)        200   passed, quantity 0.10202, binding cap bookLiquidity
GET /order-preview  (SELL)           200   failures: ["direction_allowed"]
GET /order-preview  (score 70/68)    200   failures: ["score_meets_threshold",
                                                      "score_margin_over_runner_up", ...]
GET /order-preview  (price 70000.005) 200  failures: ["prices_on_tick", "entry_near_market"]
POST /orders                         200   FILLED, PAPER-8556681B69BE, stop and target ids present
POST /orders (same key)              200   duplicate: true, same brokerOrderId, nothing created
POST /orders (new key, position open) 200  REJECTED: ["cooldown_elapsed", "no_conflicting_position"]
```

Worth reading the first preview twice. The plan risked 162 on a 15,000 notional and the round-trip
fee was 30 — nearly 20% of the risk — which dropped a nominal 3.05:1 setup to 1.61:1 after costs
and failed the gate. Exposure-capped positions carry cost drag that risk-capped ones do not,
because the fee scales with notional while the risk does not. That is the kind of thing the
`reward_to_risk_after_costs` check exists to catch, and the kind of thing a model doing the
arithmetic in its head will get wrong in the optimistic direction.

## Trying it

In Agent Studio, **Draft test**, paste the agent id:

- task `Evaluate BTCUSDT and either submit one validated order or return NO_TRADE.`,
  input `{"symbol": "BTCUSDT"}` — the main path. Expect `broker_get_account_state` and
  `market_get_multi_timeframe_analysis` in the trace, and, if a strategy qualifies, an approval
  interaction on `broker_submit_bracket_order` followed by a `SIMULATED` execution block.
- Stop the sidecar and run again — expect a failed tool call, not an invented analysis.
- Set `"minStrategyScore": 95` in `desk-config.json`, restart the sidecar, and run again — expect
  `NO_TRADE` with `RISK_CHECKS_FAILED` and `score_meets_threshold` in `risk_checks.failures`.
- Set `"maxSpreadPercent": 0.000001` and run again — expect `SPREAD_TOO_WIDE`.
- Run twice in a row after a fill — expect the second run to stop at `POSITION_EXISTS` before it
  fetches any market data.
- Load the definition first so the allowlist and output-schema checks actually run.

## When every tool call fails

The symptom is a `NO_TRADE` whose `data_validation.issues` names a tool failure rather than a
market-data problem:

```json
"issues": ["HTTP tool call failed to reach market data service."]
```

Work through these in order:

1. **Is the sidecar running?** `curl localhost:8090/health` should return
   `{"ok": true, "mode": "PAPER", ...}`.
2. **Is the host allowlisted?** Without it the error names the host:
   `HTTP tool host is not allowlisted: host.docker.internal`.
3. **Can the runtime resolve the host in the tool URLs?** This is the one that catches people.
   The five tool files point at `host.docker.internal`, which resolves *inside* a Docker container
   on Docker Desktop and nowhere else. Running the runtime from an IDE or `mvn spring-boot:run`
   puts it on the host, where the name does not resolve at all:

   ```
   ping host.docker.internal
   ping: cannot resolve host.docker.internal: Unknown host
   ```

   Either change the host to `localhost` in the five tool files and re-seed, or map the name on the
   host so one definition works in both places:

   ```
   127.0.0.1  host.docker.internal      # /etc/hosts
   ```

4. **Can the sidecar reach Binance?** `curl -s "https://api.binance.com/api/v3/ping"` from wherever
   the sidecar runs.

The agent is expected to return `NO_TRADE` for all four, with reason `TOOL_UNAVAILABLE` when the
call failed and `DATA_INVALID` when the desk answered but the data was unusable.

## What this example is not

- **Not a backtest and not a strategy recommendation.** The nine strategies are a plausible
  catalogue written to be *checkable* against the fields the analysis tool returns, not a tested
  edge. The brief is explicit that each strategy should be backtested separately and the whole
  agent paper-traded before live orders; nothing here does either.
- **Not connected to a broker.** The account, the fills and the order ids are simulated inside
  `desk.py`. There is no venue credential anywhere in this example, and `mode` is `PAPER`
  everywhere. Pointing it at a real broker means writing a real adapter and re-checking every
  assumption in `broker.py`.
- **Not durable.** Desk state lives in process memory. Restarting the sidecar clears positions,
  the daily loss counter and the idempotency ledger.
- **Single instrument, spot only.** Correlated-exposure scoring is in the rubric but the desk holds
  one instrument, so it never binds.
- **Binance public data may not reach you.** The API is geo-restricted in some jurisdictions and
  answers `451`. It returned `200` from where this was written; if it does not from where you are,
  swap the base URL in `marketdata.py` for another keyless JSON venue and adjust the response
  parsing.
