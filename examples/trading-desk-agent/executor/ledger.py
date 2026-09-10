"""
Where every tick is recorded, traded or not.

Two writers behind one interface. SheetsLedger is the production path: one Google Sheet you can read
and edit, append-only so there is no read-modify-write to lose. JsonlLedger is for dry runs and
tests, because a local file needs no credentials and makes the executor verifiable on its own.

The row is the same either way, so switching writers cannot change what is recorded.
"""

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

# The order here is the column order in the sheet. Appending a new field at the end is safe;
# reordering is not, because existing rows would no longer line up with their headers.
COLUMNS = [
    "tick_at",
    "symbol",
    "setup_timestamp",
    "catalogue_version",
    "regime",
    "daily_bias",
    "hourly_bias",
    "decision",
    "reason_code",
    "selected_strategy",
    "direction",
    "strategy_score",
    "runner_up_score",
    "applicable_strategies",
    "near_misses",
    "skipped_directions",
    "entry_price",
    "stop_price",
    "target_price",
    "quantity",
    "risk_amount",
    "reward_to_risk",
    "stop_basis",
    "risk_check_failures",
    "idempotency_key",
    "broker_order_id",
    "order_status",
    "fill_price",
    "duration_ms",
    # The indicator values the strategies actually read, flattened, so a proposed threshold change
    # can be replayed against history without keeping the whole payload.
    "d1_adx", "d1_rsi", "d1_structure",
    "h4_adx", "h4_rsi", "h4_structure",
    "h1_adx", "h1_rsi", "h1_macd_hist",
    # The three readings that decide the regime. Without them the historical ticks could not be
    # re-classified after the weak-trend fix, so the impact of that change was unmeasurable.
    "h1_close", "h1_ema50", "h1_ema200", "h1_structure",
    "m15_adx", "m15_rsi", "m15_macd_hist", "m15_vol_ratio", "m15_atr",
    "m5_rsi", "m5_macd_hist", "m5_vol_ratio",
    "quote_bid", "quote_ask", "quote_spread_pct",
]


def utc_now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _get(node, path, default=""):
    current = node
    for segment in path.split("."):
        if not isinstance(current, dict) or segment not in current:
            return default
        current = current[segment]
    return default if current is None else current


def build_row(*, tick_at, symbol, payload, catalogue_version, regime, daily_bias, hourly_bias,
              decision, reason_code, selection, evaluation, preview, order, duration_ms):
    """One flat row. Every field is a scalar so the sheet stays readable and sortable."""
    tf = payload.get("timeframes", {})
    quote = payload.get("quote", {}) or {}
    selected = selection or {}
    plan = preview or {}
    placed = order or {}

    def names(results):
        return ", ".join(f"{r['name']}:{r['direction']}" for r in results) or ""

    def misses(results):
        # strategy:direction:first-failing-condition=actual — the field the review reads.
        parts = []
        for r in results:
            first = r["failures"][0]
            parts.append(f"{r['name']}:{r['direction']}:{first['id']}={first['actual']}")
        return " | ".join(parts)

    return {
        "tick_at": tick_at,
        "symbol": symbol,
        "setup_timestamp": _get(tf, "15m.lastClosedAtMs", ""),
        "catalogue_version": catalogue_version,
        "regime": regime,
        "daily_bias": daily_bias,
        "hourly_bias": hourly_bias,
        "decision": decision,
        "reason_code": reason_code,
        "selected_strategy": selected.get("name", ""),
        "direction": selected.get("direction", ""),
        "strategy_score": selected.get("score", ""),
        "runner_up_score": selected.get("runnerUp", ""),
        "applicable_strategies": names(evaluation.get("applicable", [])),
        "near_misses": misses(evaluation.get("nearMisses", [])),
        "skipped_directions": names(evaluation.get("skipped", [])),
        "entry_price": plan.get("entryPrice", ""),
        "stop_price": plan.get("stopPrice", ""),
        "target_price": plan.get("targetPrice", ""),
        "quantity": plan.get("quantity", ""),
        "risk_amount": plan.get("riskAmount", ""),
        "reward_to_risk": plan.get("rewardToRiskAfterCosts", ""),
        "stop_basis": selected.get("stopBasis", ""),
        "risk_check_failures": ", ".join(plan.get("failures", []) or []),
        "idempotency_key": placed.get("idempotencyKey", ""),
        "broker_order_id": placed.get("brokerOrderId", "") or "",
        "order_status": placed.get("status", ""),
        "fill_price": placed.get("fillPrice", "") or "",
        "duration_ms": duration_ms,
        "d1_adx": _get(tf, "1d.trendStrength.adx14"),
        "d1_rsi": _get(tf, "1d.momentum.rsi14"),
        "d1_structure": _get(tf, "1d.structure.classification"),
        "h4_adx": _get(tf, "4h.trendStrength.adx14"),
        "h4_rsi": _get(tf, "4h.momentum.rsi14"),
        "h4_structure": _get(tf, "4h.structure.classification"),
        "h1_adx": _get(tf, "1h.trendStrength.adx14"),
        "h1_rsi": _get(tf, "1h.momentum.rsi14"),
        "h1_macd_hist": _get(tf, "1h.momentum.macdHistogram"),
        "h1_close": _get(tf, "1h.close"),
        "h1_ema50": _get(tf, "1h.movingAverages.ema50"),
        "h1_ema200": _get(tf, "1h.movingAverages.ema200"),
        "h1_structure": _get(tf, "1h.structure.classification"),
        "m15_adx": _get(tf, "15m.trendStrength.adx14"),
        "m15_rsi": _get(tf, "15m.momentum.rsi14"),
        "m15_macd_hist": _get(tf, "15m.momentum.macdHistogram"),
        "m15_vol_ratio": _get(tf, "15m.volume.ratioToAverage"),
        "m15_atr": _get(tf, "15m.volatility.atr14"),
        "m5_rsi": _get(tf, "5m.momentum.rsi14"),
        "m5_macd_hist": _get(tf, "5m.momentum.macdHistogram"),
        "m5_vol_ratio": _get(tf, "5m.volume.ratioToAverage"),
        "quote_bid": quote.get("bid", ""),
        "quote_ask": quote.get("ask", ""),
        "quote_spread_pct": quote.get("spreadPercent", ""),
    }


