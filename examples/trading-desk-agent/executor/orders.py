#!/usr/bin/env python3
"""
Counts what the desk did, from the ledger.

Separates orders actually submitted from ones a dry run only planned, because conflating the two is
the easiest way to believe a paper desk has been trading when it has not.

    executor/orders.py ~/desk/ticks.jsonl
"""

import collections
import json
import sys
from pathlib import Path

SUBMITTED = "ORDER_SUBMITTED"
WOULD = "WOULD_TRADE"


def load(paths):
    rows = []
    for path in paths:
        target = Path(path).expanduser()
        if not target.exists():
            print(f"no ledger at {target}", file=sys.stderr)
            continue
        with target.open(encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
    return rows


def main():
    paths = sys.argv[1:] or ["~/desk/ticks.jsonl"]
    rows = load(paths)
    if not rows:
        print("no ticks recorded yet")
        return 1

    submitted = [r for r in rows if r["decision"] == SUBMITTED]
    would = [r for r in rows if r["decision"] == WOULD]
    filled = [r for r in submitted if r.get("order_status") == "FILLED"]
    working = [r for r in submitted if r.get("order_status") == "WORKING"]
    rejected = [r for r in rows if r.get("reason_code") == "ORDER_REJECTED"]

    print(f"ticks recorded        {len(rows)}")
    print(f"  first              {rows[0]['tick_at']}")
    print(f"  last               {rows[-1]['tick_at']}")
    print()
    print(f"ORDERS PLACED         {len(submitted)}")
    print(f"  filled             {len(filled)}")
    print(f"  working            {len(working)}")
    print(f"  rejected           {len(rejected)}")
    print(f"would have placed     {len(would)}   (dry run, nothing submitted)")
    print()

    if submitted or would:
        print("by strategy and direction")
        pairs = collections.Counter(
            (r["selected_strategy"], r["direction"]) for r in submitted + would)
        for (name, direction), count in pairs.most_common():
            print(f"  {count:4}  {name}:{direction}")
        print()

    print("why the rest did not trade")
    for reason, count in collections.Counter(
            r["reason_code"] for r in rows
            if r["decision"] not in (SUBMITTED, WOULD)).most_common():
        print(f"  {count:4}  {reason}")

    skipped = [r for r in rows if r.get("skipped_directions")]
    if skipped:
        print()
        print(f"ticks where a matched strategy was skipped for its direction: {len(skipped)}")
        print("  (allowShort is false; this is the opportunity cost of that setting)")

    if submitted:
        print()
        print("orders")
        for r in submitted:
            print(f"  {r['tick_at']}  {r['direction']:5} {r['selected_strategy']:28} "
                  f"entry {r['entry_price']}  qty {r['quantity']}  "
                  f"R:R {r['reward_to_risk']}  {r['broker_order_id']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
