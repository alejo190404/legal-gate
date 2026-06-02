#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"
PORT="${PORT:-8080}"
API_KEY="${GATEWAY_API_KEY:-local-dev-gateway-key}"
SERVICE_TOKEN="${GATEWAY_FORWARDED_TOKEN:-local-dev-service-token}"
LOG_FILE="${LOG_FILE:-/tmp/legal-gate-gateway.log}"

cd "$ROOT_DIR"

$MVN -pl services/gateway test
$MVN -pl services/gateway -DskipTests package

java \
  -Dserver.port="$PORT" \
  -Dspring.profiles.active=local \
  -Dlegalgate.gateway.api-key="$API_KEY" \
  -Dlegalgate.gateway.forwarded-token="$SERVICE_TOKEN" \
  -jar services/gateway/target/gateway-*.jar > "$LOG_FILE" 2>&1 &
PID=$!
trap 'kill "$PID" 2>/dev/null || true' EXIT

READY=false
for _ in {1..40}; do
  if curl -fsS "http://localhost:${PORT}/actuator/health" >/dev/null 2>&1; then
    READY=true
    break
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "Gateway process exited before becoming healthy. Log follows:" >&2
    cat "$LOG_FILE" >&2
    exit 1
  fi
  sleep 0.5
done

if [ "$READY" != "true" ]; then
  echo "Gateway did not become healthy on port ${PORT}. Log follows:" >&2
  cat "$LOG_FILE" >&2
  exit 1
fi

curl -fsS "http://localhost:${PORT}/api/status" | jq -e '.service == "legal-gate-gateway" and .status == "UP"' >/dev/null

UNAUTH_STATUS="$(curl -sS -o /tmp/gateway-unauth.json -w '%{http_code}' "http://localhost:${PORT}/api/backend/cases")"
test "$UNAUTH_STATUS" = "401"
jq -e '.error == "unauthorized"' /tmp/gateway-unauth.json >/dev/null

FALLBACK_STATUS="$(curl -sS -o /tmp/gateway-fallback.json -w '%{http_code}' -H "X-Gateway-Api-Key: ${API_KEY}" "http://localhost:${PORT}/api/backend/cases")"
test "$FALLBACK_STATUS" = "503"
jq -e '.error == "service_unavailable" and .service == "backend"' /tmp/gateway-fallback.json >/dev/null

echo "Gateway local verification passed on port ${PORT}."
