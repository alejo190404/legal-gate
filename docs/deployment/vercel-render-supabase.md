# LegalGate Vercel + Render + Supabase deployment

This guide describes the first production-like LegalGate deployment slice:

- Angular frontend on Vercel.
- Spring Boot gateway on Render as the only browser-facing API facade.
- Spring Boot intake-orchestrator on Render for intake persistence.
- Spring Boot mail-ingress on Render as the CloudMailin-facing webhook adapter.
- RabbitMQ on Render private networking for inbound email events.
- Supabase Postgres accessed from the intake service through JDBC/Flyway.

The frontend must call only the gateway. It must not call intake-orchestrator or Supabase directly.

## Request flow

1. Browser opens the Vercel app.
2. Angular reads `/assets/legalgate-config.json` generated at build time from `LEGALGATE_API_BASE_URL`.
3. Angular calls `https://legal-gate-gateway.onrender.com/api/backend/...`.
4. Gateway applies CORS and temporary public-prototype route policy.
5. Gateway proxies to `LEGALGATE_BACKEND_URL` (the Render intake-orchestrator service).
6. Intake persists tenant settings and consultations in Supabase Postgres with Flyway-managed schema and RLS enabled.
7. CloudMailin posts inbound email webhooks to mail-ingress, which publishes durable events to private RabbitMQ.
8. Intake consumes RabbitMQ events asynchronously. This milestone logs/acks them without creating consultations.

The gateway should return `503 service_unavailable` only when the intake service is unreachable or `LEGALGATE_BACKEND_URL` is missing. If intake responds with `400`, `409`, or `500`, the gateway should pass that downstream status/body through so Render/Supabase failures are visible during diagnosis.

## Environment variables

### Vercel frontend

- `LEGALGATE_API_BASE_URL=https://legal-gate-gateway.onrender.com/api/backend`

Vercel already has this value configured for the current deployment. Empty local value keeps relative `/api/...` URLs for the Angular dev proxy.

### Render gateway

- `PORT`: provided by Render.
- `LEGALGATE_BACKEND_URL=https://<intake-render-service>.onrender.com`
- `GATEWAY_REQUEST_TIMEOUT=5s`
- `LEGALGATE_CORS_ALLOWED_ORIGINS=https://<vercel-domain>,https://app.legalgate.co,http://localhost:4200`
- `LEGALGATE_AUTH_MODE=PUBLIC_PROTOTYPE`

Do not configure a browser-facing shared secret for this milestone. WorkOS/JWT will replace the temporary public-prototype policy before real admin workflows are exposed.

### Render intake-orchestrator

Use the Supabase pooler connection currently configured for Render:

- `LEGALGATE_INTAKE_PERSISTENCE=jdbc`
- `SPRING_DATASOURCE_URL=jdbc:postgresql://<supabase-pooler-host>:5432/postgres?sslmode=require`
- `SPRING_DATASOURCE_USERNAME=<supabase-db-user>`
- `SPRING_DATASOURCE_PASSWORD=<supabase-db-password>`
- `SPRING_DATASOURCE_MAX_POOL_SIZE=5`
- `SPRING_FLYWAY_ENABLED=true`
- `LEGALGATE_SEED_DEMO_DATA=false`
- `LEGALGATE_MAIL_ENABLED=true`
- `SPRING_RABBITMQ_HOST=<rabbitmq-private-host>`
- `SPRING_RABBITMQ_PORT=5672`
- `SPRING_RABBITMQ_USERNAME=<rabbitmq-user>`
- `SPRING_RABBITMQ_PASSWORD=<rabbitmq-password>`
- `LEGALGATE_MAIL_EXCHANGE=legalgate.mail`
- `LEGALGATE_MAIL_INCOMING_QUEUE=legalgate.mail.incoming`
- `LEGALGATE_MAIL_DEAD_LETTER_QUEUE=legalgate.mail.incoming.dlq`
- `LEGALGATE_MAIL_ROUTING_KEY=mail.inbound.received`
- `LEGALGATE_MAIL_DEAD_LETTER_ROUTING_KEY=mail.inbound.dead`

Keep real Supabase credentials only in Render environment variables. Do not store them in Vercel or committed files.

### Render RabbitMQ

Create RabbitMQ as a private Render Docker service using `rabbitmq:3.13-management-alpine`. It should not receive public traffic. Configure:

