# LegalGate

LegalGate is a SaaS platform for automating legal consultation intake, classification, scheduling, and notifications.

## Services

- `services/gateway`: Spring Boot gateway service that exposes the public entrypoint for LegalGate APIs.
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

## Docker Compose

Build and run either service locally:

```bash
docker compose build gateway frontend
docker compose up gateway frontend
```

- Gateway: `http://localhost:8080/api/status`
- Frontend: `http://localhost:4200`
