#!/usr/bin/env python3
"""
Backfills what happened after each tick.

The ledger records what the desk decided. This records what the market then did, which is the only
thing that turns "why did it not trade" into "should it have traded". Without it, a suggestion to
loosen a threshold is an opinion; with it, it is a claim you can check.

Runs hourly and is idempotent: a tick is processed once, after all its horizons have elapsed, and
outcomes are written append-only keyed by (symbol, tick_at). Nothing updates an existing row, so the
same file layout works for a Google Sheet tab.

    executor/outcomes.py --ledger ~/desk/ticks.jsonl --out ~/desk/outcomes.jsonl
    executor/outcomes.py --out ~/desk/outcomes.jsonl --report
"""

import argparse
import collections
import json
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import marketdata   # noqa: E402

HORIZONS = (("15m", 15 * 60_000), ("1h", 60 * 60_000), ("4h", 4 * 60 * 60_000))
LONGEST_MS = max(ms for _, ms in HORIZONS)
CANDLE_INTERVAL = "5m"

COLUMNS = [
    "symbol", "tick_at", "computed_at",
    "hypothesis_direction", "hypothesis_source",
    "px_reference", "px_plus_15m", "px_plus_1h", "px_plus_4h",
    "move_15m_atr", "move_1h_atr", "move_4h_atr",
    "mfe_atr", "mae_atr",
    "plan_outcome", "r_multiple_if_taken", "minutes_to_outcome",
    "near_miss_moves",
]


def key_of(row):
    return f"{row['symbol']}|{row['tick_at']}"


def read_jsonl(path):
    target = Path(path).expanduser()
    if not target.exists():
        return []
    with target.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def append_jsonl(path, row):
    target = Path(path).expanduser()
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps({column: row[column] for column in COLUMNS}) + "\n")


def epoch_ms(iso):
    return int(datetime.fromisoformat(iso).timestamp() * 1000)


