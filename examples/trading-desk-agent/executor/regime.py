"""
Regime classification and strategy scoring.

Both are ports of the prose in the agent's context — regimeClassification and scoring — into the
arithmetic they always were. The weights and penalties are the catalogue's own numbers, not new
judgement.
"""

from dsl import LONG, SHORT, field

STRONG_UPTREND = "STRONG_UPTREND"
WEAK_UPTREND = "WEAK_UPTREND"
STRONG_DOWNTREND = "STRONG_DOWNTREND"
WEAK_DOWNTREND = "WEAK_DOWNTREND"
RANGE_BOUND = "RANGE_BOUND"
BREAKOUT_EXPANSION = "BREAKOUT_EXPANSION"
HIGH_VOLATILITY = "HIGH_VOLATILITY"
LOW_LIQUIDITY = "LOW_LIQUIDITY"
UNCERTAIN = "UNCERTAIN"

# scoring.components, verbatim from the agent's context.
COMPONENTS = {
    "regimeCompatibility": 20,
    "dailyAndHourlyAlignment": 20,
    "setupQualityOn15m": 15,
    "confirmationOn5m": 15,
    "executionQualityOn5m": 10,
    "volumeAndLiquidity": 10,
    "rewardToRiskAfterCosts": 10,
}


def _num(node, path):
    value = field(node, path)
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def _trend_side(node, direction):
    """Price on the trade's side of both long moving averages."""
    close = _num(node, "close")
    ema50 = _num(node, "movingAverages.ema50")
    ema200 = _num(node, "movingAverages.ema200")
    if None in (close, ema50, ema200):
        return False
    return (close > ema50 and close > ema200) if direction == LONG \
        else (close < ema50 and close < ema200)


def _di_side(node, direction):
    plus = _num(node, "trendStrength.plusDi")
    minus = _num(node, "trendStrength.minusDi")
    if None in (plus, minus):
        return False
    return plus > minus if direction == LONG else minus > plus


def _strong_trend(payload, direction):
    daily = field(payload, "timeframes.1d")
    hourly = field(payload, "timeframes.1h")
    structure = STRONG_UPTREND if direction == LONG else STRONG_DOWNTREND
    wanted = "HIGHER_HIGHS_HIGHER_LOWS" if direction == LONG else "LOWER_HIGHS_LOWER_LOWS"
    checks = [
        (_num(daily, "trendStrength.adx14") or 0) >= 25,
        (_num(hourly, "trendStrength.adx14") or 0) >= 25,
        _di_side(daily, direction),
        _di_side(hourly, direction),
        _trend_side(hourly, direction),
        field(hourly, "structure.classification") == wanted,
    ]
    return structure if all(checks) else None


def _weak_trend(payload, direction):
    """
    A weak trend needs price on the trade's side of the hourly ema50 *and* momentum agreeing.

    Requiring DI here, as _strong_trend already does, fixes a real contradiction: price below the
    hourly ema50 with bullish DI is a pullback inside an uptrend, not a downtrend. Without the DI
    test the LONG branch failed on price, the SHORT branch passed, and the tick was labelled
    WEAK_DOWNTREND while bias() on the same tick reported BULLISH — 22% of a 20-hour session. The
    score then compounded it, awarding a long full marks for daily-and-hourly alignment under a
    label that said the trend was down.

    When price and momentum genuinely disagree the honest answer is UNCERTAIN, which is what this
    now falls through to.
    """
    hourly = field(payload, "timeframes.1h")
    close = _num(hourly, "close")
    ema50 = _num(hourly, "movingAverages.ema50")
    adx = _num(hourly, "trendStrength.adx14")
    if None in (close, ema50, adx):
        return None
    on_side = close > ema50 if direction == LONG else close < ema50
    if not on_side or not _di_side(hourly, direction):
        return None

    # The catalogue reads "adx14 between 15 and 25, or structure mixed". The second clause was
    # missing, so a strong hourly ADX whose structure was not cleanly HH/HL matched neither
    # _strong_trend nor this, and fell through to UNCERTAIN with nothing eligible.
    #
    # Scoped to adx >= 25 deliberately: extending it below 15 would take territory that belongs to
    # RANGE_BOUND, which is checked after this and requires adx < 20.
    structure = field(hourly, "structure.classification")
    mixed = structure not in ("HIGHER_HIGHS_HIGHER_LOWS", "LOWER_HIGHS_LOWER_LOWS")
    if 15 <= adx < 25 or (adx >= 25 and mixed):
        return WEAK_UPTREND if direction == LONG else WEAK_DOWNTREND
    return None


def _breakout(payload):
    for timeframe in ("15m", "1h"):
        node = field(payload, f"timeframes.{timeframe}")
        broke = field(node, "conditions.closedAboveTwentyBarHigh") is True \
            or field(node, "conditions.closedBelowTwentyBarLow") is True
        bandwidth = _num(node, "volatility.bollingerBandwidth")
        before = _num(node, "volatility.bollingerBandwidthTwentyBarsAgo")
        volume = _num(node, "volume.ratioToAverage") or 0
        if broke and bandwidth and before and bandwidth > before * 1.2 and volume >= 1.5:
            return BREAKOUT_EXPANSION
    return None


def _high_volatility(payload):
    hourly = field(payload, "timeframes.1h")
    now = _num(hourly, "volatility.atrPercentOfPrice")
    before = _num(hourly, "volatility.atr14TwentyBarsAgo")
    daily_atr = _num(field(payload, "timeframes.1d"), "volatility.atrPercentOfPrice")
    if daily_atr is not None and daily_atr > 4:
        return HIGH_VOLATILITY
    hourly_atr = _num(hourly, "volatility.atr14")
    if hourly_atr and before and hourly_atr >= before * 1.5:
        return HIGH_VOLATILITY
    return None


