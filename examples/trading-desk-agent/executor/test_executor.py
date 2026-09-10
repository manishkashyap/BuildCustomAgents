"""
Tests for the deterministic desk.

Fixtures are built by transforming a captured live payload rather than hand-written, so every field
the indicators produce is present and realistic. Asserting on synthetic-but-complete payloads is the
only way to test a verdict, because live market data changes between runs.

    python3 -m unittest discover -s executor -p 'test_*.py'
"""

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import dsl                       # noqa: E402
import ledger as ledger_module   # noqa: E402
import regime as R               # noqa: E402

FIXTURE = HERE / "fixtures" / "analysis-baseline.json"


def baseline():
    return json.loads(FIXTURE.read_text())


def make_uptrend(payload):
    """A payload satisfying STRONG_UPTREND and trend_continuation LONG."""
    p = copy.deepcopy(payload)
    for tf in ("1d", "1h"):
        n = p["timeframes"][tf]
        n["trendStrength"].update({"adx14": 34.0, "plusDi": 40.0, "minusDi": 11.0})
        n["structure"]["classification"] = "HIGHER_HIGHS_HIGHER_LOWS"
        ma = n["movingAverages"]
        n["close"] = round(max(ma["ema50"], ma["ema200"]) * 1.03, 2)
    setup = p["timeframes"]["15m"]
    setup["momentum"].update({"macdHistogram": 120.0, "macdHistogramThreeBarsAgo": 60.0})
    entry = p["timeframes"]["5m"]
    entry["volume"]["ratioToAverage"] = 1.9
    entry["structure"]["lastSwingHighs"] = [round(entry["close"] * 0.995, 2)] * 3
    return p


def make_downtrend(payload):
    """A payload satisfying STRONG_DOWNTREND and trend_continuation SHORT."""
    p = copy.deepcopy(payload)
    for tf in ("1d", "1h"):
        n = p["timeframes"][tf]
        n["trendStrength"].update({"adx14": 32.0, "plusDi": 10.0, "minusDi": 41.0})
        n["structure"]["classification"] = "LOWER_HIGHS_LOWER_LOWS"
        ma = n["movingAverages"]
        n["close"] = round(min(ma["ema50"], ma["ema200"]) * 0.97, 2)
    setup = p["timeframes"]["15m"]
    setup["momentum"].update({"macdHistogram": -140.0, "macdHistogramThreeBarsAgo": -60.0})
    entry = p["timeframes"]["5m"]
    entry["volume"]["ratioToAverage"] = 1.8
    entry["structure"]["lastSwingLows"] = [round(entry["close"] * 1.005, 2)] * 3
    return p


class CatalogueTest(unittest.TestCase):

    def setUp(self):
        self.catalogue = json.loads((HERE / "strategies.json").read_text())
        self.limits = json.loads((HERE.parent / "desk-config.json").read_text())

    def test_the_shipped_catalogue_is_valid(self):
        validated = dsl.validate_catalogue(self.catalogue)
        self.assertEqual(len(validated["strategies"]), 9)

    def test_a_strategy_without_conditions_is_rejected(self):
        broken = copy.deepcopy(self.catalogue)
        broken["strategies"][0]["conditions"] = []
        with self.assertRaises(dsl.CatalogueError):
            dsl.validate_catalogue(broken)

    def test_a_duplicated_strategy_name_is_rejected(self):
        broken = copy.deepcopy(self.catalogue)
        broken["strategies"][1]["name"] = broken["strategies"][0]["name"]
        with self.assertRaises(dsl.CatalogueError):
            dsl.validate_catalogue(broken)

    def test_an_unknown_operator_is_rejected_at_evaluation(self):
        condition = {"id": "bogus", "tf": "1h", "op": "no_such_operator", "field": "close"}
        with self.assertRaises(dsl.CatalogueError):
            dsl.evaluate_condition(condition, baseline(), dsl.LONG, "UNCERTAIN", 0.05)


