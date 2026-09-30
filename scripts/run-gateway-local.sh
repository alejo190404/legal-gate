#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"
PORT="${PORT:-8080}"

: "${LEGALGATE_INTERNAL_SERVICE_TOKEN:?Set LEGALGATE_INTERNAL_SERVICE_TOKEN}"
: "${LEGALGATE_AUTH_AUTHORIZED_PARTY:?Set LEGALGATE_AUTH_AUTHORIZED_PARTY}"
: "${LEGALGATE_AUTH_ISSUER:?Set LEGALGATE_AUTH_ISSUER}"
: "${LEGALGATE_AUTH_JWKS_URL:?Set LEGALGATE_AUTH_JWKS_URL}"

cd "$ROOT_DIR"
$MVN -pl services/gateway -DskipTests package

exec java \
  -Dserver.port="$PORT" \
  -Dspring.profiles.active=local \
  -jar services/gateway/target/gateway-*.jar
