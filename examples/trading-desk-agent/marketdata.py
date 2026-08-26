"""Candle retrieval, contract validation and per-timeframe analysis.

The agent never sees raw candles. It sees the validation verdict and the derived
numbers, which is deliberate: 200 candles x 5 timeframes is both too large for a
prompt and an open invitation for the model to do arithmetic it should not be doing.
"""

import json
import urllib.error
import urllib.request

import indicators

BINANCE = "https://api.binance.com"
USER_AGENT = "Custom-Agent-Platform/1.0"
REQUIRED_CANDLES = 200

# Binance interval -> length in milliseconds. Only these five are offered, because they
# are the five the agent's playbook assigns a distinct job to.
INTERVALS = {
    "1m": 60_000,
    "5m": 300_000,
    "15m": 900_000,
    "1h": 3_600_000,
    "1d": 86_400_000,
}

# How stale the newest closed candle may be before the timeframe is unusable, expressed
# as a multiple of the interval. Two allows for one late candle without accepting a feed
# that has quietly stopped.
STALENESS_MULTIPLE = 2


class DataError(Exception):
    """Raised when the exchange cannot be reached or answers with something unusable."""


def _get(path, params):
    query = "&".join(f"{key}={value}" for key, value in params.items())
    request = urllib.request.Request(
        f"{BINANCE}{path}?{query}", headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raise DataError(f"{path} returned HTTP {error.code}") from error
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise DataError(f"{path} was unreachable or returned invalid JSON: {error}") from error


def symbol_filters(symbol):
    """Tick size, lot step and minimum notional, straight from the exchange."""
    payload = _get("/api/v3/exchangeInfo", {"symbol": symbol})
    entries = payload.get("symbols") or []
    if not entries:
        raise DataError(f"exchange does not list symbol {symbol}")
    entry = entries[0]
    filters = {item["filterType"]: item for item in entry.get("filters", [])}
    return {
        "symbol": entry["symbol"],
        "status": entry.get("status"),
        "baseAsset": entry.get("baseAsset"),
        "quoteAsset": entry.get("quoteAsset"),
        "tickSize": float(filters.get("PRICE_FILTER", {}).get("tickSize", "0.01")),
        "lotStep": float(filters.get("LOT_SIZE", {}).get("stepSize", "0.00001")),
        "minQuantity": float(filters.get("LOT_SIZE", {}).get("minQty", "0")),
        "minNotional": float(filters.get("NOTIONAL", {}).get("minNotional", "0")),
    }


def book(symbol):
    """Top of book plus the 24h roll, used for spread, liquidity and staleness."""
    top = _get("/api/v3/ticker/bookTicker", {"symbol": symbol})
    day = _get("/api/v3/ticker/24hr", {"symbol": symbol})
    bid, ask = float(top["bidPrice"]), float(top["askPrice"])
    if bid <= 0 or ask <= 0 or ask < bid:
        raise DataError(f"crossed or empty book for {symbol}: bid={bid} ask={ask}")
    mid = (bid + ask) / 2
    return {
        "bid": bid,
        "ask": ask,
        "bidQuantity": float(top["bidQty"]),
        "askQuantity": float(top["askQty"]),
        "mid": round(mid, 8),
        "spreadAbsolute": round(ask - bid, 8),
        "spreadPercent": round((ask - bid) / mid * 100, 6),
        "lastPrice": float(day["lastPrice"]),
        "quoteVolume24h": float(day["quoteVolume"]),
        "exchangeTimeMs": int(day["closeTime"]),
    }


def closed_candles(symbol, interval, now_ms):
    """The newest 200 fully closed candles.

    Binance returns the forming candle as the final element, so more than 200 are
    requested and anything whose close time has not passed is dropped. Trading the
    forming candle is the single easiest way to backtest a strategy that cannot exist.
    """
    raw = _get("/api/v3/klines", {"symbol": symbol, "interval": interval,
                                  "limit": REQUIRED_CANDLES + 5})
    if not isinstance(raw, list):
        raise DataError(f"klines for {interval} was not a JSON array")
    candles = [
        {"openTime": int(row[0]), "open": float(row[1]), "high": float(row[2]),
         "low": float(row[3]), "close": float(row[4]), "volume": float(row[5]),
         "closeTime": int(row[6]), "trades": int(row[8])}
        for row in raw
        if int(row[6]) < now_ms
    ]
    return candles[-REQUIRED_CANDLES:]


def validate(candles, interval, now_ms):
    """Every check the playbook demands, as a list of human-readable failures.

    An empty list means the timeframe may be traded on. Anything else must reach the
    agent verbatim so a NO_TRADE carries a reason rather than a shrug.
    """
    issues = []
    span = INTERVALS[interval]
    if len(candles) != REQUIRED_CANDLES:
        issues.append(
            f"{interval}: {len(candles)} closed candles available, {REQUIRED_CANDLES} required")
        return issues
    seen = set()
    for index, candle in enumerate(candles):
        open_time = candle["openTime"]
        if open_time in seen:
            issues.append(f"{interval}: duplicate candle at {open_time}")
        seen.add(open_time)
        if index and open_time <= candles[index - 1]["openTime"]:
            issues.append(f"{interval}: candles are not in chronological order at index {index}")
        if index and open_time - candles[index - 1]["openTime"] != span:
            issues.append(
                f"{interval}: gap or misaligned boundary between index {index - 1} and {index}")
        if open_time % span != 0:
            issues.append(f"{interval}: candle at {open_time} is not aligned to the interval")
        high, low = candle["high"], candle["low"]
        if high < low or high < max(candle["open"], candle["close"]) \
                or low > min(candle["open"], candle["close"]):
            issues.append(f"{interval}: invalid OHLC relationship at {open_time}")
        if min(candle["open"], candle["high"], candle["low"], candle["close"]) <= 0:
            issues.append(f"{interval}: non-positive price at {open_time}")
        if candle["volume"] < 0:
            issues.append(f"{interval}: negative volume at {open_time}")
    if all(candle["volume"] == 0 for candle in candles):
        issues.append(f"{interval}: every candle has zero volume")
    age = now_ms - candles[-1]["closeTime"]
    if age > span * STALENESS_MULTIPLE:
        issues.append(
            f"{interval}: newest closed candle is {age // 1000}s old, "
            f"limit is {span * STALENESS_MULTIPLE // 1000}s")
    # One duplicate produces one message per affected index; collapse for readability.
    return sorted(set(issues))


def _last(series):
    for value in reversed(series):
        if value is not None:
            return value
    return None


def _at(series, offset):
    """The value `offset` bars back from the end, or None if it was never defined."""
    index = len(series) - 1 - offset
    return series[index] if 0 <= index < len(series) else None


def _percent(numerator, denominator):
    if not denominator:
        return None
    return round(numerator / denominator * 100, 4)


def analyse(candles, interval):
    """Turn 200 closed candles into the fixed set of numbers the playbook names."""
    opens = [c["open"] for c in candles]
    highs = [c["high"] for c in candles]
    lows = [c["low"] for c in candles]
    closes = [c["close"] for c in candles]
    volumes = [c["volume"] for c in candles]
    price = closes[-1]

    ema9, ema20 = indicators.ema(closes, 9), indicators.ema(closes, 20)
    ema50, ema200 = indicators.ema(closes, 50), indicators.ema(closes, 200)
    sma20, sma50 = indicators.sma(closes, 20), indicators.sma(closes, 50)
    rsi14 = indicators.rsi(closes, 14)
    macd_line, signal_line, histogram = indicators.macd(closes)
    atr14 = indicators.atr(highs, lows, closes, 14)
    middle, upper, lower, percent_b, bandwidth = indicators.bollinger(closes)
    adx14, plus_di, minus_di = indicators.adx(highs, lows, closes, 14)
    volume_average = indicators.sma(volumes, 20)

    atr_now = _last(atr14)
    swing_highs, swing_lows = indicators.swings(highs, lows)
    support, resistance = indicators.zones(
        swing_highs, swing_lows, price, (atr_now or price * 0.001) * 0.5)

    recent_high = max(highs[-21:-1]) if len(highs) > 21 else max(highs[:-1])
    recent_low = min(lows[-21:-1]) if len(lows) > 21 else min(lows[:-1])
    last = candles[-1]
    body = abs(last["close"] - last["open"])
    span = last["high"] - last["low"]

    def rounded(value, digits=8):
        return None if value is None else round(value, digits)

    return {
        "timeframe": interval,
        "candles": len(candles),
        "lastClosedAtMs": last["closeTime"],
        "close": rounded(price),
        "movingAverages": {
            "ema9": rounded(_last(ema9), 4), "ema20": rounded(_last(ema20), 4),
            "ema50": rounded(_last(ema50), 4), "ema200": rounded(_last(ema200), 4),
            "sma20": rounded(_last(sma20), 4), "sma50": rounded(_last(sma50), 4),
        },
        "distanceFromMasPercent": {
            "ema20": _percent(price - (_last(ema20) or 0), _last(ema20)),
            "ema50": _percent(price - (_last(ema50) or 0), _last(ema50)),
            "ema200": _percent(price - (_last(ema200) or 0), _last(ema200)),
        },
        "momentum": {
            "rsi14": rounded(_last(rsi14), 2),
            "rsi14FiveBarsAgo": rounded(_at(rsi14, 5), 2),
            "macd": rounded(_last(macd_line), 6),
            "macdSignal": rounded(_last(signal_line), 6),
            "macdHistogram": rounded(_last(histogram), 6),
            "macdHistogramThreeBarsAgo": rounded(_at(histogram, 3), 6),
        },
        "volatility": {
            "atr14": rounded(atr_now, 6),
            "atrPercentOfPrice": _percent(atr_now or 0, price),
            "atr14TwentyBarsAgo": rounded(_at(atr14, 20), 6),
            "bollingerMiddle": rounded(_last(middle), 4),
            "bollingerUpper": rounded(_last(upper), 4),
            "bollingerLower": rounded(_last(lower), 4),
            "bollingerPercentB": rounded(_last(percent_b), 4),
            "bollingerBandwidth": rounded(_last(bandwidth), 6),
            "bollingerBandwidthTwentyBarsAgo": rounded(_at(bandwidth, 20), 6),
        },
        "trendStrength": {
            "adx14": rounded(_last(adx14), 2),
            "plusDi": rounded(_last(plus_di), 2),
            "minusDi": rounded(_last(minus_di), 2),
        },
        "structure": {
            "classification": indicators.structure(swing_highs, swing_lows),
            "lastSwingHighs": [rounded(level, 4) for _, level in swing_highs[-3:]],
            "lastSwingLows": [rounded(level, 4) for _, level in swing_lows[-3:]],
        },
        "levels": {
            "support": support,
            "resistance": resistance,
            "twentyBarHigh": rounded(recent_high, 4),
            "twentyBarLow": rounded(recent_low, 4),
        },
        "volume": {
            "last": rounded(volumes[-1], 4),
            "average20": rounded(_last(volume_average), 4),
            "ratioToAverage": (None if not _last(volume_average)
                               else round(volumes[-1] / _last(volume_average), 3)),
            "trades": last["trades"],
        },
        "conditions": {
            "closedAboveTwentyBarHigh": price > recent_high,
            "closedBelowTwentyBarLow": price < recent_low,
            "upperWickRejection": span > 0 and (last["high"] - max(last["open"], last["close"]))
                                  > body and body / span < 0.4,
            "lowerWickRejection": span > 0 and (min(last["open"], last["close"]) - last["low"])
                                  > body and body / span < 0.4,
            "insideBar": last["high"] <= highs[-2] and last["low"] >= lows[-2],
        },
    }