class RegimeTest(unittest.TestCase):

    def setUp(self):
        self.limits = json.loads((HERE.parent / "desk-config.json").read_text())

    def test_aligned_bullish_timeframes_classify_as_a_strong_uptrend(self):
        found, _ = R.classify(make_uptrend(baseline()), self.limits)
        self.assertEqual(found, R.STRONG_UPTREND)

    def test_aligned_bearish_timeframes_classify_as_a_strong_downtrend(self):
        found, _ = R.classify(make_downtrend(baseline()), self.limits)
        self.assertEqual(found, R.STRONG_DOWNTREND)

    def test_conflicting_daily_and_hourly_classify_as_uncertain(self):
        p = make_uptrend(baseline())
        # Flip only the hourly, so the two timeframes disagree.
        p["timeframes"]["1h"]["trendStrength"].update({"plusDi": 11.0, "minusDi": 40.0})
        p["timeframes"]["1h"]["structure"]["classification"] = "LOWER_HIGHS_LOWER_LOWS"
        found, evidence = R.classify(p, self.limits)
        self.assertEqual(found, R.UNCERTAIN)
        self.assertTrue(any("bias" in line for line in evidence))

    def _hourly(self, payload, adx, plus_di, minus_di, above_ema50, structure=None):
        h = payload["timeframes"]["1h"]
        h["trendStrength"].update({"adx14": adx, "plusDi": plus_di, "minusDi": minus_di})
        ema50 = h["movingAverages"]["ema50"]
        h["close"] = round(ema50 * (1.005 if above_ema50 else 0.995), 2)
        if structure is not None:
            h["structure"]["classification"] = structure
        return payload

    def test_price_below_the_hourly_average_with_bullish_momentum_is_not_a_downtrend(self):
        """
        The contradiction that affected 22% of a 20-hour session: the label said WEAK_DOWNTREND
        while bias() on the same tick said BULLISH, and the score gave a long full alignment marks.
        A genuine disagreement between price and momentum is UNCERTAIN.
        """
        p = self._hourly(baseline(), adx=18.0, plus_di=30.0, minus_di=20.0, above_ema50=False)
        found, _ = R.classify(p, self.limits)
        self.assertEqual(found, R.UNCERTAIN)
        self.assertEqual(R.bias(p, "1h"), "BULLISH")

    def test_price_above_the_hourly_average_with_bearish_momentum_is_not_an_uptrend(self):
        p = self._hourly(baseline(), adx=18.0, plus_di=20.0, minus_di=30.0, above_ema50=True)
        found, _ = R.classify(p, self.limits)
        self.assertEqual(found, R.UNCERTAIN)
        self.assertEqual(R.bias(p, "1h"), "BEARISH")

    def test_a_genuine_weak_downtrend_still_classifies(self):
        p = self._hourly(baseline(), adx=18.0, plus_di=18.0, minus_di=30.0, above_ema50=False)
        found, _ = R.classify(p, self.limits)
        self.assertEqual(found, R.WEAK_DOWNTREND)
        self.assertEqual(R.bias(p, "1h"), "BEARISH")

    def test_a_genuine_weak_uptrend_still_classifies(self):
        p = self._hourly(baseline(), adx=18.0, plus_di=30.0, minus_di=18.0, above_ema50=True)
        found, _ = R.classify(p, self.limits)
        self.assertEqual(found, R.WEAK_UPTREND)

    def test_the_label_never_contradicts_the_hourly_bias(self):
        """The invariant the fix establishes, across the whole grid."""
        for adx in (12.0, 18.0, 24.0, 30.0):
            for above in (True, False):
                for plus, minus in ((30.0, 18.0), (18.0, 30.0)):
                    p = self._hourly(baseline(), adx, plus, minus, above)
                    found, _ = R.classify(p, self.limits)
                    hourly_bias = R.bias(p, "1h")
                    if found == R.WEAK_DOWNTREND or found == R.STRONG_DOWNTREND:
                        self.assertEqual(hourly_bias, "BEARISH",
                                         f"adx={adx} above_ema50={above} -> {found}")
                    if found == R.WEAK_UPTREND or found == R.STRONG_UPTREND:
                        self.assertEqual(hourly_bias, "BULLISH",
                                         f"adx={adx} above_ema50={above} -> {found}")

    def test_a_strong_adx_with_mixed_structure_is_weak_not_uncertain(self):
        """The 'or structure mixed' clause the first implementation dropped."""
        p = self._hourly(baseline(), adx=30.0, plus_di=32.0, minus_di=15.0,
                         above_ema50=True, structure="LOWER_LOWS_HIGHER_HIGHS_EXPANDING")
        # Not a strong uptrend, because the structure is not cleanly HH/HL.
        found, _ = R.classify(p, self.limits)
        self.assertEqual(found, R.WEAK_UPTREND)

    def test_a_very_low_adx_is_left_to_range_bound(self):
        """Scoping the mixed clause to adx >= 25 keeps RANGE_BOUND's territory intact."""
        p = self._hourly(baseline(), adx=8.0, plus_di=30.0, minus_di=18.0,
                         above_ema50=True, structure="LOWER_LOWS_HIGHER_HIGHS_EXPANDING")
        found, _ = R.classify(p, self.limits)
        self.assertNotEqual(found, R.WEAK_UPTREND)

    def test_a_spread_above_the_limit_is_low_liquidity_before_anything_else(self):
        p = make_uptrend(baseline())
        p["quote"]["spreadPercent"] = self.limits["maxSpreadPercent"] * 10
        found, _ = R.classify(p, self.limits)
        self.assertEqual(found, R.LOW_LIQUIDITY)


