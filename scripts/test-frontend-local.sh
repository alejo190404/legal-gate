#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/services/frontend"
PORT="${FRONTEND_PORT:-4200}"

cd "$FRONTEND_DIR"

npm test -- --watch=false
npm run build

python3 -m http.server "$PORT" --directory dist/frontend/browser >/tmp/legalgate-frontend-http.log 2>&1 &
SERVER_PID=$!
cleanup() {
  kill "$SERVER_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for _ in $(seq 1 30); do
  if curl -fs "http://localhost:$PORT" >/tmp/legalgate-frontend.html; then
    break
  fi
  sleep 1
done

if ! curl -fsS "http://localhost:$PORT" >/tmp/legalgate-frontend.html; then
  echo "Frontend did not become ready on port $PORT" >&2
  exit 1
fi

grep -q "main-" /tmp/legalgate-frontend.html
MAIN_BUNDLE="$(python3 - <<'PY'
import re
html = open('/tmp/legalgate-frontend.html', encoding='utf-8').read()
match = re.search(r'src="([^\"]*main-[^\"]+\.js)"', html)
if not match:
    raise SystemExit('main bundle not found')
print(match.group(1).lstrip('/'))
PY
)"
curl -fsS "http://localhost:$PORT/$MAIN_BUNDLE" >/tmp/legalgate-frontend-main.js

grep -q "Convierte cada correo de un cliente en una consulta agendada" /tmp/legalgate-frontend-main.js
grep -q "LegalGate" /tmp/legalgate-frontend-main.js

if grep -qi "dashboard\|consola" /tmp/legalgate-frontend-main.js; then
  echo "Unexpected private-product wording found in landing page bundle" >&2
  exit 1
fi

echo "Frontend local verification passed on port $PORT."
