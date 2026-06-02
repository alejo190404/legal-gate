#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"
PORT="${PORT:-8080}"

export GATEWAY_API_KEY="${GATEWAY_API_KEY:-local-dev-gateway-key}"
export GATEWAY_FORWARDED_TOKEN="${GATEWAY_FORWARDED_TOKEN:-local-dev-service-token}"

cd "$ROOT_DIR"
$MVN -pl services/gateway -DskipTests package

exec java \
  -Dserver.port="$PORT" \
  -Dspring.profiles.active=local \
  -jar services/gateway/target/gateway-*.jar
