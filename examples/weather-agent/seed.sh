#!/usr/bin/env bash
# Creates and publishes the weather tools, then creates the Weather Reporter agent as a draft.
#
# The agent is left as a DRAFT on purpose so you can exercise it through the draft-test
# endpoint first. Pass --publish to publish it as well.
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
PUBLISH_AGENT="no"
[ "${1:-}" = "--publish" ] && PUBLISH_AGENT="yes"

DIR="$(cd "$(dirname "$0")" && pwd)"

hdr=(
  -H "Content-Type: application/json"
  -H "X-Agent-License-Code: ${LICENSE_CODE}"
  -H "X-Agent-User-Id: ${USER_ID}"
  -H "X-Agent-Roles: ${ROLES}"
  -H "X-Agent-Change-Reason: Seeded from examples/weather-agent"
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

GEO_ID="$(create_tool tool-weather-geocode.json weather_geocode_place)"
FC_ID="$(create_tool tool-weather-forecast.json weather_get_forecast)"
publish_tool "${GEO_ID}" weather_geocode_place
publish_tool "${FC_ID}" weather_get_forecast

echo "→ creating agent Weather Reporter" >&2
AGENT_ID="$(curl -sS -X POST "${MANAGEMENT_URL}/api/v1/agents" "${hdr[@]}" \
  --data-binary "@${DIR}/agent-weather-reporter.json" | read_id)"

if [ "${PUBLISH_AGENT}" = "yes" ]; then
  echo "→ publishing agent" >&2
  curl -sS -o /dev/null -w '   status %{http_code}\n' \
    -X PATCH "${MANAGEMENT_URL}/api/v1/agents/${AGENT_ID}/status" "${hdr[@]}" \
    -d '{"status":"PUBLISHED"}'
fi

cat <<SUMMARY

Seeded.
  weather_geocode_place  ${GEO_ID}  PUBLISHED
  weather_get_forecast   ${FC_ID}  PUBLISHED
  Weather Reporter       ${AGENT_ID}  $([ "${PUBLISH_AGENT}" = "yes" ] && echo PUBLISHED || echo DRAFT)

Next: open Agent Studio, go to Draft test, paste the agent id, and run

  task:  Give me a three day weather report for Bengaluru.
  input: {}

Reminder: the runtime needs both hosts allowlisted, or every call fails with
"HTTP tool host is not allowlisted".

  AGENT_HTTP_TOOL_ALLOWED_HOSTS=api.open-meteo.com,geocoding-api.open-meteo.com
SUMMARY
