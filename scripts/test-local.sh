#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
"${MVN:-mvn}" -pl services/gateway,services/intake-orchestrator,services/mail-ingress -am test