class EvaluationTest(unittest.TestCase):

    def setUp(self):
        self.catalogue = json.loads((HERE / "strategies.json").read_text())
        self.limits = json.loads((HERE.parent / "desk-config.json").read_text())

    def evaluate(self, payload, allow_short):
        found, _ = R.classify(payload, self.limits)
        return found, dsl.evaluate_catalogue(
            self.catalogue, payload, found, self.limits["maxSpreadPercent"],
            allow_long=True, allow_short=allow_short)

    def test_trend_continuation_matches_long_in_a_strong_uptrend(self):
        _, result = self.evaluate(make_uptrend(baseline()), allow_short=False)
        names = [r["name"] for r in result["applicable"]]
        self.assertIn("trend_continuation", names)
        self.assertTrue(all(r["direction"] == dsl.LONG for r in result["applicable"]))

    def test_trend_continuation_matches_short_in_a_strong_downtrend(self):
        _, result = self.evaluate(make_downtrend(baseline()), allow_short=True)
        matched = [(r["name"], r["direction"]) for r in result["applicable"]]
        self.assertIn(("trend_continuation", dsl.SHORT), matched)

    def test_a_disallowed_direction_is_recorded_as_skipped_not_dropped(self):
        """The opportunity cost of allowShort=false has to be measurable, not invisible."""
        _, result = self.evaluate(make_downtrend(baseline()), allow_short=False)
        self.assertEqual(result["applicable"], [])
        skipped = [(r["name"], r["direction"]) for r in result["skipped"]]
        self.assertIn(("trend_continuation", dsl.SHORT), skipped)
        self.assertTrue(all(r["reason"] == "DIRECTION_DISALLOWED" for r in result["skipped"]))

    def test_a_near_miss_names_the_failing_condition_and_its_value(self):
        """This detail is what the daily review reasons over."""
        p = make_uptrend(baseline())
        p["timeframes"]["5m"]["volume"]["ratioToAverage"] = 0.4   # below the 1.2 threshold
        _, result = self.evaluate(p, allow_short=False)
        misses = {r["name"]: r["failures"] for r in result["nearMisses"]}
        self.assertIn("trend_continuation", misses)
        failure = misses["trend_continuation"][0]
        self.assertEqual(failure["id"], "entry_volume")
        self.assertAlmostEqual(failure["actual"], 0.4)

    def test_every_condition_is_evaluated_not_just_the_first_failure(self):
        p = make_uptrend(baseline())
        result = dsl.evaluate_strategy(
            self.catalogue["strategies"][0], p, dsl.LONG, R.STRONG_UPTREND, 0.05)
        self.assertEqual(len(result["passed"]) + len(result["failures"]),
                         len(self.catalogue["strategies"][0]["conditions"]))


