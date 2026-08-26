"""Deterministic indicator maths for the trading desk sidecar.

Every number the agent reasons about is produced here, in code, from closed candles.
Nothing in this module talks to the network or to the model; it takes OHLCV lists and
returns plain floats. That separation is the point of the example: the LLM classifies
and ranks, it never calculates.

Wilder smoothing is used for RSI, ATR and ADX because that is what the standard
definitions specify; using a plain EMA there silently changes every threshold a
strategy is scored against.
"""

from statistics import pstdev


def sma(values, period):
    """Simple moving average series, None until `period` values exist."""
    out = [None] * len(values)
    if period <= 0 or len(values) < period:
        return out
    running = sum(values[:period])
    out[period - 1] = running / period
    for i in range(period, len(values)):
        running += values[i] - values[i - period]
        out[i] = running / period
    return out


def ema(values, period):
    """EMA seeded with the SMA of the first `period` values."""
    out = [None] * len(values)
    if period <= 0 or len(values) < period:
        return out
    multiplier = 2.0 / (period + 1)
    seed = sum(values[:period]) / period
    out[period - 1] = seed
    previous = seed
    for i in range(period, len(values)):
        previous = (values[i] - previous) * multiplier + previous
        out[i] = previous
    return out


def wilder(values, period):
    """Wilder's smoothing: seed with a sum of `period`, then decay by 1/period."""
    out = [None] * len(values)
    if period <= 0 or len(values) < period:
        return out
    seed = sum(values[:period])
    out[period - 1] = seed
    previous = seed
    for i in range(period, len(values)):
        previous = previous - (previous / period) + values[i]
        out[i] = previous
    return out


def rsi(closes, period=14):
    """Wilder RSI. Returns a series aligned to `closes`."""
    out = [None] * len(closes)
    if len(closes) <= period:
        return out
    gains, losses = [], []
    for i in range(1, len(closes)):
        change = closes[i] - closes[i - 1]
        gains.append(max(change, 0.0))
        losses.append(max(-change, 0.0))
    average_gain = sum(gains[:period]) / period
    average_loss = sum(losses[:period]) / period
    out[period] = _rsi_value(average_gain, average_loss)
    for i in range(period, len(gains)):
        average_gain = (average_gain * (period - 1) + gains[i]) / period
        average_loss = (average_loss * (period - 1) + losses[i]) / period
        out[i + 1] = _rsi_value(average_gain, average_loss)
    return out


def _rsi_value(average_gain, average_loss):
    if average_loss == 0:
        return 100.0 if average_gain > 0 else 50.0
    return 100.0 - (100.0 / (1.0 + average_gain / average_loss))


def macd(closes, fast=12, slow=26, signal=9):
    """MACD line, signal line and histogram, each aligned to `closes`."""
    fast_line, slow_line = ema(closes, fast), ema(closes, slow)
    macd_line = [
        None if fast_line[i] is None or slow_line[i] is None else fast_line[i] - slow_line[i]
        for i in range(len(closes))
    ]
    defined = [value for value in macd_line if value is not None]
    signal_defined = ema(defined, signal)
    signal_line = [None] * len(closes)
    offset = len(closes) - len(defined)
    for i, value in enumerate(signal_defined):
        signal_line[offset + i] = value
    histogram = [
        None if macd_line[i] is None or signal_line[i] is None else macd_line[i] - signal_line[i]
        for i in range(len(closes))
    ]
    return macd_line, signal_line, histogram


def true_ranges(highs, lows, closes):
    ranges = [highs[0] - lows[0]]
    for i in range(1, len(closes)):
        previous_close = closes[i - 1]
        ranges.append(max(
            highs[i] - lows[i],
            abs(highs[i] - previous_close),
            abs(lows[i] - previous_close)))
    return ranges


def atr(highs, lows, closes, period=14):
    """Wilder ATR as an average (the smoothed sum divided by the period)."""
    smoothed = wilder(true_ranges(highs, lows, closes), period)
    return [None if value is None else value / period for value in smoothed]