- `RABBITMQ_DEFAULT_USER=<rabbitmq-user>`
- `RABBITMQ_DEFAULT_PASS=<rabbitmq-password>`

Attach persistent disk storage if message durability matters beyond service restarts.

### Render mail-ingress

Deploy `services/mail-ingress/Dockerfile` as a public Render web service and configure:

- `SPRING_DATASOURCE_URL=<same Supabase JDBC URL used by intake>`
- `SPRING_DATASOURCE_USERNAME=<supabase-db-user>`
- `SPRING_DATASOURCE_PASSWORD=<supabase-db-password>`
- `SPRING_RABBITMQ_HOST=<rabbitmq-private-host>`
- `SPRING_RABBITMQ_PORT=5672`
- `SPRING_RABBITMQ_USERNAME=<rabbitmq-user>`
- `SPRING_RABBITMQ_PASSWORD=<rabbitmq-password>`
- `LEGALGATE_CLOUDMAILIN_USERNAME=<cloudmailin-basic-auth-user>`
- `LEGALGATE_CLOUDMAILIN_PASSWORD=<cloudmailin-basic-auth-password>`
- `LEGALGATE_MAIL_EXCHANGE=legalgate.mail`
- `LEGALGATE_MAIL_ROUTING_KEY=mail.inbound.received`

In CloudMailin, set the target URL to `https://<user>:<password>@<mail-ingress-render-host>/webhooks/cloudmailin` and choose the Normalized JSON format.

## Troubleshooting registration `503`

If `POST https://legal-gate-gateway.onrender.com/api/backend/api/auth/register` returns `503`, check these in order:

1. `LEGALGATE_BACKEND_URL` on the Render gateway must point to the public intake service root, for example `https://legal-gate-intake-orchestrator.onrender.com`, without an extra `/api` suffix.
2. The intake Render service must be healthy at `/actuator/health` and `/api/status`.
3. The intake Render service must have JDBC persistence enabled with:
   - `LEGALGATE_INTAKE_PERSISTENCE=jdbc`
   - `SPRING_FLYWAY_ENABLED=true`
   - a valid Supabase pooler `SPRING_DATASOURCE_URL`
   - no stale Flyway bootstrap overrides such as `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` or `SPRING_FLYWAY_VERSION=0` unless you intentionally need them
4. If intake starts but registration fails, inspect the intake logs for Flyway migration failures, Postgres authentication errors, or missing-table errors such as `relation "users" does not exist`.

With the current gateway behavior, downstream intake errors should remain visible instead of being rewritten to a generic `503`, which makes Render-side debugging much easier.

## Local Docker Compose

`docker-compose.yml` includes a local Postgres container and runs the intake service with JDBC/Flyway enabled:

```bash
cp .env.example .env
docker compose up --build postgres rabbitmq intake-orchestrator mail-ingress gateway frontend
```

Local endpoints:

- Gateway health: `http://localhost:8080/actuator/health`
- Gateway API status: `http://localhost:8080/api/status`
- Gateway proxy status: `http://localhost:8080/api/backend/api/status`
- Intake health: `http://localhost:8081/actuator/health`
- Mail ingress health: `http://localhost:8082/actuator/health`
- RabbitMQ management: `http://localhost:15672`
- Frontend: `http://localhost:4200`

## Supabase schema and RLS

Flyway migration `V1__create_intake_schema.sql` creates:

- `tenants`
- `tenant_settings`
- `consultations`

RLS is enabled and forced on tenant-scoped tables. The JDBC repository sets `app.tenant_slug` inside each transaction before reads/writes so policies enforce tenant isolation as a defense-in-depth layer.

## WorkOS seam

The current milestone intentionally keeps WorkOS as a seam only:

- Frontend auth is isolated behind `AuthService`.
- Gateway exposes `LEGALGATE_AUTH_MODE` with `PUBLIC_PROTOTYPE` today and a failing `WORKOS` placeholder for the next milestone.
- `tenants.workos_organization_id` is present in the schema for the future one-tenant-to-one-WorkOS-organization mapping.

Before exposing real admin data, implement WorkOS AuthKit/JWT validation at the gateway and derive tenant context from WorkOS organization claims rather than browser-supplied tenant path parameters.
