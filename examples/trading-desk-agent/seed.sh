#!/usr/bin/env bash
# Creates and publishes the five trading desk tools, then creates the Trading Desk agent
# as a draft.
#
# The agent is left as a DRAFT on purpose. Draft tests mock every non-READ tool, so you
# can exercise the whole decision path — including the order call — before anything can
# reach even the paper broker. Pass --publish to publish it too.
#
# Usage:
#   ./seed.sh                     # against localhost:8080, tenant account-123
#   ./seed.sh --publish
#   MANAGEMENT_URL=http://host:8080 LICENSE_CODE=acct-9 ./seed.sh

set -euo pipefail

MANAGEMENT_URL="${MANAGEMENT_URL:-http://localhost:8080}"
LICENSE_CODE="${LICENSE_CODE:-account-123}"
USER_ID="${USER_ID:-user-123}"
ROLES="${ROLES:-AGENT_EDITOR,AGENT_PUBLISHER,AGENT_ADMIN}"
DESK_URL="${DESK_URL:-http://localhost:8090}"
PUBLISH_AGENT="no"
[ "${1:-}" = "--publish" ] && PUBLISH_AGENT="yes"

DIR="$(cd "$(dirname "$0")" && pwd)"

hdr=(
  -H "Content-Type: application/json"
  -H "X-Agent-License-Code: ${LICENSE_CODE}"
  -H "X-Agent-User-Id: ${USER_ID}"
  -H "X-Agent-Roles: ${ROLES}"
  -H "X-Agent-Change-Reason: Seeded from examples/trading-desk-agent"
)

# Prints the .id of a JSON body, or exits with the server's message.
read_id() {
  python3 -c '
import json, sys
raw = sys.stdin.read()
try:
    body = json.loads(raw)
except Exception:
    sys.exit("Not JSON: " + raw[:400])
if isinstance(body, dict) and body.get("id"):
    print(body["id"])
else:
    sys.exit("No id in response: " + json.dumps(body)[:400])
'
}

create_tool() {
  local file="$1" name="$2"
  echo "→ creating tool ${name}" >&2
  curl -sS -X POST "${MANAGEMENT_URL}/api/v1/tools" "${hdr[@]}" \
    --data-binary "@${DIR}/${file}" | read_id
}

publish_tool() {
  local id="$1" name="$2"
  echo "→ publishing tool ${name}" >&2
  curl -sS -o /dev/null -w '   status %{http_code}\n' \
    -X PATCH "${MANAGEMENT_URL}/api/v1/tools/${id}/status" "${hdr[@]}" \
    -d '{"status":"PUBLISHED"}'
}

if ! curl -sS -m 3 -o /dev/null "${DESK_URL}/health" 2>/dev/null; then
  echo "!  The desk sidecar is not answering on ${DESK_URL}." >&2
  echo "   Seeding will still work, but every tool call will fail until you start it:" >&2
  echo "     python3 ${DIR}/desk.py --port 8090" >&2
  echo >&2
fi

names=(
  "tool-market-analysis.json:market_get_multi_timeframe_analysis"
  "tool-market-quote.json:market_get_quote"
  "tool-broker-account-state.json:broker_get_account_state"
  "tool-broker-preview-order.json:broker_preview_order"
  "tool-broker-submit-order.json:broker_submit_bracket_order"
)

summary=""
for entry in "${names[@]}"; do
  file="${entry%%:*}"
  name="${entry##*:}"
  id="$(create_tool "${file}" "${name}")"
  publish_tool "${id}" "${name}"
  summary="${summary}  ${name}  ${id}  PUBLISHED"$'\n'
done

echo "→ creating agent Trading Desk" >&2
AGENT_ID="$(curl -sS -X POST "${MANAGEMENT_URL}/api/v1/agents" "${hdr[@]}" \
  --data-binary "@${DIR}/agent-trading-desk.json" | read_id)"

if [ "${PUBLISH_AGENT}" = "yes" ]; then
  echo "→ publishing agent" >&2
  curl -sS -o /dev/null -w '   status %{http_code}\n' \
    -X PATCH "${MANAGEMENT_URL}/api/v1/agents/${AGENT_ID}/status" "${hdr[@]}" \
    -d '{"status":"PUBLISHED"}'
fi

cat <<SUMMARY

Seeded.
${summary}  Trading Desk  ${AGENT_ID}  $([ "${PUBLISH_AGENT}" = "yes" ] && echo PUBLISHED || echo DRAFT)

Next: open Agent Studio, go to Draft test, paste the agent id, and run

  task:  Evaluate BTCUSDT and either submit one validated order or return NO_TRADE.
  input: {"symbol": "BTCUSDT"}

Reminder: the runtime needs the desk host allowlisted, or every call fails with
"HTTP tool host is not allowlisted".

  AGENT_HTTP_TOOL_ALLOWED_HOSTS=host.docker.internal

The sidecar must be running and able to reach api.binance.com:

  python3 examples/trading-desk-agent/desk.py --port 8090
SUMMARY