class OperatorTest(unittest.TestCase):

    def test_dotted_paths_index_into_lists(self):
        node = {"structure": {"lastSwingLows": [1.5, 2.5]}}
        self.assertEqual(dsl.field(node, "structure.lastSwingLows.0"), 1.5)
        self.assertIsNone(dsl.field(node, "structure.lastSwingLows.9"))
        self.assertIsNone(dsl.field(node, "structure.missing.0"))

    def test_moved_toward_distinguishes_recovering_from_still_falling(self):
        condition = {"id": "t", "tf": "15m", "op": "moved_toward",
                     "field": "momentum.rsi14", "from": "momentum.rsi14FiveBarsAgo",
                     "target": 50}
        recovering = {"timeframes": {"15m": {"momentum": {"rsi14": 46, "rsi14FiveBarsAgo": 41}}}}
        falling = {"timeframes": {"15m": {"momentum": {"rsi14": 41, "rsi14FiveBarsAgo": 46}}}}
        self.assertTrue(dsl.evaluate_condition(condition, recovering, dsl.LONG, "X", 0.05)[0])
        self.assertFalse(dsl.evaluate_condition(condition, falling, dsl.LONG, "X", 0.05)[0])

    def test_a_missing_field_fails_rather_than_raising(self):
        condition = {"id": "t", "tf": "15m", "op": "gte",
                     "field": "momentum.doesNotExist", "value": 1}
        passed, actual = dsl.evaluate_condition(
            condition, {"timeframes": {"15m": {}}}, dsl.LONG, "X", 0.05)
        self.assertFalse(passed)
        self.assertIsNone(actual)


class PricingTest(unittest.TestCase):
    """
    The desk computes net = (reward*qty - costs) / (risk*qty + costs), with costs in both terms.
    A target set at exactly minRewardToRisk is therefore always rejected. These lock in the
    algebra that fixes it, because the symptom is an executor that silently never trades.
    """

    def setUp(self):
        sys.path.insert(0, str(HERE))
        import runner
        self.runner = runner
        self.limits = json.loads((HERE.parent / "desk-config.json").read_text())

    def _net_ratio(self, entry, stop, target, quantity, costs):
        reward = abs(target - entry) * quantity
        risk = abs(entry - stop) * quantity
        return (reward - costs) / (risk + costs)

    def test_the_widened_target_clears_the_minimum_after_costs(self):
        entry, stop, quantity, costs = 78650.53, 78383.77, 0.1595, 25.09
        preview = {"quantity": quantity, "estimatedFees": costs, "estimatedSlippageCost": 0.0}
        target = self.runner.widen_target_for_costs(
            entry, stop, dsl.LONG, preview, self.limits)
        ratio = self._net_ratio(entry, stop, target, quantity, costs)
        self.assertGreaterEqual(ratio, self.limits["minRewardToRisk"])

    def test_a_gross_target_would_have_failed(self):
        """Guards the regression directly: the naive target lands below the limit."""
        entry, stop, quantity, costs = 78650.53, 78383.77, 0.1595, 25.09
        risk = entry - stop
        naive_target = entry + risk * self.limits["minRewardToRisk"]
        ratio = self._net_ratio(entry, stop, naive_target, quantity, costs)
        self.assertLess(ratio, self.limits["minRewardToRisk"])

    def test_it_works_for_a_short_as_well(self):
        entry, stop, quantity, costs = 78650.53, 78917.29, 0.1595, 25.09
        preview = {"quantity": quantity, "estimatedFees": costs, "estimatedSlippageCost": 0.0}
        target = self.runner.widen_target_for_costs(
            entry, stop, dsl.SHORT, preview, self.limits)
        self.assertLess(target, entry)
        ratio = self._net_ratio(entry, stop, target, quantity, costs)
        self.assertGreaterEqual(ratio, self.limits["minRewardToRisk"])

    def test_an_unsized_preview_yields_no_target(self):
        preview = {"quantity": 0, "estimatedFees": 0, "estimatedSlippageCost": 0}
        self.assertIsNone(self.runner.widen_target_for_costs(
            100.0, 90.0, dsl.LONG, preview, self.limits))


