# LegalGate Gateway

Spring Boot service that acts as the secured public entrypoint for LegalGate backend services.

## Endpoints

- `GET /actuator/health`: public health check.
- `GET /api/status`: public gateway status and backend connectivity/configuration summary.
- `/api/backend/**`: temporary prototype reverse-proxy facade for the LegalGate backend.
- `GET /api/gateway/fallback`: public fallback shape for unavailable services.

## Security model

- `LEGALGATE_AUTH_MODE=PUBLIC_PROTOTYPE` exposes only the demo-safe backend facade routes needed by the Vercel prototype.
- `LEGALGATE_AUTH_MODE=WORKOS` is reserved for the next milestone and fails fast until WorkOS JWT validation is implemented.
- If configured, the gateway forwards `GATEWAY_FORWARDED_TOKEN` as `X-LegalGate-Service-Token` to internal services.
- Spring Security applies default hardening headers such as `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY`.
- CSRF is disabled because the gateway is stateless.

## Configuration

Environment variables:

- `PORT`: HTTP port, default `8080`.
- `LEGALGATE_AUTH_MODE`: `PUBLIC_PROTOTYPE` for the current demo facade; `WORKOS` is reserved until JWT validation lands.
- `GATEWAY_FORWARDED_TOKEN`: optional internal token forwarded to LegalGate services.
- `LEGALGATE_CORS_ALLOWED_ORIGINS`: comma-separated browser origins allowed to call the gateway facade.
- `LEGALGATE_BACKEND_URL`: optional backend base URL, for example `http://backend:8080`.
- `GATEWAY_REQUEST_TIMEOUT`: downstream request timeout, default `3s`.

When `LEGALGATE_BACKEND_URL` is blank or the backend returns an error, `/api/backend/**` responds with a stable JSON `503 service_unavailable` payload so consumers still receive a useful response while services are being connected.

## Local commands

```bash
mvn -pl services/gateway test
./scripts/test-local.sh
./scripts/run-gateway-local.sh
```

## Docker build

```bash
mvn -pl services/gateway -DskipTests package
cd services/gateway
docker build -t legal-gate-gateway:local .
docker run --rm -p 8080:8080 \
  -e LEGALGATE_AUTH_MODE=PUBLIC_PROTOTYPE \
  -e GATEWAY_FORWARDED_TOKEN=change-me-too \
  legal-gate-gateway:local
```
