1# LegalGate

LegalGate is a SaaS platform for automating legal consultation intake, classification, scheduling, and notifications.

## Services

- `services/gateway`: Spring Boot gateway service that exposes the public entrypoint for LegalGate APIs.
- `services/intake-orchestrator`: Spring Boot consultation intake service for tenant settings, plain-language consultation creation, urgency pre-classification, and admin review.
- `services/frontend`: Angular 21 public landing page for Colombian client-facing marketing.

## Gateway quick start

Run the local verification script:

```bash
./scripts/test-local.sh
```

Run the gateway locally:

```bash
GATEWAY_API_KEY=local-dev-gateway-key \
GATEWAY_FORWARDED_TOKEN=local-dev-service-token \
./scripts/run-gateway-local.sh
```

Then test public and protected endpoints:

```bash
curl http://localhost:8080/api/status
curl -i http://localhost:8080/api/backend/cases
curl -i -H 'X-Gateway-Api-Key: local-dev-gateway-key' http://localhost:8080/api/backend/cases
```

If `LEGALGATE_BACKEND_URL` is unset or the backend is unavailable, the gateway still returns a consistent JSON `503 service_unavailable` fallback instead of failing with an empty response.

## Intake orchestrator quick start

Run the intake verification script:

```bash
./scripts/test-intake-local.sh
```

Run the intake service locally:

```bash
PORT=8081 ./scripts/run-intake-local.sh
```

Then test tenant settings, consultation creation, and admin review:

```bash
curl http://localhost:8081/api/status
curl -X PUT -H 'Content-Type: application/json' \
  -d '{"urgentKeywords":["audiencia","captura"],"consultationWindows":["LUN-VIE 09:00-13:00"],"destinationEmail":"intake@firma.test"}' \
  http://localhost:8081/api/tenants/firma-demo/settings
curl -X POST -H 'Content-Type: application/json' \
  -d '{"clientName":"María Pérez","clientEmail":"maria@example.com","summary":"Tengo una audiencia mañana y necesito orientación aunque no conozco términos legales."}' \
  http://localhost:8081/api/tenants/firma-demo/consultations
curl http://localhost:8081/api/admin/tenants/firma-demo/consultations
```

The current implementation stores data in memory and returns queued email/calendar orchestration flags so DB, RabbitMQ, LLM, email, and calendar adapters can be added later without changing the public contract.

## Frontend quick start

Run the frontend verification script:

```bash
./scripts/test-frontend-local.sh
```

Run the Angular dev server:

```bash
cd services/frontend
npm start
```

Open `http://localhost:4200`.

## Vercel frontend deployment

The repository includes root `vercel.json` configuration for the Angular frontend service:

- Install command: `npm ci --prefix services/frontend`
- Build command: `npm run build --prefix services/frontend`
- Output directory: `services/frontend/dist/frontend/browser`

This lets Vercel deploy the monorepo from the repository root while serving only the Angular landing page.

## Docker Compose

Build and run services locally:

```bash
docker compose build gateway intake-orchestrator frontend
docker compose up gateway intake-orchestrator frontend
```

- Gateway: `http://localhost:8080/api/status`
- Intake orchestrator: `http://localhost:8081/api/status`
- Frontend: `http://localhost:4200`

Made by Alejandro Barragán