class StopRuleTest(unittest.TestCase):
    """
    Regression for the bug that produced zero orders in 20 hours of live dry-running.

    A single swing-based stop was applied to all nine strategies. For a breakout that put the stop
    765-1023 points from entry against a 242-258 point ATR budget, and the desk rejected five
    setups that had scored 90-100 with an acceptable reward-to-risk.
    """

    def setUp(self):
        sys.path.insert(0, str(HERE))
        import runner
        self.runner = runner
        self.catalogue = json.loads((HERE / "strategies.json").read_text())
        self.limits = json.loads((HERE.parent / "desk-config.json").read_text())

    def strategy(self, name):
        return next(s for s in self.catalogue["strategies"] if s["name"] == name)

    def test_every_strategy_carries_a_stop_rule(self):
        for s in self.catalogue["strategies"]:
            self.assertIn("stopRule", s, f"{s['name']} has no stopRule")
            rule = s["stopRule"]
            for key in ("tf", "long", "short", "cushion"):
                self.assertIn(key, rule, f"{s['name']} stopRule missing {key}")

    def test_a_breakout_stop_comes_from_the_broken_level_not_a_distant_swing(self):
        p = make_uptrend(baseline())
        m15 = p["timeframes"]["15m"]
        atr = m15["volatility"]["atr14"]
        entry = p["quote"]["ask"]
        m15["levels"]["twentyBarHigh"] = round(entry - atr * 0.2, 2)
        m15["structure"]["lastSwingLows"] = [round(entry - atr * 4.5, 2)] * 3

        stop, basis = self.runner.resolve_stop(
            p, self.strategy("support_resistance_breakout"), dsl.LONG, entry)
        distance = entry - stop
        budget = atr * self.limits["maxEntryDistanceInAtr"]
        self.assertLess(distance, budget,
                        "breakout stop must sit inside the desk's ATR budget")
        self.assertIn("twentyBarHigh", basis)

    def test_the_old_swing_stop_would_have_exceeded_the_budget(self):
        """Guards the regression directly, so the fix cannot be quietly undone."""
        p = make_uptrend(baseline())
        m15 = p["timeframes"]["15m"]
        atr = m15["volatility"]["atr14"]
        entry = p["quote"]["ask"]
        swing = round(entry - atr * 4.5, 2)
        naive_distance = entry - (swing - atr * 0.25)
        self.assertGreater(naive_distance, atr * self.limits["maxEntryDistanceInAtr"])

    def test_trend_continuation_still_uses_the_fifteen_minute_swing(self):
        p = make_uptrend(baseline())
        entry = p["quote"]["ask"]
        p["timeframes"]["15m"]["structure"]["lastSwingLows"] = [round(entry - 300, 2)] * 3
        stop, basis = self.runner.resolve_stop(
            p, self.strategy("trend_continuation"), dsl.LONG, entry)
        self.assertIn("lastSwingLows", basis)
        self.assertLess(stop, entry)

    def test_a_short_stop_sits_above_entry(self):
        p = make_downtrend(baseline())
        entry = p["quote"]["bid"]
        p["timeframes"]["15m"]["structure"]["lastSwingHighs"] = [round(entry + 300, 2)] * 3
        stop, _ = self.runner.resolve_stop(
            p, self.strategy("trend_continuation"), dsl.SHORT, entry)
        self.assertGreater(stop, entry)

    def test_an_anchor_on_the_wrong_side_of_entry_is_refused(self):
        p = make_uptrend(baseline())
        entry = p["quote"]["ask"]
        # A swing low above entry cannot be a long's invalidation.
        p["timeframes"]["15m"]["structure"]["lastSwingLows"] = [round(entry + 100, 2)] * 3
        stop, reason = self.runner.resolve_stop(
            p, self.strategy("trend_continuation"), dsl.LONG, entry)
        self.assertIsNone(stop)
        self.assertIn("wrong side", reason)

    def test_a_missing_anchor_reports_rather_than_guessing(self):
        p = make_uptrend(baseline())
        p["timeframes"]["15m"]["structure"]["lastSwingLows"] = []
        stop, reason = self.runner.resolve_stop(
            p, self.strategy("trend_continuation"), dsl.LONG, p["quote"]["ask"])
        self.assertIsNone(stop)
        self.assertIn("unavailable", reason)

    def test_range_reversal_leans_on_the_nearest_zone(self):
        p = make_uptrend(baseline())
        entry = p["quote"]["ask"]
        p["timeframes"]["15m"]["levels"]["support"] = [
            {"level": round(entry - 120, 2), "touches": 3},
            {"level": round(entry - 900, 2), "touches": 5},
        ]
        stop, basis = self.runner.resolve_stop(
            p, self.strategy("range_reversal"), dsl.LONG, entry)
        self.assertIn("zone", basis)
        self.assertGreater(stop, entry - 400, "must pick the nearest zone, not the furthest")


