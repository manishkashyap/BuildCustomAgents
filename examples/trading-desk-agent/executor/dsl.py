"""
Evaluates the strategy catalogue against an analysis payload.

The operators are code, tested once. The conditions are data in strategies.json, which is what
lets the catalogue be reviewed, diffed and tuned without touching this file.

Every evaluation reports not just whether a strategy matched, but which condition failed first and
what the actual value was. That detail is the whole point of the daily review: it is the difference
between "consider loosening the volume filter" and "trend_continuation failed 41 times solely on
volume.ratioToAverage, median 1.09 against a threshold of 1.2".
"""

LONG = "LONG"
SHORT = "SHORT"


class CatalogueError(Exception):
    """The catalogue is malformed. The executor must not trade on a half-edited rule set."""


def field(node, path):
    """Reads a dotted path, with numeric segments indexing into lists. None when absent."""
    if node is None or not path:
        return None
    current = node
    for segment in str(path).split("."):
        if isinstance(current, list):
            if not segment.isdigit() or int(segment) >= len(current):
                return None
            current = current[int(segment)]
        elif isinstance(current, dict):
            if segment not in current:
                return None
            current = current[segment]
        else:
            return None
    return current


def _numeric(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _directional(condition, direction, key):
    """Picks the long or short variant of a condition value, falling back to a shared one."""
    if direction == LONG and "long" in condition:
        return condition["long"]
    if direction == SHORT and "short" in condition:
        return condition["short"]
    return condition.get(key)


def _zones(node, direction, min_touches):
    """Level zones on the side a trade would lean on, filtered by how often they were tested."""
    levels = field(node, "levels") or {}
    side = "support" if direction == LONG else "resistance"
    return [z["level"] for z in (levels.get(side) or [])
            if _numeric(z.get("level")) and (z.get("touches") or 0) >= min_touches]


# --- operators ------------------------------------------------------------------------------
# Each returns (passed, actual). `actual` is recorded for the review, so a near miss carries the
# value that missed rather than only the fact that it did.

def _op_threshold_simple(node, condition, direction, comparison):
    actual = field(node, condition["field"])
    if not _numeric(actual):
        return False, actual
    limit = condition.get("value")
    if limit is None:
        limit = _directional(condition, direction, "value")
    if not _numeric(limit):
        raise CatalogueError(f"{condition['id']}: {comparison} needs a numeric value")
    passed = {
        "gte": actual >= limit, "lte": actual <= limit,
        "gt": actual > limit, "lt": actual < limit,
    }[comparison]
    return passed, actual


def _op_between(node, condition, direction):
    actual = field(node, condition["field"])
    bounds = _directional(condition, direction, "value")
    if not isinstance(bounds, list) or len(bounds) != 2:
        raise CatalogueError(f"{condition['id']}: between needs a two-element range")
    if not _numeric(actual):
        return False, actual
    return bounds[0] <= actual <= bounds[1], actual


def _op_threshold(node, condition, direction):
    """A per-direction operator and value, for conditions that flip comparison as well as bound."""
    spec = _directional(condition, direction, "value")
    if not isinstance(spec, dict):
        raise CatalogueError(f"{condition['id']}: threshold needs an op and value per direction")
    actual = field(node, condition["field"])
    if not _numeric(actual):
        return False, actual
    limit = spec["value"]
    passed = {"gte": actual >= limit, "lte": actual <= limit,
              "gt": actual > limit, "lt": actual < limit}[spec["op"]]
    return passed, actual


def _op_compare_field(node, condition, direction):
    left = field(node, condition["left"])
    right_path = condition["right"]
    if direction == SHORT and condition.get("shortRight"):
        right_path = condition["shortRight"]
    right = field(node, right_path)
    comparison = condition.get("comparison") or _directional(condition, direction, "comparison")
    if comparison not in ("gt", "lt", "gte", "lte"):
        raise CatalogueError(f"{condition['id']}: compare_field needs a comparison")
    if not (_numeric(left) and _numeric(right)):
        return False, left
    passed = {"gt": left > right, "lt": left < right,
              "gte": left >= right, "lte": left <= right}[comparison]
    return passed, left


def _op_ordered(node, condition, direction):
    values = [field(node, path) for path in condition["fields"]]
    if not all(_numeric(v) for v in values):
        return False, values
    order = _directional(condition, direction, "order")
    if order == "descending":
        passed = all(values[i] > values[i + 1] for i in range(len(values) - 1))
    elif order == "ascending":
        passed = all(values[i] < values[i + 1] for i in range(len(values) - 1))
    else:
        raise CatalogueError(f"{condition['id']}: ordered needs ascending or descending")
    return passed, values


def _op_is_true(node, condition, direction):
    path = _directional(condition, direction, "field")
    actual = field(node, path)
    return actual is True, actual


def _op_equals(node, condition, direction):
    expected = _directional(condition, direction, "value")
    actual = field(node, condition["field"])
    return actual == expected, actual


def _op_not_in(node, condition, _direction):
    actual = field(node, condition["field"])
    return actual not in condition["values"], actual


def _op_sign_is(node, condition, direction):
    actual = field(node, condition["field"])
    wanted = _directional(condition, direction, "sign")
    if not _numeric(actual):
        return False, actual
    return (actual > 0) if wanted == "positive" else (actual < 0), actual


def _op_moved_toward(node, condition, _direction):
    """The value has moved closer to a target than it was, rather than further away."""
    now = field(node, condition["field"])
    before = field(node, condition["from"])
    target = condition["target"]
    if not (_numeric(now) and _numeric(before)):
        return False, now
    return abs(now - target) < abs(before - target), now


def _op_turned_back(node, condition, direction):
    now = field(node, condition["field"])
    before = field(node, condition["from"])
    if not (_numeric(now) and _numeric(before)):
        return False, now
    wanted = _directional(condition, direction, "turn")
    return (now > before) if wanted == "up" else (now < before), now


def _op_within_atr_of(node, condition, direction):
    """Price sits within a multiple of ATR of a moving average, or of a well-tested zone."""
    actual = field(node, condition["field"])
    atr = field(node, "volatility.atr14")
    if not (_numeric(actual) and _numeric(atr)):
        return False, actual
    tolerance = atr * float(condition.get("multiple", 1.0))
    anchors = [field(node, path) for path in condition.get("anchors", [])]
    if condition.get("zones"):
        anchors += _zones(node, direction, condition.get("minTouches", 1))
    distances = [abs(actual - a) for a in anchors if _numeric(a)]
    if not distances:
        return False, actual
    return min(distances) <= tolerance, round(min(distances), 4)


def _op_zone_near_extreme(node, condition, direction):
    """A tested zone sits close to the range extreme that was broken."""
    extreme = field(node, _directional(condition, direction, "extreme"))
    atr = field(node, "volatility.atr14")
    if not (_numeric(extreme) and _numeric(atr)):
        return False, extreme
    tolerance = atr * float(condition.get("multiple", 0.5))
    zones = _zones(node, direction, condition.get("minTouches", 1))
    distances = [abs(extreme - z) for z in zones]
    if not distances:
        return False, None
    return min(distances) <= tolerance, round(min(distances), 4)


def _op_band_extreme(node, condition, direction):
    actual = field(node, condition["field"])
    limit = _directional(condition, direction, "value")
    if not _numeric(actual):
        return False, actual
    return (actual <= limit) if direction == LONG else (actual >= limit), actual


def _op_ratio(node, condition, _direction, comparison):
    left = field(node, condition["left"])
    right = field(node, condition["right"])
    factor = float(condition["factor"])
    if not (_numeric(left) and _numeric(right)):
        return False, left
    passed = left >= right * factor if comparison == "gte" else left <= right * factor
    return passed, left


def _op_not_opposed(node, condition, direction):
    """The hourly bias must not point against the trade; neutral is acceptable."""
    plus = field(node, "trendStrength.plusDi")
    minus = field(node, "trendStrength.minusDi")
    if not (_numeric(plus) and _numeric(minus)):
        return False, None
    bias = LONG if plus > minus else SHORT
    return bias == direction or abs(plus - minus) < 2.0, bias


def _op_regime_not(_node, condition, direction, regime=None):
    forbidden = _directional(condition, direction, "regime")
    return regime != forbidden, regime


def _op_spread_ok(_node, _condition, _direction, quote=None, max_spread_percent=None):
    actual = (quote or {}).get("spreadPercent")
    if not _numeric(actual) or max_spread_percent is None:
        return False, actual
    return actual <= max_spread_percent, actual


def evaluate_condition(condition, payload, direction, regime, max_spread_percent):
    """Evaluates one condition. Returns (passed, actual)."""
    timeframe = condition.get("tf")
    node = field(payload, f"timeframes.{timeframe}") if timeframe else None
    if timeframe and node is None:
        return False, None
    op = condition["op"]

    if op in ("gte", "lte", "gt", "lt"):
        return _op_threshold_simple(node, condition, direction, op)
    if op == "between":
        return _op_between(node, condition, direction)
    if op == "threshold":
        return _op_threshold(node, condition, direction)
    if op == "compare_field":
        return _op_compare_field(node, condition, direction)
    if op == "ordered":
        return _op_ordered(node, condition, direction)
    if op == "is_true":
        return _op_is_true(node, condition, direction)
    if op == "equals":
        return _op_equals(node, condition, direction)
    if op == "not_in":
        return _op_not_in(node, condition, direction)
    if op == "sign_is":
        return _op_sign_is(node, condition, direction)
    if op == "moved_toward":
        return _op_moved_toward(node, condition, direction)
    if op == "turned_back":
        return _op_turned_back(node, condition, direction)
    if op in ("within_atr_of", "beyond_swing"):
        if op == "beyond_swing":
            swing = field(node, _directional(condition, direction, "swing"))
            close = field(node, condition["field"])
            if not (_numeric(swing) and _numeric(close)):
                return False, close
            return (close > swing) if direction == LONG else (close < swing), close
        return _op_within_atr_of(node, condition, direction)
    if op == "zone_near_extreme":
        return _op_zone_near_extreme(node, condition, direction)
    if op == "band_extreme":
        return _op_band_extreme(node, condition, direction)
    if op == "ratio_gte":
        return _op_ratio(node, condition, direction, "gte")
    if op == "ratio_lte":
        return _op_ratio(node, condition, direction, "lte")
    if op == "not_opposed":
        return _op_not_opposed(node, condition, direction)
    if op == "regime_not":
        return _op_regime_not(node, condition, direction, regime=regime)
    if op == "spread_ok":
        return _op_spread_ok(node, condition, direction,
                             quote=field(payload, "quote"),
                             max_spread_percent=max_spread_percent)
    raise CatalogueError(f"{condition.get('id')}: unknown operator {op}")


def evaluate_strategy(strategy, payload, direction, regime, max_spread_percent):
    """
    Evaluates one strategy in one direction.

    Every condition is evaluated, not just up to the first failure, so the review can see that a
    strategy failed on one condition rather than five — which is what distinguishes a threshold
    worth tuning from a setup that was never close.
    """
    if regime not in strategy["requiredRegime"]:
        return {"name": strategy["name"], "direction": direction, "applicable": False,
                "failures": [{"id": "required_regime", "actual": regime}], "passed": []}
    failures, passed = [], []
    for condition in strategy["conditions"]:
        ok, actual = evaluate_condition(condition, payload, direction, regime, max_spread_percent)
        (passed if ok else failures).append({"id": condition["id"], "actual": actual})
    return {"name": strategy["name"], "direction": direction,
            "applicable": not failures, "failures": failures, "passed": passed}


def evaluate_catalogue(catalogue, payload, regime, max_spread_percent, allow_long, allow_short):
    """
    Every strategy in every permitted direction.

    A direction the desk forbids is still evaluated and reported as skipped rather than dropped, so
    the ledger measures the opportunity cost of the restriction instead of hiding it.
    """
    applicable, near_misses, skipped = [], [], []
    for strategy in catalogue["strategies"]:
        for direction in strategy.get("directions", [LONG, SHORT]):
            allowed = allow_long if direction == LONG else allow_short
            result = evaluate_strategy(strategy, payload, direction, regime, max_spread_percent)
            if not allowed:
                if result["applicable"]:
                    skipped.append({**result, "reason": "DIRECTION_DISALLOWED"})
                continue
            if result["applicable"]:
                applicable.append(result)
            elif len(result["failures"]) <= 2 and result["failures"][0]["id"] != "required_regime":
                near_misses.append(result)
    return {"applicable": applicable, "nearMisses": near_misses, "skipped": skipped}


def validate_catalogue(catalogue):
    """
    Fails loudly on a malformed catalogue.

    Called on every tick because the catalogue is hand-editable: reading it halfway through an edit
    must stop the tick, not produce a trade from a partial rule set.
    """
    if not isinstance(catalogue, dict) or not isinstance(catalogue.get("strategies"), list):
        raise CatalogueError("catalogue must be an object with a strategies array")
    if not catalogue["strategies"]:
        raise CatalogueError("catalogue contains no strategies")
    seen = set()
    for strategy in catalogue["strategies"]:
        name = strategy.get("name")
        if not name or name in seen:
            raise CatalogueError(f"strategy name missing or duplicated: {name}")
        seen.add(name)
        if not strategy.get("requiredRegime"):
            raise CatalogueError(f"{name}: requiredRegime must not be empty")
        if not strategy.get("conditions"):
            raise CatalogueError(f"{name}: conditions must not be empty")
        for condition in strategy["conditions"]:
            if not condition.get("id") or not condition.get("op"):
                raise CatalogueError(f"{name}: every condition needs an id and an op")
    return catalogue
