# LegalGate

LegalGate is a SaaS platform for automating legal consultation intake, classification, scheduling, and notifications.

## Services

- `services/gateway`: Spring Boot gateway service that exposes the public entrypoint for LegalGate APIs.
- `services/intake-orchestrator`: Spring Boot consultation intake service for tenant settings, plain-language consultation creation, urgency pre-classification, and admin review.
- `services/mail-ingress`: Spring Boot CloudMailin webhook adapter that publishes inbound email events to RabbitMQ.
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
curl -X PUT -H 'Content-Type: application/json' \
  -d '{"urgentKeywords":["audiencia","captura"],"consultationWindows":["LUN-VIE 09:00-13:00"],"destinationEmail":"notificaciones@firma.test","intakeEmail":"intake@firma.test"}' \
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
docker compose up --build postgres rabbitmq intake-orchestrator mail-ingress gateway frontend
```

- Gateway: `http://localhost:8080/api/status`
- Intake orchestrator: `http://localhost:8081/api/status`
- Mail ingress: `http://localhost:8082/actuator/health`
- RabbitMQ management: `http://localhost:15672` using the local `SPRING_RABBITMQ_USERNAME` / `SPRING_RABBITMQ_PASSWORD`
- Frontend: `http://localhost:4200`

Local CloudMailin-style webhook smoke test:

```bash
curl -fsS -X PUT -H 'Content-Type: application/json' \
  -d '{"urgentKeywords":["audiencia"],"consultationWindows":[],"destinationEmail":"notificaciones@firma.test","intakeEmail":"intake@firma.test"}' \
  http://localhost:8081/api/tenants/firma-demo/settings

curl -i -u cloudmailin-local:cloudmailin-local-password \
  -H 'Content-Type: application/json' \
  -d '{"headers":{"from":"Cliente <cliente@example.com>","subject":"Consulta","message_id":"<local@example.com>"},"envelope":{"to":"intake@firma.test","recipients":["intake@firma.test"],"from":"cliente@example.com"},"plain":"Necesito orientacion.","html":"<p>Necesito orientacion.</p>","attachments":[]}' \
  http://localhost:8082/webhooks/cloudmailin
```

Made by Alejandro Barragán