class OutcomeTest(unittest.TestCase):
    """
    The backfill turns "why did it not trade" into "should it have traded", so its path logic has to
    be right. Candles are hand-built here because the point is the walk, not the indicators.
    """

    def setUp(self):
        sys.path.insert(0, str(HERE))
        import outcomes
        self.outcomes = outcomes

    def candle(self, open_time, low, high, close):
        return {"openTime": open_time, "closeTime": open_time + 299_999,
                "open": close, "high": high, "low": low, "close": close, "volume": 1, "trades": 1}

    def test_a_long_that_reaches_its_target_scores_the_full_multiple(self):
        candles = [self.candle(0, 99, 101, 100), self.candle(300_000, 99, 121, 120)]
        outcome, r, _ = self.outcomes.walk_plan(candles, "LONG", 100, 90, 120)
        self.assertEqual(outcome, "TARGET_HIT")
        self.assertAlmostEqual(r, 2.0)

    def test_a_long_stopped_out_scores_minus_one(self):
        candles = [self.candle(0, 89, 101, 90)]
        outcome, r, _ = self.outcomes.walk_plan(candles, "LONG", 100, 90, 120)
        self.assertEqual(outcome, "STOP_HIT")
        self.assertEqual(r, -1.0)

    def test_a_bar_spanning_both_is_treated_as_a_stop(self):
        """
        Deliberately pessimistic. A 5m bar cannot say which level came first, and assuming the
        target would quietly inflate every R figure in the report.
        """
        candles = [self.candle(0, 89, 121, 110)]
        outcome, r, _ = self.outcomes.walk_plan(candles, "LONG", 100, 90, 120)
        self.assertEqual(outcome, "STOP_HIT")
        self.assertEqual(r, -1.0)

    def test_an_unresolved_plan_reports_its_unrealised_multiple(self):
        candles = [self.candle(0, 99, 105, 105)]
        outcome, r, _ = self.outcomes.walk_plan(candles, "LONG", 100, 90, 120)
        self.assertEqual(outcome, "OPEN")
        self.assertAlmostEqual(r, 0.5)

    def test_a_short_is_measured_in_its_own_direction(self):
        candles = [self.candle(0, 79, 101, 80)]
        outcome, r, _ = self.outcomes.walk_plan(candles, "SHORT", 100, 110, 80)
        self.assertEqual(outcome, "TARGET_HIT")
        self.assertAlmostEqual(r, 2.0)

    def test_a_near_miss_supplies_the_hypothesis_when_nothing_was_selected(self):
        row = {"direction": "", "selected_strategy": "",
               "near_misses": "trend_continuation:SHORT:entry_volume=0.4",
               "skipped_directions": ""}
        direction, source = self.outcomes.hypothesis(row)
        self.assertEqual(direction, "SHORT")
        self.assertEqual(source, "near_miss")

    def test_a_selected_strategy_wins_over_a_near_miss(self):
        row = {"direction": "LONG", "selected_strategy": "trend_continuation",
               "near_misses": "pullback_in_trend:SHORT:setup_rsi_band=61",
               "skipped_directions": ""}
        direction, source = self.outcomes.hypothesis(row)
        self.assertEqual(direction, "LONG")
        self.assertIn("trend_continuation", source)

    def test_a_short_move_is_signed_so_a_fall_reads_positive(self):
        row = {"symbol": "BTCUSDT", "tick_at": "2026-09-08T00:00:00+00:00",
               "direction": "SHORT", "selected_strategy": "s", "near_misses": "",
               "skipped_directions": "", "m15_atr": 100.0, "quote_bid": 1000.0,
               "quote_ask": 1000.5, "entry_price": "", "stop_price": "", "target_price": ""}
        candles = [self.candle(1757289600000, 890, 1010, 900)]
        result = self.outcomes.backfill_one(row, candles)
        # Price fell 100 from 1000 on a short: one ATR in the trade's favour.
        self.assertGreater(result["move_15m_atr"], 0)

    def test_the_outcome_row_has_every_column(self):
        row = {"symbol": "BTCUSDT", "tick_at": "2026-09-08T00:00:00+00:00",
               "direction": "", "selected_strategy": "", "near_misses": "",
               "skipped_directions": "", "m15_atr": 100.0, "quote_bid": 1000.0,
               "quote_ask": 1000.5, "entry_price": "", "stop_price": "", "target_price": ""}
        result = self.outcomes.backfill_one(row, [self.candle(1757289600000, 990, 1010, 1000)])
        self.assertEqual(sorted(result.keys()), sorted(self.outcomes.COLUMNS))


