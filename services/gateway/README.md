# LegalGate Gateway

Spring Boot service that acts as the secured public entrypoint for LegalGate backend services.

## Endpoints

- `GET /actuator/health`: public health check.
- `GET /api/status`: public gateway status and backend connectivity/configuration summary.
- `/api/backend/**`: protected reverse-proxy route for the LegalGate backend.
- `GET /api/gateway/fallback`: public fallback shape for unavailable services.

## Security model

- Protected routes require `X-Gateway-Api-Key` matching `GATEWAY_API_KEY`.
- The gateway does not forward the external API key downstream.
- If configured, the gateway forwards `GATEWAY_FORWARDED_TOKEN` as `X-LegalGate-Service-Token` to internal services.
- Spring Security applies default hardening headers such as `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY`.
- CSRF is disabled because the gateway is stateless and API-key authenticated.

## Configuration

Environment variables:

- `PORT`: HTTP port, default `8080`.
- `GATEWAY_API_KEY`: required secret for protected gateway routes.
- `GATEWAY_FORWARDED_TOKEN`: optional internal token forwarded to LegalGate services.
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
  -e GATEWAY_API_KEY=change-me \
  -e GATEWAY_FORWARDED_TOKEN=change-me-too \
  legal-gate-gateway:local
```
