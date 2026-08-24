"""Paper broker, position sizing and the pre-trade risk gate.

Every limit in desk-config.json is enforced here, not in the prompt. A model that
decides to trade anyway still has to get past `evaluate`, and `submit` re-runs the same
evaluation rather than trusting the preview the model saw. That is the whole reason the
sizing arithmetic does not live in the agent's instructions: a rule the model can
restate is a rule the model can talk itself out of.
"""

import json
import time
import uuid
from decimal import Decimal, ROUND_DOWN, ROUND_HALF_UP


def load_config(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def _floor_to_step(value, step):
    if step <= 0:
        return value
    quantum = Decimal(str(step))
    return float((Decimal(str(value)) / quantum).to_integral_value(ROUND_DOWN) * quantum)


def _is_on_step(value, step):
    if step <= 0:
        return True
    quantum = Decimal(str(step))
    remainder = (Decimal(str(value)) / quantum) % 1
    return remainder in (Decimal(0),) or remainder.quantize(Decimal("1e-9")) == Decimal(0)


def _round_to_tick(value, tick):
    if tick <= 0:
        return value
    quantum = Decimal(str(tick))
    return float((Decimal(str(value)) / quantum).quantize(Decimal(1), ROUND_HALF_UP) * quantum)


class Desk:
    """In-memory paper account. State is lost on restart, which is intentional for an example."""

    def __init__(self, config):
        self.config = config
        self.equity = float(config["startingEquity"])
        self.realised_pnl_today = 0.0
        self.consecutive_losses = 0
        self.last_fill_epoch = None
        self.positions = []
        self.orders = []
        self.by_idempotency_key = {}

    # ---------------------------------------------------------------- account

    def exposure(self):
        return sum(abs(p["quantity"]) * p["entryPrice"] for p in self.positions)

    def account_state(self, symbol, filters, quote):
        limits = self.config
        cooldown_remaining = 0
        if self.last_fill_epoch is not None:
            elapsed = time.time() - self.last_fill_epoch
            cooldown_remaining = max(0, int(limits["tradeCooldownMinutes"] * 60 - elapsed))
        daily_loss_percent = (-self.realised_pnl_today / self.equity * 100) if self.equity else 0.0
        return {
            "mode": limits["mode"],
            "instrument": limits["instrument"],
            "exchange": limits["exchange"],
            "asOfMs": int(time.time() * 1000),
            "equity": round(self.equity, 2),
            "buyingPower": round(self.equity * limits["maxLeverage"], 2),
            "openExposure": round(self.exposure(), 2),
            "openExposurePercentOfEquity": round(self.exposure() / self.equity * 100, 3)
            if self.equity else 0.0,
            "realisedPnlToday": round(self.realised_pnl_today, 2),
            "dailyLossPercent": round(daily_loss_percent, 3) if daily_loss_percent > 0 else 0.0,
            "consecutiveLosses": self.consecutive_losses,
            "cooldownSecondsRemaining": cooldown_remaining,
            "circuitBreakers": {
                "dailyLossTriggered": daily_loss_percent >= limits["maxDailyLossPercent"],
                "consecutiveLossTriggered":
                    self.consecutive_losses >= limits["maxConsecutiveLosses"],
                "cooldownActive": cooldown_remaining > 0,
            },
            "positions": [dict(position) for position in self.positions],
            "openOrders": [order for order in self.orders if order["status"] == "WORKING"],
            "recentOrders": self.orders[-5:],
            "instrumentFilters": filters,
            "quote": quote,
            "limits": {
                "riskPerTradePercent": limits["riskPerTradePercent"],
                "maxPositionExposurePercent": limits["maxPositionExposurePercent"],
                "maxPortfolioExposurePercent": limits["maxPortfolioExposurePercent"],
                "maxDailyLossPercent": limits["maxDailyLossPercent"],
                "maxConsecutiveLosses": limits["maxConsecutiveLosses"],
                "minRewardToRisk": limits["minRewardToRisk"],
                "minStrategyScore": limits["minStrategyScore"],
                "minScoreMarginOverRunnerUp": limits["minScoreMarginOverRunnerUp"],
                "maxSpreadPercent": limits["maxSpreadPercent"],
                "maxSlippagePercent": limits["maxSlippagePercent"],
                "maxLeverage": limits["maxLeverage"],
                "tradeCooldownMinutes": limits["tradeCooldownMinutes"],
                "orderExpirySeconds": limits["orderExpirySeconds"],
                "maxEntryDistanceInAtr": limits["maxEntryDistanceInAtr"],
                "allowLong": limits["allowLong"],
                "allowShort": limits["allowShort"],
            },
        }

    # ---------------------------------------------------------------- sizing

    def evaluate(self, request, filters, quote):
        """Size the trade and run every pre-trade check. Never raises on a bad plan."""
        limits = self.config
        checks = []

        def check(name, passed, detail):
            checks.append({"name": name, "passed": bool(passed), "detail": detail})
            return bool(passed)

        direction = request["direction"].upper()
        entry = float(request["entryPrice"])
        stop = float(request["stopPrice"])
        target = float(request["targetPrice"])
        score = float(request.get("strategyScore", 0))
        runner_up = float(request.get("runnerUpScore", 0))
        reference = quote["ask"] if direction == "BUY" else quote["bid"]
        atr = float(request.get("atr14", 0)) or None

        check("mode_is_paper", limits["mode"] == "PAPER", f"desk mode is {limits['mode']}")
        check("instrument_matches", request["symbol"] == limits["instrument"],
              f"requested {request['symbol']}, desk trades {limits['instrument']}")
        check("direction_allowed",
              limits["allowLong"] if direction == "BUY" else limits["allowShort"],
              f"{direction} permitted by configuration")
        check("market_tradable", filters.get("status") == "TRADING",
              f"exchange symbol status is {filters.get('status')}")

        state = self.account_state(request["symbol"], filters, quote)
        breakers = state["circuitBreakers"]
        check("daily_loss_breaker", not breakers["dailyLossTriggered"],
              f"day is {state['dailyLossPercent']}% down, limit {limits['maxDailyLossPercent']}%")
        check("consecutive_loss_breaker", not breakers["consecutiveLossTriggered"],
              f"{self.consecutive_losses} consecutive losses, limit {limits['maxConsecutiveLosses']}")
        check("cooldown_elapsed", not breakers["cooldownActive"],
              f"{state['cooldownSecondsRemaining']}s of cooldown remaining")
        check("no_conflicting_position",
              not any(p["symbol"] == request["symbol"] for p in self.positions),
              "no open position in this instrument")
        check("no_working_order",
              not any(o["status"] == "WORKING" and o["symbol"] == request["symbol"]
                      for o in self.orders),
              "no working order in this instrument")

        if direction == "BUY":
            geometry = stop < entry < target
        else:
            geometry = target < entry < stop
        check("stop_and_target_geometry", geometry,
              f"{direction} with entry {entry}, stop {stop}, target {target}")
        tick = filters["tickSize"]
        check("prices_on_tick",
              all(_is_on_step(price, tick) for price in (entry, stop, target)),
              f"tick size {tick}")

        check("spread_within_limit", quote["spreadPercent"] <= limits["maxSpreadPercent"],
              f"spread {quote['spreadPercent']}%, limit {limits['maxSpreadPercent']}%")
        estimated_slippage_percent = round(quote["spreadPercent"] / 2, 6)
        check("slippage_within_limit",
              estimated_slippage_percent <= limits["maxSlippagePercent"],
              f"estimated {estimated_slippage_percent}%, limit {limits['maxSlippagePercent']}%")
        drift_percent = abs(entry - reference) / reference * 100
        check("entry_near_market", drift_percent <= limits["maxEntryDriftPercent"],
              f"entry is {round(drift_percent, 4)}% from the {('ask' if direction == 'BUY' else 'bid')}, "
              f"limit {limits['maxEntryDriftPercent']}%")
        if atr:
            check("stop_within_atr_budget",
                  abs(entry - stop) <= atr * limits["maxEntryDistanceInAtr"],
                  f"stop is {round(abs(entry - stop) / atr, 2)} ATR away, "
                  f"limit {limits['maxEntryDistanceInAtr']}")
        else:
            check("stop_within_atr_budget", False, "no atr14 supplied, cannot bound the stop")

        check("score_meets_threshold", score >= limits["minStrategyScore"],
              f"score {score}, minimum {limits['minStrategyScore']}")
        check("score_margin_over_runner_up",
              score - runner_up >= limits["minScoreMarginOverRunnerUp"],
              f"margin {round(score - runner_up, 2)}, "
              f"minimum {limits['minScoreMarginOverRunnerUp']}")

        risk_amount = self.equity * limits["riskPerTradePercent"] / 100
        risk_per_unit = abs(entry - stop)
        caps = {}
        if risk_per_unit <= 0:
            quantity = 0.0
        else:
            quantity = risk_amount / risk_per_unit
            caps["risk"] = quantity
            caps["positionExposure"] = (
                self.equity * limits["maxPositionExposurePercent"] / 100) / entry
            remaining_portfolio = max(
                0.0, self.equity * limits["maxPortfolioExposurePercent"] / 100 - self.exposure())
            caps["portfolioExposure"] = remaining_portfolio / entry
            caps["buyingPower"] = (self.equity * limits["maxLeverage"]) / entry
            caps["bookLiquidity"] = (
                quote["askQuantity"] if direction == "BUY" else quote["bidQuantity"]
            ) * limits["maxShareOfTopOfBook"]
            quantity = min(caps.values())
        quantity = _floor_to_step(quantity, filters["lotStep"])
        binding = min(caps, key=caps.get) if caps else None

        notional = quantity * entry
        fee_rate = limits["feeBps"] / 10_000
        fees = notional * fee_rate * 2
        slippage_cost = quantity * quote["spreadAbsolute"] / 2
        costs = fees + slippage_cost
        gross_reward = abs(target - entry) * quantity
        gross_risk = risk_per_unit * quantity
        reward_to_risk = round((gross_reward - costs) / (gross_risk + costs), 3) \
            if gross_risk + costs > 0 else 0.0

        check("quantity_is_tradable", quantity > 0,
              f"{quantity} after rounding down to lot step {filters['lotStep']}")
        check("meets_minimum_quantity", quantity >= filters["minQuantity"],
              f"exchange minimum {filters['minQuantity']}")
        check("meets_minimum_notional", notional >= filters["minNotional"],
              f"notional {round(notional, 2)}, exchange minimum {filters['minNotional']}")
        check("position_exposure_within_limit",
              notional <= self.equity * limits["maxPositionExposurePercent"] / 100 + 1e-9,
              f"{round(notional / self.equity * 100, 3)}% of equity, "
              f"limit {limits['maxPositionExposurePercent']}%")
        check("portfolio_exposure_within_limit",
              self.exposure() + notional
              <= self.equity * limits["maxPortfolioExposurePercent"] / 100 + 1e-9,
              f"{round((self.exposure() + notional) / self.equity * 100, 3)}% of equity, "
              f"limit {limits['maxPortfolioExposurePercent']}%")
        check("leverage_within_limit", notional <= self.equity * limits["maxLeverage"] + 1e-9,
              f"{round(notional / self.equity, 3)}x, limit {limits['maxLeverage']}x")
        check("reward_to_risk_after_costs", reward_to_risk >= limits["minRewardToRisk"],
              f"{reward_to_risk}:1 after costs, minimum {limits['minRewardToRisk']}:1")

        failures = [entry_["name"] for entry_ in checks if not entry_["passed"]]
        return {
            "symbol": request["symbol"],
            "direction": direction,
            "orderType": "LIMIT",
            "entryPrice": entry,
            "stopPrice": stop,
            "targetPrice": target,
            "quantity": quantity,
            "notional": round(notional, 2),
            "riskAmount": round(min(risk_amount, gross_risk), 2),
            "riskPercentOfEquity": round(min(risk_amount, gross_risk) / self.equity * 100, 4)
            if self.equity else 0.0,
            "bindingConstraint": binding,
            "estimatedFees": round(fees, 4),
            "estimatedSlippageCost": round(slippage_cost, 4),
            "estimatedSlippagePercent": estimated_slippage_percent,
            "rewardToRiskAfterCosts": reward_to_risk,
            "referencePrice": reference,
            "checks": checks,
            "passed": not failures,
            "failures": failures,
        }

    # ---------------------------------------------------------------- orders

    def submit(self, request, filters, quote):
        """Idempotent bracket submission. Re-evaluates rather than trusting the preview."""
        key = request["idempotencyKey"]
        if key in self.by_idempotency_key:
            existing = self.by_idempotency_key[key]
            return {"duplicate": True, "order": existing,
                    "message": "idempotency key already used; returning the original order"}

        evaluation = self.evaluate(request, filters, quote)
        if not evaluation["passed"]:
            rejection = {
                "status": "REJECTED",
                "idempotencyKey": key,
                "failures": evaluation["failures"],
                "checks": evaluation["checks"],
                "message": "pre-trade risk checks failed; nothing was submitted",
            }
            self.by_idempotency_key[key] = rejection
            return {"duplicate": False, "order": rejection}

        direction = evaluation["direction"]
        entry = evaluation["entryPrice"]
        marketable = quote["ask"] <= entry if direction == "BUY" else quote["bid"] >= entry
        order_id = f"PAPER-{uuid.uuid4().hex[:12].upper()}"
        order = {
            "brokerOrderId": order_id,
            "idempotencyKey": key,
            "symbol": evaluation["symbol"],
            "direction": direction,
            "orderType": "LIMIT",
            "timeInForce": f"GTD:{self.config['orderExpirySeconds']}s",
            "quantity": evaluation["quantity"],
            "limitPrice": entry,
            "strategy": request.get("strategy"),
            "submittedAtMs": int(time.time() * 1000),
            "status": "WORKING",
            "fillPrice": None,
            "filledQuantity": 0.0,
            "protectiveStopOrderId": None,
            "targetOrderId": None,
        }

        if marketable:
            fill = quote["ask"] if direction == "BUY" else quote["bid"]
            order.update({
                "status": "FILLED",
                "fillPrice": fill,
                "filledQuantity": evaluation["quantity"],
                "protectiveStopOrderId": f"{order_id}-STP",
                "targetOrderId": f"{order_id}-TGT",
                "stopPrice": evaluation["stopPrice"],
                "targetPrice": evaluation["targetPrice"],
            })
            self.positions.append({
                "symbol": evaluation["symbol"],
                "side": "LONG" if direction == "BUY" else "SHORT",
                "quantity": evaluation["quantity"],
                "entryPrice": fill,
                "stopPrice": evaluation["stopPrice"],
                "targetPrice": evaluation["targetPrice"],
                "openedAtMs": order["submittedAtMs"],
                "brokerOrderId": order_id,
            })
            self.last_fill_epoch = time.time()

        self.orders.append(order)
        self.by_idempotency_key[key] = order
        return {"duplicate": False, "order": order, "evaluation": evaluation}