class JsonlLedger:
    """
    Appends rows to a local JSONL file.

    For dry runs and tests. Production uses SheetsLedger; this exists so the executor can be
    verified end to end without credentials, which is the difference between a tested tick loop and
    one that has only ever been read.
    """

    def __init__(self, path):
        self.path = path

    def append(self, row):
        os.makedirs(os.path.dirname(os.path.abspath(self.path)), exist_ok=True)
        with open(self.path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps({key: row[key] for key in COLUMNS}) + "\n")
        return {"backend": "jsonl", "path": self.path}

    def describe(self):
        return f"jsonl:{self.path}"


class SheetsLedger:
    """
    Appends rows to a tab in a Google Sheet.

    Append-only: values.append is a single atomic call, so concurrent ticks cannot lose a row the
    way a read-modify-write on a state tab could.

    Needs a service account with the spreadsheet shared to its client_email. Auth uses google-auth
    rather than being hand-rolled, because RS256 signing is not in the Python standard library.
    """

    SCOPES = ["https://www.googleapis.com/auth/spreadsheets"]

    def __init__(self, spreadsheet_id, tab, service_account_file):
        self.spreadsheet_id = spreadsheet_id
        self.tab = tab
        self.service_account_file = service_account_file
        self._credentials = None

    def _token(self):
        try:
            from google.oauth2 import service_account
            import google.auth.transport.requests
        except ImportError as error:  # pragma: no cover - depends on the deployment environment
            raise RuntimeError(
                "SheetsLedger needs google-auth: pip install -r executor/requirements.txt"
            ) from error
        if self._credentials is None:
            self._credentials = service_account.Credentials.from_service_account_file(
                self.service_account_file, scopes=self.SCOPES)
        if not self._credentials.valid:
            self._credentials.refresh(google.auth.transport.requests.Request())
        return self._credentials.token

    def _call(self, method, url, body=None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Authorization", f"Bearer {self._token()}")
        request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")[:400]
            raise RuntimeError(f"Sheets {method} failed with {error.code}: {detail}") from error

    def ensure_header(self):
        """Writes the header row once, so a fresh spreadsheet is usable without hand setup."""
        target = urllib.parse.quote(f"{self.tab}!A1:1")
        url = (f"https://sheets.googleapis.com/v4/spreadsheets/{self.spreadsheet_id}"
               f"/values/{target}")
        existing = self._call("GET", url).get("values") or []
        if existing and existing[0]:
            return False
        self._call("PUT", f"{url}?valueInputOption=RAW", {"values": [COLUMNS]})
        return True

    def append(self, row):
        target = urllib.parse.quote(f"{self.tab}!A1")
        url = (f"https://sheets.googleapis.com/v4/spreadsheets/{self.spreadsheet_id}"
               f"/values/{target}:append"
               f"?valueInputOption=RAW&insertDataOption=INSERT_ROWS")
        values = [["" if row[key] is None else row[key] for key in COLUMNS]]
        result = self._call("POST", url, {"values": values})
        return {"backend": "sheets", "range": _get(result, "updates.updatedRange", "")}

    def describe(self):
        return f"sheets:{self.spreadsheet_id}/{self.tab}"


def from_env():
    """
    Chooses the writer from the environment.

    LEDGER=sheets needs SHEET_ID, GOOGLE_APPLICATION_CREDENTIALS and optionally SHEET_TAB.
    Anything else writes JSONL, which is what a dry run should do.
    """
    if os.environ.get("LEDGER", "").lower() == "sheets":
        spreadsheet_id = os.environ.get("SHEET_ID")
        key_file = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
        if not spreadsheet_id or not key_file:
            raise RuntimeError(
                "LEDGER=sheets requires SHEET_ID and GOOGLE_APPLICATION_CREDENTIALS")
        return SheetsLedger(spreadsheet_id, os.environ.get("SHEET_TAB", "Ticks"), key_file)
    return JsonlLedger(os.environ.get("LEDGER_PATH", "ledger/ticks.jsonl"))
