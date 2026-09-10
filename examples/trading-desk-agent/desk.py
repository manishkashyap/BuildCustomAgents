#!/usr/bin/env python3
"""The trading desk sidecar: market analysis, account state and a paper broker.

Run it next to the agent runtime:

    python3 examples/trading-desk-agent/desk.py --port 8090

Five endpoints, one per tool definition. Every response carries a top-level `ok`, and a
failure is reported as `ok: false` with HTTP 200 rather than a 4xx, because the runtime's
HttpToolExecutor turns a non-2xx into an AgentExecutionException that kills the run. A
data problem should reach the model as data, so it can return NO_TRADE with a reason.

Spot market data comes from Binance's public REST API: keyless, JSON, and it accepts an
explicit candle count and the exact five intervals the playbook uses. The account and
order side is simulated in this process; nothing here can reach a real venue.
"""

import argparse
import datetime
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import broker
import marketdata

TIMEFRAMES = ["5m", "15m", "1h", "4h", "1d"]
FILTER_CACHE_SECONDS = 300


def iso(milliseconds):
    """UTC ISO-8601 for the agent to copy into its output.

    Supplied rather than left to the model: an epoch-to-timestamp conversion is
    arithmetic, and this desk does the arithmetic.
    """
    return datetime.datetime.fromtimestamp(
        milliseconds / 1000, datetime.timezone.utc).isoformat(timespec="seconds")

_desk = None
_filters_cache = {}


def instrument_filters(symbol):
    cached = _filters_cache.get(symbol)
    if cached and time.time() - cached[0] < FILTER_CACHE_SECONDS:
        return cached[1]
    filters = marketdata.symbol_filters(symbol)
    _filters_cache[symbol] = (time.time(), filters)
    return filters


def analysis(symbol):
    now = int(time.time() * 1000)
    issues, timeframes = [], {}
    for interval in TIMEFRAMES:
        candles = marketdata.closed_candles(symbol, interval, now)
        problems = marketdata.validate(candles, interval, now)
        if problems:
            issues.extend(problems)
            continue
        timeframes[interval] = marketdata.analyse(candles, interval)
    quote = marketdata.book(symbol)
    return {
        "ok": True,
        "symbol": symbol,
        "asOfMs": now,
        "asOf": iso(now),
        "candlesPerTimeframe": marketdata.REQUIRED_CANDLES,
        "timeframeRoles": {
            "1d": "primary trend, volatility environment, major levels",
            "4h": "intermediate trend, and whether a daily move has follow-through",
            "1h": "regime and directional bias",
            "15m": "setup and structural confirmation",
            "5m": "entry confirmation and momentum",
        },
        "dataValidation": {"valid": not issues, "issues": issues},
        "quote": quote,
        "timeframes": timeframes,
    }


def quote_payload(symbol):
    quote = marketdata.book(symbol)
    limits = _desk.config
    quote["spreadWithinLimit"] = quote["spreadPercent"] <= limits["maxSpreadPercent"]
    quote["maxSpreadPercent"] = limits["maxSpreadPercent"]
    now = int(time.time() * 1000)
    quote["ageMs"] = now - quote["exchangeTimeMs"]
    return {"ok": True, "symbol": symbol, "asOf": iso(now), "quote": quote}


def account_payload(symbol):
    filters = instrument_filters(symbol)
    state = _desk.account_state(symbol, filters, marketdata.book(symbol))
    state["asOf"] = iso(state["asOfMs"])
    return {"ok": True, "account": state}


REQUIRED_ORDER_FIELDS = [
    "symbol", "direction", "entryPrice", "stopPrice", "targetPrice",
    "atr14", "strategy", "strategyScore", "runnerUpScore",
]


def order_request(source):
    missing = [field for field in REQUIRED_ORDER_FIELDS if source.get(field) in (None, "")]
    if missing:
        raise ValueError("missing required fields: " + ", ".join(missing))
    return {
        "symbol": str(source["symbol"]),
        "direction": str(source["direction"]),
        "entryPrice": float(source["entryPrice"]),
        "stopPrice": float(source["stopPrice"]),
        "targetPrice": float(source["targetPrice"]),
        "atr14": float(source["atr14"]),
        "strategy": str(source["strategy"]),
        "strategyScore": float(source["strategyScore"]),
        "runnerUpScore": float(source["runnerUpScore"]),
        "idempotencyKey": str(source.get("idempotencyKey", "")),
    }


def preview_payload(params):
    request = order_request(params)
    filters = instrument_filters(request["symbol"])
    evaluation = _desk.evaluate(request, filters, marketdata.book(request["symbol"]))
    return {"ok": True, "preview": evaluation}


def submit_payload(body):
    request = order_request(body)
    if not request["idempotencyKey"]:
        raise ValueError("missing required fields: idempotencyKey")
    filters = instrument_filters(request["symbol"])
    result = _desk.submit(request, filters, marketdata.book(request["symbol"]))
    return {"ok": True, **result}


class Handler(BaseHTTPRequestHandler):
    server_version = "TradingDeskSidecar/1.0"

    def do_GET(self):
        route = urlparse(self.path)
        params = {key: values[0] for key, values in parse_qs(route.query).items()}
        symbol = params.get("symbol", _desk.config["instrument"])
        routes = {
            "/health": lambda: {"ok": True, "mode": _desk.config["mode"],
                                "instrument": _desk.config["instrument"]},
            "/analysis": lambda: analysis(symbol),
            "/quote": lambda: quote_payload(symbol),
            "/account": lambda: account_payload(symbol),
            "/order-preview": lambda: preview_payload(params),
        }
        self._dispatch(routes.get(route.path))

    def do_POST(self):
        route = urlparse(self.path)
        if route.path != "/orders":
            return self._dispatch(None)
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        try:
            body = json.loads(raw)
        except json.JSONDecodeError as error:
            return self._respond({"ok": False, "error": f"body was not JSON: {error}"})
        self._dispatch(lambda: submit_payload(body))

    def _dispatch(self, handler):
        if handler is None:
            return self._respond({"ok": False, "error": f"unknown endpoint {self.path}"})
        try:
            self._respond(handler())
        except marketdata.DataError as error:
            self._respond({"ok": False, "error": f"market data unavailable: {error}",
                           "reason": "DATA_INVALID"})
        except (ValueError, KeyError, TypeError) as error:
            self._respond({"ok": False, "error": f"invalid request: {error}"})

    def _respond(self, payload):
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, fmt, *args):
        print(f"  {self.address_string()} {fmt % args}", flush=True)


def main():
    global _desk
    parser = argparse.ArgumentParser(description="Trading desk sidecar")
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--config", default="desk-config.json")
    arguments = parser.parse_args()
    _desk = broker.Desk(broker.load_config(arguments.config))
    print(f"Trading desk on :{arguments.port} — {_desk.config['mode']} "
          f"{_desk.config['instrument']} equity {_desk.config['startingEquity']}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", arguments.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