def _float(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def parse_near_misses(text):
    """`strategy:DIRECTION:condition=actual | ...` back into tuples."""
    out = []
    for item in filter(None, (text or "").split(" | ")):
        try:
            head, actual = item.rsplit("=", 1)
            name, direction, condition = head.split(":")
        except ValueError:
            continue
        out.append((name, direction, condition, actual))
    return out


def hypothesis(row):
    """
    The direction whose forward move is worth measuring, and where it came from.

    A tick that selected a strategy has one. A tick that only nearly matched still has a testable
    hypothesis - the direction that strategy would have taken - and that is exactly the case the
    review needs to judge. A tick with neither has no hypothesis, only a raw price move.
    """
    if row.get("direction"):
        return row["direction"], f"selected:{row.get('selected_strategy')}"
    misses = parse_near_misses(row.get("near_misses"))
    if misses:
        counts = collections.Counter(direction for _, direction, _, _ in misses)
        direction, _ = counts.most_common(1)[0]
        return direction, "near_miss"
    skipped = (row.get("skipped_directions") or "").strip()
    if skipped:
        return skipped.split(":")[-1].split(",")[0].strip(), "skipped_direction"
    return "", "none"


def price_at(candles, instant_ms):
    """Close of the candle covering an instant, or the last one before it."""
    covering = [c for c in candles if c["openTime"] <= instant_ms <= c["closeTime"]]
    if covering:
        return covering[0]["close"]
    earlier = [c for c in candles if c["closeTime"] <= instant_ms]
    return earlier[-1]["close"] if earlier else None


def walk_plan(candles, direction, entry, stop, target):
    """
    Whether the stop or the target came first.

    Where a single candle's range spans both, the stop is assumed to have been hit first. That is a
    real methodological choice and it biases the R figures downward: 5m bars cannot say which came
    first, and an optimistic assumption would quietly inflate every result.
    """
    long_side = direction == "LONG"
    risk = abs(entry - stop)
    if risk <= 0:
        return "NOT_PRICED", None, None
    for candle in candles:
        hit_stop = candle["low"] <= stop if long_side else candle["high"] >= stop
        hit_target = candle["high"] >= target if long_side else candle["low"] <= target
        if hit_stop:
            return "STOP_HIT", -1.0, candle
        if hit_target:
            return "TARGET_HIT", round(abs(target - entry) / risk, 3), candle
    if not candles:
        return "NOT_PRICED", None, None
    final = candles[-1]["close"]
    unrealised = (final - entry) if long_side else (entry - final)
    return "OPEN", round(unrealised / risk, 3), None


def backfill_one(row, candles):
    direction, source = hypothesis(row)
    tick_ms = epoch_ms(row["tick_at"])
    atr = _float(row.get("m15_atr")) or 0

    long_side = direction != "SHORT"
    reference = _float(row.get("entry_price"))
    if reference is None:
        reference = _float(row.get("quote_ask")) if long_side else _float(row.get("quote_bid"))
    if reference is None and candles:
        reference = candles[0]["open"]

    prices = {label: price_at(candles, tick_ms + ms) for label, ms in HORIZONS}

    def signed_move(price):
        if price is None or reference is None or atr <= 0:
            return ""
        raw = (price - reference) if long_side else (reference - price)
        return round(raw / atr, 3)

    highs = [c["high"] for c in candles] or [reference or 0]
    lows = [c["low"] for c in candles] or [reference or 0]
    if reference is not None and atr > 0:
        favourable = (max(highs) - reference) if long_side else (reference - min(lows))
        adverse = (reference - min(lows)) if long_side else (max(highs) - reference)
        mfe, mae = round(favourable / atr, 3), round(adverse / atr, 3)
    else:
        mfe = mae = ""

    entry = _float(row.get("entry_price"))
    stop = _float(row.get("stop_price"))
    target = _float(row.get("target_price"))
    if None not in (entry, stop, target) and direction:
        outcome, r_multiple, at_candle = walk_plan(candles, direction, entry, stop, target)
        minutes = round((at_candle["closeTime"] - tick_ms) / 60_000) if at_candle else ""
    else:
        outcome, r_multiple, minutes = "NOT_PRICED", "", ""

    # Per near-miss forward move: the opportunity cost of the condition that blocked it.
    per_miss = []
    for name, miss_direction, condition, _actual in parse_near_misses(row.get("near_misses")):
        price = prices["1h"]
        if price is None or reference is None or atr <= 0:
            continue
        raw = (price - reference) if miss_direction == "LONG" else (reference - price)
        per_miss.append(f"{name}:{miss_direction}:{condition}={round(raw / atr, 3)}")

    return {
        "symbol": row["symbol"],
        "tick_at": row["tick_at"],
        "computed_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "hypothesis_direction": direction,
        "hypothesis_source": source,
        "px_reference": round(reference, 2) if reference is not None else "",
        "px_plus_15m": prices["15m"] if prices["15m"] is not None else "",
        "px_plus_1h": prices["1h"] if prices["1h"] is not None else "",
        "px_plus_4h": prices["4h"] if prices["4h"] is not None else "",
        "move_15m_atr": signed_move(prices["15m"]),
        "move_1h_atr": signed_move(prices["1h"]),
        "move_4h_atr": signed_move(prices["4h"]),
        "mfe_atr": mfe,
        "mae_atr": mae,
        "plan_outcome": outcome,
        "r_multiple_if_taken": r_multiple if r_multiple is not None else "",
        "minutes_to_outcome": minutes,
        "near_miss_moves": " | ".join(per_miss),
    }


def backfill(ledger_path, out_path, limit=None):
    ticks = read_jsonl(ledger_path)
    done = {key_of(row) for row in read_jsonl(out_path)}
    now_ms = int(time.time() * 1000)

    pending = [row for row in ticks
               if key_of(row) not in done
               and epoch_ms(row["tick_at"]) + LONGEST_MS <= now_ms]
    if limit:
        pending = pending[:limit]

    print(f"ticks {len(ticks)} | already backfilled {len(done)} | ready now {len(pending)}")
    waiting = len(ticks) - len(done) - len(pending)
    if waiting > 0:
        print(f"  {waiting} tick(s) still inside the 4h horizon; they will be picked up later")

    written = 0
    for row in pending:
        tick_ms = epoch_ms(row["tick_at"])
        try:
            candles = marketdata.candles_between(
                row["symbol"], CANDLE_INTERVAL, tick_ms, tick_ms + LONGEST_MS)
        except marketdata.DataError as error:
            print(f"  {row['tick_at']}: {error}", file=sys.stderr)
            continue
        if not candles:
            print(f"  {row['tick_at']}: no candles returned", file=sys.stderr)
            continue
        append_jsonl(out_path, backfill_one(row, candles))
        written += 1
    print(f"wrote {written} outcome row(s) to {out_path}")
    return written


def report(out_path):
    """
    What the outcomes say, aggregated.

    The near-miss section is the one that matters: it answers whether relaxing a condition would
    have caught a move or only caught noise.
    """
    rows = read_jsonl(out_path)
    if not rows:
        print("no outcomes yet")
        return 1
    print(f"outcomes recorded {len(rows)}")

    taken = [r for r in rows if r["plan_outcome"] in ("TARGET_HIT", "STOP_HIT", "OPEN")]
    if taken:
        print(f"\npriced plans {len(taken)}")
        for outcome, count in collections.Counter(r["plan_outcome"] for r in taken).most_common():
            print(f"  {count:4}  {outcome}")
        rs = [r["r_multiple_if_taken"] for r in taken
              if isinstance(r["r_multiple_if_taken"], (int, float))]
        if rs:
            print(f"  R multiple: median {statistics.median(rs):+.2f}  "
                  f"total {sum(rs):+.2f}  best {max(rs):+.2f}  worst {min(rs):+.2f}")

    print("\nforward move by hypothesis source, in ATR (positive = the hypothesis was right)")
    by_source = collections.defaultdict(list)
    for r in rows:
        value = r["move_1h_atr"]
        if isinstance(value, (int, float)):
            by_source[r["hypothesis_source"].split(":")[0]].append(value)
    for source, values in sorted(by_source.items(), key=lambda kv: -len(kv[1])):
        print(f"  {len(values):4}  {source:18} median {statistics.median(values):+.3f}  "
              f"mean {statistics.fmean(values):+.3f}")

    print("\nblocked conditions: would relaxing them have caught a move?")
    per_condition = collections.defaultdict(list)
    for r in rows:
        for item in filter(None, (r["near_miss_moves"] or "").split(" | ")):
            try:
                head, move = item.rsplit("=", 1)
                name, direction, condition = head.split(":")
                per_condition[(name, direction, condition)].append(float(move))
            except ValueError:
                continue
    if not per_condition:
        print("  none recorded yet")
    for (name, direction, condition), moves in sorted(
            per_condition.items(), key=lambda kv: -len(kv[1]))[:12]:
        median = statistics.median(moves)
        verdict = "worth relaxing" if median >= 0.5 else \
                  ("noise" if abs(median) < 0.5 else "correctly blocked")
        print(f"  {len(moves):4}  {name}:{direction} {condition:26} "
              f"median {median:+.3f} ATR   {verdict}")
    print("\n  (a median move at or above +0.5 ATR suggests the condition blocked a real move;")
    print("   near zero means it blocked noise; negative means it saved you a loss)")
    return 0


def main():
    parser = argparse.ArgumentParser(description="Backfill what happened after each tick")
    parser.add_argument("--ledger", default="~/desk/ticks.jsonl")
    parser.add_argument("--out", default="~/desk/outcomes.jsonl")
    parser.add_argument("--limit", type=int, default=None,
                        help="process at most this many ticks, to stay inside API rate limits")
    parser.add_argument("--report", action="store_true", help="summarise instead of backfilling")
    arguments = parser.parse_args()

    if arguments.report:
        return report(arguments.out)
    backfill(arguments.ledger, arguments.out, arguments.limit)
    return 0


if __name__ == "__main__":
    sys.exit(main())
