#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"
PORT="${PORT:-8081}"
: "${LEGALGATE_INTERNAL_SERVICE_TOKEN:?Set LEGALGATE_INTERNAL_SERVICE_TOKEN}"
: "${WORKOS_API_KEY:?Set WORKOS_API_KEY}"

cd "$ROOT_DIR"
$MVN -pl services/intake-orchestrator -DskipTests package

exec java \
  -Dserver.port="$PORT" \
  -jar services/intake-orchestrator/target/intake-orchestrator-*.jar
