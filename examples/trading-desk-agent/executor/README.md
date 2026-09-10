# Deterministic executor

One tick of the trading desk with no language model involved: fetch the five timeframes, classify
the regime, evaluate the nine strategies, score and rank them, size through the desk's own risk
checks, submit if it passes, and record the tick either way.

A tick takes about 2 seconds and costs **zero tokens**. The same decision on the same input is
always the same decision, which is what makes it testable and reproducible.

## Run it

```bash
# One tick, nothing submitted, JSONL ledger. Do this for a day first.
LEDGER_PATH=/tmp/desk/ticks.jsonl executor/runner.py --symbol BTCUSDT --dry-run

# Live, to the paper broker
LEDGER_PATH=/tmp/desk/ticks.jsonl executor/runner.py --symbol BTCUSDT

# Replay a recorded payload rather than fetching live data
executor/runner.py --fixture executor/fixtures/analysis-baseline.json --no-lock --dry-run
```

Every five minutes, from cron:

```
*/5 * * * * cd /path/to/trading-desk-agent && LEDGER_PATH=$HOME/desk/ticks.jsonl executor/runner.py --symbol BTCUSDT >> $HOME/desk.log 2>&1
```

A single-instance lock is taken by default. A tick can outlast its five-minute slot and cron will
start the next one regardless; without the lock two ticks could size against the same equity and
submit twice. A lock older than ten minutes is reclaimed, so a crashed process cannot wedge the desk.

## The ledger

One row per tick, traded or not. `LEDGER=sheets` with `SHEET_ID` and
`GOOGLE_APPLICATION_CREDENTIALS` writes to a Google Sheet tab; anything else writes JSONL.

Rows are append-only in both backends. `values.append` is a single atomic call, so concurrent ticks
cannot lose a row the way a read-modify-write on a state tab could.

Two columns carry the weight for the daily review:

- `near_misses` — `strategy:direction:condition=actual` for every strategy that failed on one or two
  conditions. This is the difference between "consider loosening the volume filter" and
  "`trend_continuation` failed on `entry_volume=0.37` against a threshold of 1.2".
- `skipped_directions` — strategies that matched but whose direction the desk forbids, so the
  opportunity cost of `allowShort: false` is measured rather than invisible.

The indicator values the strategies actually read are flattened into their own columns, so a
proposed threshold change can be replayed over history without keeping whole payloads.

## Outcomes

The ledger records what the desk decided. `outcomes.py` records what the market then did, which is
what turns "why did it not trade" into "should it have traded".

```bash
# hourly; idempotent, and only processes ticks whose 4h horizon has elapsed
executor/outcomes.py --ledger ~/desk/ticks.jsonl --out ~/desk/outcomes.jsonl

# what it says
executor/outcomes.py --out ~/desk/outcomes.jsonl --report
```

Per tick it walks the following 5m candles and records the price at +15m/+1h/+4h, the maximum
favourable and adverse excursion in ATR units, and — for a tick that produced a priced plan —
whether the stop or the target came first.

Two choices worth knowing:

- **A bar spanning both stop and target counts as a stop.** A 5m bar cannot say which came first,
  and assuming the target would quietly inflate every R figure in the report.
- **A near miss still gets a hypothesis.** The direction the blocked strategy would have taken is
  measured anyway, because that is exactly the case the review needs: it is the difference between a
  condition that blocked a real move and one that blocked noise.

The report's last section reads a median forward move per blocked condition. At or above +0.5 ATR
the condition blocked a real move; near zero it blocked noise; negative and it saved a loss.

Hourly, from cron:

```
17 * * * * cd /path/to/trading-desk-agent && executor/outcomes.py --ledger $HOME/desk/ticks.jsonl --out $HOME/desk/outcomes.jsonl >> $HOME/desk/outcomes.log 2>&1
```

## The catalogue

`strategies.json` holds the nine strategies as data: each condition is a timeframe, an operator, a
field path and per-direction bounds. Twenty-one operators cover all nine strategies; the operators
are code, tested once, and the conditions are data you can edit.

It is validated on **every** tick. Reading a half-finished edit must stop the tick — recorded as
`CATALOGUE_INVALID` — rather than produce a trade from a partial rule set.

## Tests

```bash
python3 -m unittest discover -s executor -p 'test_*.py'
```

Fixtures are transformed from a captured live payload rather than hand-written, so every field the
indicators produce is present. Asserting on synthetic-but-complete payloads is the only way to test
a verdict, because live data changes between runs.

## Two things worth knowing

**Targets must account for costs.** The desk computes
`net = (reward*qty - costs) / (risk*qty + costs)`, with costs in both terms, so a target set at
exactly `minRewardToRisk` is always rejected. `widen_target_for_costs` solves for the reward that
clears the minimum after costs, using the desk's own preview figures. Without it the executor never
trades, and the symptom is silence rather than an error.

**`stop_within_atr_budget` is a legitimate no-trade.** When a strategy's invalidation level sits
further from entry than `maxEntryDistanceInAtr` allows, the risk limit wins and the tick records
`RISK_CHECKS_FAILED`. Moving the stop closer to fit the budget would mean taking a trade whose
invalidation is wrong, and being stopped out by noise.