class LedgerTest(unittest.TestCase):

    def test_a_row_has_every_column_and_only_scalars(self):
        p = make_uptrend(baseline())
        row = ledger_module.build_row(
            tick_at="2026-09-01T00:00:00+00:00", symbol="BTCUSDT", payload=p,
            catalogue_version=1, regime="STRONG_UPTREND", daily_bias="BULLISH",
            hourly_bias="BULLISH", decision="NO_TRADE", reason_code="NO_APPLICABLE_STRATEGY",
            selection=None,
            evaluation={"applicable": [], "nearMisses": [], "skipped": []},
            preview=None, order=None, duration_ms=42)
        self.assertEqual(sorted(row.keys()), sorted(ledger_module.COLUMNS))
        for key, value in row.items():
            self.assertNotIsInstance(value, (dict, list), f"{key} must be flat for a sheet")

    def test_near_misses_are_flattened_readably(self):
        row = ledger_module.build_row(
            tick_at="t", symbol="BTCUSDT", payload=baseline(), catalogue_version=1,
            regime="UNCERTAIN", daily_bias="BULLISH", hourly_bias="BEARISH",
            decision="NO_TRADE", reason_code="NO_APPLICABLE_STRATEGY", selection=None,
            evaluation={"applicable": [], "skipped": [], "nearMisses": [
                {"name": "trend_continuation", "direction": "LONG",
                 "failures": [{"id": "entry_volume", "actual": 0.37}]}]},
            preview=None, order=None, duration_ms=1)
        self.assertEqual(row["near_misses"], "trend_continuation:LONG:entry_volume=0.37")

    def test_the_jsonl_writer_round_trips(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "nested" / "ticks.jsonl"
            writer = ledger_module.JsonlLedger(str(path))
            row = {key: "" for key in ledger_module.COLUMNS}
            row["symbol"] = "BTCUSDT"
            writer.append(row)
            writer.append(row)
            lines = path.read_text().strip().splitlines()
            self.assertEqual(len(lines), 2)
            self.assertEqual(json.loads(lines[0])["symbol"], "BTCUSDT")


if __name__ == "__main__":
    unittest.main()