def adx(highs, lows, closes, period=14):
    """Wilder ADX with +DI and -DI. Returns (adx, plus_di, minus_di)."""
    length = len(closes)
    empty = [None] * length
    if length < period * 2:
        return empty, empty, empty
    plus_dm, minus_dm = [0.0], [0.0]
    for i in range(1, length):
        up = highs[i] - highs[i - 1]
        down = lows[i - 1] - lows[i]
        plus_dm.append(up if up > down and up > 0 else 0.0)
        minus_dm.append(down if down > up and down > 0 else 0.0)
    smoothed_tr = wilder(true_ranges(highs, lows, closes), period)
    smoothed_plus = wilder(plus_dm, period)
    smoothed_minus = wilder(minus_dm, period)
    plus_di, minus_di, dx = [None] * length, [None] * length, []
    for i in range(length):
        if smoothed_tr[i] in (None, 0) or smoothed_plus[i] is None:
            continue
        plus_di[i] = 100.0 * smoothed_plus[i] / smoothed_tr[i]
        minus_di[i] = 100.0 * smoothed_minus[i] / smoothed_tr[i]
        total = plus_di[i] + minus_di[i]
        dx.append((i, 0.0 if total == 0 else 100.0 * abs(plus_di[i] - minus_di[i]) / total))
    adx_series = [None] * length
    if len(dx) < period:
        return adx_series, plus_di, minus_di
    values = [value for _, value in dx]
    average = sum(values[:period]) / period
    adx_series[dx[period - 1][0]] = average
    for offset in range(period, len(values)):
        average = (average * (period - 1) + values[offset]) / period
        adx_series[dx[offset][0]] = average
    return adx_series, plus_di, minus_di


def bollinger(closes, period=20, deviations=2.0):
    """Bollinger bands with a population standard deviation, plus %B and bandwidth."""
    middle = sma(closes, period)
    upper, lower, percent_b, bandwidth = ([None] * len(closes) for _ in range(4))
    for i in range(period - 1, len(closes)):
        spread = pstdev(closes[i - period + 1:i + 1])
        upper[i] = middle[i] + deviations * spread
        lower[i] = middle[i] - deviations * spread
        width = upper[i] - lower[i]
        percent_b[i] = None if width == 0 else (closes[i] - lower[i]) / width
        bandwidth[i] = None if middle[i] == 0 else width / middle[i]
    return middle, upper, lower, percent_b, bandwidth


def swings(highs, lows, reach=2):
    """Fractal swing points: a high with `reach` lower highs on both sides, and the inverse.

    Returns (swing_highs, swing_lows) as lists of (index, price), oldest first. The last
    `reach` bars can never qualify, which is correct: a swing is only confirmed once
    price has moved away from it.
    """
    swing_highs, swing_lows = [], []
    for i in range(reach, len(highs) - reach):
        window = range(i - reach, i + reach + 1)
        if all(highs[i] >= highs[j] for j in window) and any(highs[i] > highs[j] for j in window):
            swing_highs.append((i, highs[i]))
        if all(lows[i] <= lows[j] for j in window) and any(lows[i] < lows[j] for j in window):
            swing_lows.append((i, lows[i]))
    return swing_highs, swing_lows


def structure(swing_highs, swing_lows):
    """Classify market structure from the last two confirmed swings of each kind."""
    if len(swing_highs) < 2 or len(swing_lows) < 2:
        return "INDETERMINATE"
    higher_high = swing_highs[-1][1] > swing_highs[-2][1]
    higher_low = swing_lows[-1][1] > swing_lows[-2][1]
    if higher_high and higher_low:
        return "HIGHER_HIGHS_HIGHER_LOWS"
    if not higher_high and not higher_low:
        return "LOWER_HIGHS_LOWER_LOWS"
    if higher_low and not higher_high:
        return "HIGHER_LOWS_LOWER_HIGHS_CONTRACTING"
    return "LOWER_LOWS_HIGHER_HIGHS_EXPANDING"


def zones(swing_highs, swing_lows, price, tolerance):
    """Cluster swing levels into support/resistance zones.

    Levels within `tolerance` (an ATR fraction) of each other are one zone. Touch count
    is what makes a zone worth respecting, so it is reported rather than inferred.
    """
    levels = sorted(level for _, level in swing_highs + swing_lows)
    if not levels or tolerance <= 0:
        return [], []
    clusters, current = [], [levels[0]]
    for level in levels[1:]:
        if level - current[-1] <= tolerance:
            current.append(level)
        else:
            clusters.append(current)
            current = [level]
    clusters.append(current)
    built = [
        {"level": round(sum(c) / len(c), 8), "touches": len(c),
         "low": round(min(c), 8), "high": round(max(c), 8)}
        for c in clusters
    ]
    support = [z for z in built if z["level"] < price]
    resistance = [z for z in built if z["level"] >= price]
    support.sort(key=lambda z: price - z["level"])
    resistance.sort(key=lambda z: z["level"] - price)
    return support[:3], resistance[:3]
