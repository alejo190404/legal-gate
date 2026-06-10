# LegalGate Vercel + Render + Supabase deployment

This guide describes the first production-like LegalGate deployment slice:

- Angular frontend on Vercel.
- Spring Boot gateway on Render as the only browser-facing API facade.
- Spring Boot intake-orchestrator on Render for intake persistence.
- Supabase Postgres accessed directly from the intake service through JDBC/Flyway.

The frontend must call only the gateway. It must not call intake-orchestrator or Supabase directly.

## Request flow

1. Browser opens the Vercel app.
2. Angular reads `/assets/legalgate-config.json` generated at build time from `LEGALGATE_API_BASE_URL`.
3. Angular calls `https://legal-gate-gateway.onrender.com/api/backend/...`.
4. Gateway applies CORS and temporary public-prototype route policy.
5. Gateway proxies to `LEGALGATE_BACKEND_URL` (the Render intake-orchestrator service).
6. Intake persists tenant settings and consultations in Supabase Postgres with Flyway-managed schema and RLS enabled.

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

Use Supabase's direct Postgres host, not the connection pooler:

- `LEGALGATE_INTAKE_PERSISTENCE=jdbc`
- `SPRING_DATASOURCE_URL=jdbc:postgresql://<supabase-direct-host>:5432/postgres?sslmode=require`
- `SPRING_DATASOURCE_USERNAME=<supabase-db-user>`
- `SPRING_DATASOURCE_PASSWORD=<supabase-db-password>`
- `SPRING_DATASOURCE_MAX_POOL_SIZE=5`
- `SPRING_FLYWAY_ENABLED=true`
- `LEGALGATE_SEED_DEMO_DATA=false`

Keep real Supabase credentials only in Render environment variables. Do not store them in Vercel or committed files.

## Local Docker Compose

`docker-compose.yml` includes a local Postgres container and runs the intake service with JDBC/Flyway enabled:

```bash
cp .env.example .env
docker compose up --build postgres intake-orchestrator gateway frontend
```

Local endpoints:

- Gateway health: `http://localhost:8080/actuator/health`
- Gateway API status: `http://localhost:8080/api/status`
- Gateway proxy status: `http://localhost:8080/api/backend/api/status`
- Intake health: `http://localhost:8081/actuator/health`
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