def _range_bound(payload):
    node = field(payload, "timeframes.1h")
    adx = _num(node, "trendStrength.adx14")
    percent_b = _num(node, "volatility.bollingerPercentB")
    bandwidth = _num(node, "volatility.bollingerBandwidth")
    before = _num(node, "volatility.bollingerBandwidthTwentyBarsAgo")
    if adx is None or adx >= 20:
        return None
    inside = percent_b is not None and 0 <= percent_b <= 1
    settled = bandwidth is not None and before is not None and bandwidth <= before
    return RANGE_BOUND if inside and settled else None


def _low_liquidity(payload, limits):
    quote = field(payload, "quote") or {}
    spread = quote.get("spreadPercent")
    max_spread = limits.get("maxSpreadPercent")
    if spread is not None and max_spread is not None and spread > max_spread:
        return LOW_LIQUIDITY
    return None


def classify(payload, limits):
    """
    The regime, with the readings that decided it.

    Precedence is deliberate. Liquidity comes first because nothing else matters if the book cannot
    fill a risk-sized order. A strong trend outranks a breakout because the catalogue lets breakout
    strategies run inside a strong trend anyway, so the more specific label would only narrow what
    is eligible.
    """
    evidence = []
    daily = field(payload, "timeframes.1d")
    hourly = field(payload, "timeframes.1h")

    def note(timeframe, path):
        value = field(field(payload, f"timeframes.{timeframe}"), path)
        evidence.append(f"{timeframe} {path} {value}")

    for classifier in (
        lambda: _low_liquidity(payload, limits),
        lambda: _strong_trend(payload, LONG),
        lambda: _strong_trend(payload, SHORT),
        lambda: _breakout(payload),
        lambda: _high_volatility(payload),
        lambda: _weak_trend(payload, LONG),
        lambda: _weak_trend(payload, SHORT),
        lambda: _range_bound(payload),
    ):
        result = classifier()
        if result:
            note("1d", "trendStrength.adx14")
            note("1h", "trendStrength.adx14")
            note("1h", "structure.classification")
            return result, evidence

    # The daily and hourly disagree, or nothing fits cleanly.
    daily_up = _di_side(daily, LONG)
    hourly_up = _di_side(hourly, LONG)
    evidence.append(f"1d bias {'up' if daily_up else 'down'}, "
                    f"1h bias {'up' if hourly_up else 'down'}")
    return UNCERTAIN, evidence


def bias(payload, timeframe):
    node = field(payload, f"timeframes.{timeframe}")
    return "BULLISH" if _di_side(node, LONG) else "BEARISH"


def score(result, payload, regime, reward_to_risk, limits):
    """
    Scores an applicable strategy with the catalogue's own rubric, then subtracts its penalties.

    Returns the integer score and the components, so a review can see which part of a setup was
    weak rather than only that the total fell short.
    """
    direction = result["direction"]
    setup = field(payload, "timeframes.15m")
    entry = field(payload, "timeframes.5m")
    quote = field(payload, "quote") or {}
    parts = {}

    parts["regimeCompatibility"] = COMPONENTS["regimeCompatibility"]

    aligned = bias(payload, "1d") == bias(payload, "1h")
    wants_long = direction == LONG
    agrees = (bias(payload, "1h") == "BULLISH") == wants_long
    parts["dailyAndHourlyAlignment"] = COMPONENTS["dailyAndHourlyAlignment"] if aligned and agrees \
        else (COMPONENTS["dailyAndHourlyAlignment"] // 2 if agrees else 0)

    setup_adx = _num(setup, "trendStrength.adx14") or 0
    parts["setupQualityOn15m"] = COMPONENTS["setupQualityOn15m"] if setup_adx >= 25 \
        else (10 if setup_adx >= 20 else 5)

    entry_volume = _num(entry, "volume.ratioToAverage") or 0
    parts["confirmationOn5m"] = COMPONENTS["confirmationOn5m"] if entry_volume >= 1.5 \
        else (10 if entry_volume >= 1.2 else 5)

    spread = quote.get("spreadPercent")
    max_spread = limits.get("maxSpreadPercent") or 1
    parts["executionQualityOn5m"] = COMPONENTS["executionQualityOn5m"] \
        if spread is not None and spread <= max_spread / 2 else 5

    parts["volumeAndLiquidity"] = COMPONENTS["volumeAndLiquidity"] if entry_volume >= 1.0 else 3

    minimum = limits.get("minRewardToRisk") or 2.0
    parts["rewardToRiskAfterCosts"] = COMPONENTS["rewardToRiskAfterCosts"] \
        if reward_to_risk >= minimum else (5 if reward_to_risk >= minimum * 0.75 else 0)

    total = sum(parts.values())

    penalties = []
    if bias(payload, "1d") != bias(payload, "1h"):
        penalties.append(("conflicting timeframes", -15))
    if entry_volume < 1.0:
        penalties.append(("weak or declining volume", -10))
    atr = _num(setup, "volatility.atr14")
    atr_before = _num(setup, "volatility.atr14TwentyBarsAgo")
    if atr and atr_before and atr > atr_before * 2:
        penalties.append(("excessive volatility", -10))
    if spread is not None and max_spread and spread > max_spread:
        penalties.append(("wide spread", -10))
    if reward_to_risk < minimum:
        penalties.append(("costs above 20 percent of gross reward", -10))

    total += sum(amount for _, amount in penalties)
    return max(0, int(round(total))), parts, penalties
