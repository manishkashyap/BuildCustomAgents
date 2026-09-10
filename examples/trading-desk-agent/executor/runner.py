"""
One tick of the trading desk. No language model involved.

Fetch the five timeframes, classify the regime, evaluate the nine strategies, score and rank them,
size through the desk's own risk checks, submit if it passes, and record the tick either way.

Run it every five minutes from cron:

    */5 * * * * cd .../trading-desk-agent && executor/runner.py --symbol BTCUSDT >> ~/desk.log 2>&1

Start with --dry-run for a day. It does everything except submit, so the ledger fills with the
decisions it would have made and you can read them before any order exists.
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))              # executor modules
sys.path.insert(0, str(HERE.parent))       # broker, marketdata, indicators

import broker            # noqa: E402
import marketdata        # noqa: E402
import dsl               # noqa: E402
import ledger as ledger_module   # noqa: E402
import regime as regime_module   # noqa: E402

CATALOGUE_PATH = HERE / "strategies.json"
DESK_CONFIG_PATH = HERE.parent / "desk-config.json"
LOCK_PATH = Path(os.environ.get("EXECUTOR_LOCK", "/tmp/trading-desk-executor.lock"))
LOCK_STALE_SECONDS = 600


class TickSkipped(Exception):
    """The tick cannot proceed. Recorded in the ledger, never traded around."""

    def __init__(self, reason_code, detail):
        super().__init__(detail)
        self.reason_code = reason_code
        self.detail = detail


def acquire_lock():
    """
    One executor at a time.

    A tick can outlast its five-minute slot, and cron will start the next one regardless. Without
    this, two ticks could size against the same equity and submit twice. A stale lock is reclaimed
    so a crashed process does not wedge the desk permanently.
    """
    now = time.time()
    if LOCK_PATH.exists():
        age = now - LOCK_PATH.stat().st_mtime
        if age < LOCK_STALE_SECONDS:
            raise TickSkipped("EXECUTOR_ALREADY_RUNNING",
                              f"lock held for {int(age)}s by pid {LOCK_PATH.read_text().strip()}")
        print(f"reclaiming a stale lock ({int(age)}s old)", file=sys.stderr)
    LOCK_PATH.write_text(str(os.getpid()))


def release_lock():
    try:
        LOCK_PATH.unlink()
    except FileNotFoundError:
        pass


def load_catalogue():
    """
    Loads and validates the catalogue on every tick.

    Validated every time because it is meant to be hand-editable: reading it mid-edit must stop the
    tick rather than trade on half a change.
    """
    try:
        catalogue = json.loads(CATALOGUE_PATH.read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise TickSkipped("CATALOGUE_INVALID", f"{CATALOGUE_PATH.name}: {error}") from error
    try:
        return dsl.validate_catalogue(catalogue)
    except dsl.CatalogueError as error:
        raise TickSkipped("CATALOGUE_INVALID", str(error)) from error


def fetch_analysis(symbol, timeframes):
    """The five timeframes, computed here rather than fetched from the sidecar."""
    now = int(time.time() * 1000)
    payload = {"symbol": symbol, "asOfMs": now, "timeframes": {}}
    issues = []
    for interval in timeframes:
        try:
            candles = marketdata.closed_candles(symbol, interval, now)
            issues.extend(marketdata.validate(candles, interval, now))
            payload["timeframes"][interval] = marketdata.analyse(candles, interval)
        except marketdata.DataError as error:
            issues.append(f"{interval}: {error}")
    try:
        payload["quote"] = marketdata.book(symbol)
    except marketdata.DataError as error:
        issues.append(f"quote: {error}")
    payload["dataValidation"] = {"valid": not issues, "issues": issues}
    if issues:
        raise TickSkipped("DATA_INVALID", "; ".join(issues[:3]))
    return payload


def resolve_stop(payload, strategy, direction, entry):
    """
    The stop, from the strategy's own stopRule.

    Each strategy names where its invalidation sits, and they are not interchangeable. Applying one
    swing-based stop to all nine put breakout stops 765-1023 points out against a 242-258 point ATR
    budget, and the desk rejected five otherwise-valid setups on stop_within_atr_budget. A breakout
    is invalidated by price re-entering the level it broke, not by a swing far behind it.

    Returns (stop, anchor_description) or (None, reason) when the anchor is unavailable.
    """
    rule = strategy.get("stopRule")
    if not rule:
        return None, "no stopRule on this strategy"
    node = payload["timeframes"].get(rule["tf"])
    if node is None:
        return None, f"{rule['tf']} timeframe missing"
    atr = dsl.field(node, "volatility.atr14") or 0
    long_side = direction == dsl.LONG
    path = rule["long"] if long_side else rule["short"]

    if path == "nearest_zone":
        levels = dsl.field(node, "levels") or {}
        side = "support" if long_side else "resistance"
        candidates = [z["level"] for z in (levels.get(side) or [])
                      if isinstance(z.get("level"), (int, float))]
        # The zone the trade is leaning on is the nearest one on that side.
        anchor = min(candidates, key=lambda level: abs(entry - level)) if candidates else None
        described = f"nearest {side} zone"
    else:
        anchor = dsl.field(node, path)
        described = path

    if not isinstance(anchor, (int, float)):
        return None, f"anchor {described} unavailable on {rule['tf']}"

    cushion = atr * float(rule.get("cushion", 0.0))
    stop = anchor - cushion if long_side else anchor + cushion
    # A stop on the wrong side of entry is not a stop.
    if (long_side and stop >= entry) or (not long_side and stop <= entry):
        return None, f"{described} sits on the wrong side of entry"
    return round(stop, 2), f"{rule['tf']} {described} +/- {rule.get('cushion', 0)}xATR"


def plan_prices(payload, strategy, result, limits):
    """
    Entry, stop and target.

    Entry is the current quote on the side being taken. The stop comes from the strategy's own rule.
    The target is provisional at the desk's minimum reward-to-risk; widen_target_for_costs then
    re-derives it from the desk's own cost figures.
    """
    quote = payload["quote"]
    long_side = result["direction"] == dsl.LONG
    entry = quote["ask"] if long_side else quote["bid"]

    stop, described = resolve_stop(payload, strategy, result["direction"], entry)
    if stop is None:
        # Falls back to an ATR stop rather than silently using another strategy's rule.
        setup = payload["timeframes"]["15m"]
        atr = dsl.field(setup, "volatility.atr14") or 0
        if atr <= 0:
            raise TickSkipped("PLAN_INVALID", f"no usable stop: {described}")
        stop = entry - atr if long_side else entry + atr
        described = f"fallback 1xATR ({described})"

    risk = abs(entry - stop)
    if risk <= 0:
        raise TickSkipped("PLAN_INVALID", "stop and entry resolved to the same price")
    reward_multiple = float(limits.get("minRewardToRisk") or 2.0)
    target = entry + risk * reward_multiple if long_side else entry - risk * reward_multiple
    setup_atr = dsl.field(payload["timeframes"]["15m"], "volatility.atr14") or 0
    return round(entry, 2), round(stop, 2), round(target, 2), setup_atr, described


def widen_target_for_costs(entry, stop, direction, preview, limits):
    """
    Re-derives the target so the ratio clears the minimum *after* costs.

    A target set at exactly minRewardToRisk is a gross figure. The desk computes

        net = (reward_per_unit * qty - costs) / (risk_per_unit * qty + costs)

    with costs in both terms, so the net ratio always lands below the limit and every trade is
    rejected on reward_to_risk_after_costs. Solving that for the reward needed to reach m:

        reward_per_unit >= m * risk_per_unit + costs * (m + 1) / qty

    Costs and quantity come from the desk's own preview, so this cannot drift from the model that
    will judge the order. Widening the target does not change the size, which is derived from risk,
    so one pass is enough.
    """
    risk_per_unit = abs(entry - stop)
    quantity = preview.get("quantity") or 0
    if risk_per_unit <= 0 or quantity <= 0:
        return None
    costs = (preview.get("estimatedFees") or 0) + (preview.get("estimatedSlippageCost") or 0)
    minimum = float(limits.get("minRewardToRisk") or 2.0)
    # A little past the boundary, so rounding to the tick cannot land just under it.
    required = minimum * risk_per_unit + costs * (minimum + 1) / quantity
    required *= 1.02
    return round(entry + required, 2) if direction == dsl.LONG else round(entry - required, 2)


def tick(symbol, dry_run, ledger, fixture=None):
    started = time.time()
    catalogue = load_catalogue()
    limits = json.loads(DESK_CONFIG_PATH.read_text())
    desk = broker.Desk(limits)

    if fixture:
        # Replay: the same code path, a recorded payload. Used to exercise the order path and,
        # later, to replay a proposed catalogue change over history.
        payload = json.loads(Path(fixture).read_text())
        payload.setdefault("quote", {})
    else:
        payload = fetch_analysis(symbol, list(marketdata.INTERVALS.keys()))

    market_regime, evidence = regime_module.classify(payload, limits)
    daily_bias = regime_module.bias(payload, "1d")
    hourly_bias = regime_module.bias(payload, "1h")

    evaluation = dsl.evaluate_catalogue(
        catalogue, payload, market_regime,
        limits.get("maxSpreadPercent"),
        allow_long=limits.get("allowLong", True),
        allow_short=limits.get("allowShort", False))

    decision, reason_code, selection, preview, order = "NO_TRADE", "", None, None, None

    if not evaluation["applicable"]:
        reason_code = "NO_APPLICABLE_STRATEGY"
    else:
        filters = marketdata.symbol_filters(symbol)
        scored = []
        by_name = {s["name"]: s for s in catalogue["strategies"]}
        for result in evaluation["applicable"]:
            strategy = by_name[result["name"]]
            entry, stop, target, atr, stop_basis = plan_prices(
                payload, strategy, result, limits)
            probe = {"symbol": symbol,
                     "direction": "BUY" if result["direction"] == dsl.LONG else "SELL",
                     "entryPrice": entry, "stopPrice": stop, "targetPrice": target,
                     "atr14": atr, "strategy": result["name"],
                     "strategyScore": 0, "runnerUpScore": 0, "idempotencyKey": "probe"}
            # Priced first, because rewardToRiskAfterCosts is one of the scoring components and
            # only the desk can compute it.
            probe_preview = desk.evaluate(probe, filters, payload["quote"])
            widened = widen_target_for_costs(
                entry, stop, result["direction"], probe_preview, limits)
            if widened:
                target = widened
                probe["targetPrice"] = target
                probe_preview = desk.evaluate(probe, filters, payload["quote"])
            total, parts, penalties = regime_module.score(
                result, payload, market_regime,
                probe_preview.get("rewardToRiskAfterCosts") or 0, limits)
            scored.append({**result, "score": total, "components": parts,
                           "penalties": penalties, "entry": entry, "stop": stop,
                           "target": target, "atr": atr, "stopBasis": stop_basis})

        scored.sort(key=lambda s: s["score"], reverse=True)
        best = scored[0]
        runner_up = scored[1]["score"] if len(scored) > 1 else 0
        best["runnerUp"] = runner_up
        selection = best

        minimum = limits.get("minStrategyScore", 75)
        margin = limits.get("minScoreMarginOverRunnerUp", 8)
        bonus = next((s.get("scoreBonusRequired", 0) for s in catalogue["strategies"]
                      if s["name"] == best["name"]), 0)

        if best["score"] < minimum + bonus:
            reason_code = "SCORE_BELOW_THRESHOLD"
        elif best["score"] - runner_up < margin:
            reason_code = "SCORE_MARGIN_TOO_SMALL"
        else:
            setup_timestamp = dsl.field(payload, "timeframes.15m.lastClosedAtMs")
            direction = "BUY" if best["direction"] == dsl.LONG else "SELL"
            request = {
                "symbol": symbol, "direction": direction,
                "entryPrice": best["entry"], "stopPrice": best["stop"],
                "targetPrice": best["target"], "atr14": best["atr"],
                "strategy": best["name"], "strategyScore": best["score"],
                "runnerUpScore": runner_up,
                # Identical across the three ticks inside one 15m candle, so a repeat cannot
                # double the position.
                "idempotencyKey": f"{symbol}|{best['name']}|{direction}|{setup_timestamp}",
            }
            preview = desk.evaluate(request, filters, payload["quote"])
            if not preview["passed"]:
                decision, reason_code = "NO_TRADE", "RISK_CHECKS_FAILED"
            elif dry_run:
                decision, reason_code = "WOULD_TRADE", "DRY_RUN"
                order = {"idempotencyKey": request["idempotencyKey"], "status": "NOT_SUBMITTED"}
            else:
                submission = desk.submit(request, filters, payload["quote"])
                order = submission["order"]
                decision = "ORDER_SUBMITTED" if order.get("brokerOrderId") else "NO_TRADE"
                reason_code = "SUBMITTED" if order.get("brokerOrderId") else "ORDER_REJECTED"

    row = ledger_module.build_row(
        tick_at=ledger_module.utc_now_iso(), symbol=symbol, payload=payload,
        catalogue_version=catalogue.get("version", 0), regime=market_regime,
        daily_bias=daily_bias, hourly_bias=hourly_bias, decision=decision,
        reason_code=reason_code, selection=selection, evaluation=evaluation,
        preview=preview, order=order,
        duration_ms=int((time.time() - started) * 1000))
    written = ledger.append(row)

    return {"decision": decision, "reason": reason_code, "regime": market_regime,
            "evidence": evidence,
            "applicable": [f"{r['name']}:{r['direction']}" for r in evaluation["applicable"]],
            "nearMisses": len(evaluation["nearMisses"]),
            "skipped": [f"{r['name']}:{r['direction']}" for r in evaluation["skipped"]],
            "selected": (selection or {}).get("name"),
            "score": (selection or {}).get("score"),
            "order": (order or {}).get("brokerOrderId"),
            "ledger": written, "durationMs": row["duration_ms"]}


def main():
    parser = argparse.ArgumentParser(description="One deterministic trading desk tick")
    parser.add_argument("--symbol", default=os.environ.get("SYMBOL", "BTCUSDT"))
    parser.add_argument("--dry-run", action="store_true",
                        help="evaluate, price and risk-check, but never submit")
    parser.add_argument("--ledger", default=None,
                        help="jsonl path; overrides LEDGER_PATH. Ignored when LEDGER=sheets")
    parser.add_argument("--no-lock", action="store_true", help="skip the single-instance lock")
    parser.add_argument("--fixture", default=None,
                        help="replay a recorded analysis payload instead of fetching live data")
    arguments = parser.parse_args()

    ledger = (ledger_module.JsonlLedger(arguments.ledger) if arguments.ledger
              else ledger_module.from_env())
    if isinstance(ledger, ledger_module.SheetsLedger):
        ledger.ensure_header()

    locked = False
    try:
        if not arguments.no_lock:
            acquire_lock()
            locked = True
        outcome = tick(arguments.symbol, arguments.dry_run, ledger, arguments.fixture)
        print(json.dumps(outcome, indent=2, default=str))
        return 0
    except TickSkipped as skipped:
        print(json.dumps({"decision": "SKIPPED", "reason": skipped.reason_code,
                          "detail": skipped.detail}, indent=2))
        return 0
    finally:
        if locked:
            release_lock()


if __name__ == "__main__":
    sys.exit(main())
