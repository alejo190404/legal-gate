# LegalGate

LegalGate is a SaaS platform for automating legal consultation intake, classification, scheduling, and notifications.

## Services

- `services/gateway`: Spring Boot gateway service that exposes the public entrypoint for LegalGate APIs.
- `services/intake-orchestrator`: Spring Boot consultation intake service for tenant routing-rule settings, plain-language consultation creation, urgency pre-classification, and admin review.
- `services/consultation-classifier`: Python FastAPI Gemini sidecar for structured inbound email consultation classification.
- `services/mail-ingress`: Spring Boot CloudMailin and MailerSend webhook adapter that sends inbound email events synchronously to Intake Orchestrator over HTTP.
- `services/frontend`: Angular 21 public landing page for Colombian client-facing marketing.

## Gateway quick start

Deployment guide: [`docs/deployment/vercel-render-supabase.md`](docs/deployment/vercel-render-supabase.md).

Run the local verification script:

```bash
./scripts/test-local.sh
```

Run the gateway locally:

```bash
GATEWAY_FORWARDED_TOKEN=local-dev-service-token \
./scripts/run-gateway-local.sh
```

Then test public endpoints and the temporary prototype gateway facade:

```bash
curl http://localhost:8080/api/status
curl -i http://localhost:8080/api/backend/api/status
```

If `LEGALGATE_BACKEND_URL` is unset or the backend is unreachable, the gateway returns a consistent JSON `503 service_unavailable` fallback instead of failing with an empty response. Real backend HTTP errors such as `400`, `409`, and `500` are preserved so deployment issues can be diagnosed from the client response.

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
curl http://localhost:8081/api/tenants/firma-demo/settings
curl -X PUT -H 'Content-Type: application/json' \
  -d '{"routingRules":[{"name":"Urgencias laborales","urgentKeywords":["audiencia","captura"],"consultationWindows":["LUN-VIE 09:00-13:00"],"destinationEmail":"notificaciones@firma.test"}]}' \
  http://localhost:8081/api/tenants/firma-demo/settings
curl -X POST -H 'Content-Type: application/json' \
  -d '{"clientName":"María Pérez","clientEmail":"maria@example.com","summary":"Tengo una audiencia mañana y necesito orientación aunque no conozco términos legales."}' \
  http://localhost:8081/api/tenants/firma-demo/consultations
curl http://localhost:8081/api/admin/tenants/firma-demo/consultations
```

The default test profile stores data in memory. Docker Compose and Render can run the intake service with JDBC/Flyway enabled so consultations persist to Postgres/Supabase while preserving the public contract.

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

Set `LEGALGATE_API_BASE_URL` in Vercel to the Render gateway facade URL, for example `https://legal-gate-gateway.onrender.com/api/backend`. The frontend build writes this value into `/assets/legalgate-config.json`.

## Docker Compose

Build and run services locally:

```bash
cp .env.example .env
docker compose up --build postgres consultation-classifier intake-orchestrator mail-ingress gateway frontend
```

- Gateway: `http://localhost:8080/api/status`
- Intake orchestrator: `http://localhost:8081/api/status`
- Consultation classifier: `http://localhost:8083/health`
- Mail ingress: `http://localhost:8082/actuator/health`
- Frontend: `http://localhost:4200`

Local CloudMailin-style webhook smoke test:

```bash
curl -fsS -X PUT -H 'Content-Type: application/json' \
  -d '{"routingRules":[{"name":"Default intake route","urgentKeywords":["audiencia"],"consultationWindows":[],"destinationEmail":"notificaciones@firma.test"}]}' \
  http://localhost:8081/api/tenants/firma-demo/settings

curl -i -u cloudmailin-local:cloudmailin-local-password \
  -H 'Content-Type: application/json' \
  -d '{"headers":{"from":"Cliente <cliente@example.com>","subject":"Consulta","message_id":"<local@example.com>"},"envelope":{"to":"firma-demo@intake.legal-gate.co","recipients":["firma-demo@intake.legal-gate.co"],"from":"cliente@example.com"},"plain":"Necesito orientacion.","html":"<p>Necesito orientacion.</p>","attachments":[]}' \
  http://localhost:8082/webhooks/cloudmailin
```

Local MailerSend-style webhook smoke test:

```bash
curl -i -H 'Content-Type: application/json' \
  -H 'X-MailerSend-Webhook-Secret: mailersend-local-secret' \
  -d '{"type":"inbound.message","data":{"from":{"email":"cliente@example.com","name":"Cliente"},"recipients":[{"email":"firma-demo@intake.legal-gate.co"}],"subject":"Consulta","message_id":"<local-mailersend@example.com>","text":{"plain":"Necesito orientacion.","html":"<p>Necesito orientacion.</p>"}}}' \
  http://localhost:8082/webhooks/mailersend
```

Made by Alejandro Barragán
