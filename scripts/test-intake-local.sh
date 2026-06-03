#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"
PORT="${PORT:-8081}"
LOG_FILE="${LOG_FILE:-/tmp/legal-gate-intake-orchestrator.log}"

cd "$ROOT_DIR"

$MVN -pl services/intake-orchestrator test
$MVN -pl services/intake-orchestrator -DskipTests package

java \
  -Dserver.port="$PORT" \
  -jar services/intake-orchestrator/target/intake-orchestrator-*.jar > "$LOG_FILE" 2>&1 &
PID=$!
trap 'kill "$PID" 2>/dev/null || true' EXIT

READY=false
for _ in {1..40}; do
  if curl -fsS "http://localhost:${PORT}/actuator/health" >/dev/null 2>&1; then
    READY=true
    break
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "Intake orchestrator exited before becoming healthy. Log follows:" >&2
    cat "$LOG_FILE" >&2
    exit 1
  fi
  sleep 0.5
done

if [ "$READY" != "true" ]; then
  echo "Intake orchestrator did not become healthy on port ${PORT}. Log follows:" >&2
  cat "$LOG_FILE" >&2
  exit 1
fi

curl -fsS "http://localhost:${PORT}/api/status" | jq -e '.service == "legal-gate-intake-orchestrator" and .status == "UP"' >/dev/null

curl -fsS \
  -X PUT \
  -H 'Content-Type: application/json' \
  -d '{"urgentKeywords":["audiencia","captura"],"consultationWindows":["LUN-VIE 09:00-13:00"],"destinationEmail":"intake@familia.test"}' \
  "http://localhost:${PORT}/api/tenants/familia-legal/settings" \
  | jq -e '.tenantId == "familia-legal" and .urgentKeywords[0] == "audiencia"' >/dev/null

CREATE_STATUS="$(curl -sS -o /tmp/intake-create.json -w '%{http_code}' \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"María Pérez","clientEmail":"maria@example.com","summary":"Tengo una audiencia mañana y necesito orientación aunque no conozco términos legales.","preferredWindow":"LUN-VIE 09:00-13:00"}' \
  "http://localhost:${PORT}/api/tenants/familia-legal/consultations")"
test "$CREATE_STATUS" = "201"
jq -e '.status == "RECEIVED" and .urgency == "URGENT" and .notifications.emailQueued == true and .notifications.calendarUpdateQueued == true' /tmp/intake-create.json >/dev/null

curl -fsS "http://localhost:${PORT}/api/admin/tenants/familia-legal/consultations" \
  | jq -e '.tenantId == "familia-legal" and (.consultations | length) == 1' >/dev/null

echo "Intake orchestrator local verification passed on port ${PORT}."
