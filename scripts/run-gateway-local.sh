#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"
PORT="${PORT:-8080}"

export GATEWAY_FORWARDED_TOKEN="${GATEWAY_FORWARDED_TOKEN:-local-dev-service-token}"
export LEGALGATE_AUTH_MODE="${LEGALGATE_AUTH_MODE:-PUBLIC_PROTOTYPE}"

cd "$ROOT_DIR"
$MVN -pl services/gateway -DskipTests package

exec java \
  -Dserver.port="$PORT" \
  -Dspring.profiles.active=local \
  -jar services/gateway/target/gateway-*.jar
