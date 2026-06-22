# LegalGate Vercel + Render + Supabase deployment

This guide describes the first production-like LegalGate deployment slice:

- Angular frontend on Vercel.
- Spring Boot gateway on Render as the only browser-facing API facade.
- Spring Boot intake-orchestrator on Render for intake persistence.
- Python FastAPI consultation-classifier on Render for Gemini-based inbound email classification.
- Spring Boot mail-ingress on Render as the CloudMailin and MailerSend webhook adapter that calls intake-orchestrator over HTTP.
- Supabase Postgres accessed from the intake service through JDBC/Flyway.

The frontend must call only the gateway. It must not call intake-orchestrator or Supabase directly.

## Request flow

1. Browser opens the Vercel app.
2. Angular reads `/assets/legalgate-config.json` generated at build time from `LEGALGATE_API_BASE_URL`.
3. Angular calls `https://legal-gate-gateway.onrender.com/api/backend/...`.
4. Gateway applies CORS and temporary public-prototype route policy.
5. Gateway proxies to `LEGALGATE_BACKEND_URL` (the Render intake-orchestrator service).
6. Intake persists each tenant's canonical LegalGate intake email, routing rules, tenant urgency levels, and consultations in Supabase Postgres with Flyway-managed schema and RLS enabled.
7. CloudMailin or MailerSend posts inbound email webhooks to mail-ingress.
8. Mail-ingress resolves the tenant and synchronously posts normalized inbound-email JSON to intake-orchestrator at `/api/internal/inbound-emails`.
9. Intake calls consultation-classifier with the normalized email, tenant routes, urgency levels, and system prompt. Intake validates the model response, stores the selected route name as `consultationType`, stores the selected destination email as `assignedLawyerEmail`, and creates the consultation. If Gemini is unavailable or invalid, intake creates a fallback consultation for manual review without queuing email or calendar work.

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
- `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false`
- `LEGALGATE_SEED_DEMO_DATA=false`
- `LEGALGATE_INTAKE_EMAIL_DOMAIN=intake.legal-gate.co`
- `LEGALGATE_CONSULTATION_CLASSIFIER_URL=https://<consultation-classifier-render-service>.onrender.com`
- `LEGALGATE_CONSULTATION_CLASSIFIER_TIMEOUT=3s`
- `LEGALGATE_CONSULTATION_CLASSIFIER_PROMPT_VERSION=consultation-classifier-v1`

Keep real Supabase credentials only in Render environment variables. Do not store them in Vercel or committed files.

### Render consultation-classifier

Deploy `services/consultation-classifier/Dockerfile` as a private/internal Render web service when possible, or as a public service restricted by network policy if private service-to-service routing is not available. Configure:

- `PORT`: provided by Render.
- `GEMINI_API_KEY=<google-ai-api-key>`
- `GEMINI_MODEL=gemini-3.5-flash`
- `GEMINI_TEMPERATURE=0.2`
- `CLASSIFIER_LOG_PAYLOADS=false`
- `CLASSIFIER_LOG_PREVIEW_CHARS=500`

Health check path: `/health`.

The service logs every Gemini call attempt with model, temperature, prompt version, message id, sender, recipients, subject preview, body lengths, urgency levels, and route configuration. Set `CLASSIFIER_LOG_PAYLOADS=true` only when you intentionally want prompt and raw model response previews in logs; keep it `false` for production unless you are debugging sensitive intake content. The service returns typed `503 gemini_unavailable` or `502 gemini_invalid_response` responses. Intake treats those as fallback conditions and still creates a manual-review consultation.

### Render mail-ingress

Deploy `services/mail-ingress/Dockerfile` as a public Render web service and configure:

- `SPRING_DATASOURCE_URL=<same Supabase JDBC URL used by intake>`
- `SPRING_DATASOURCE_USERNAME=<supabase-db-user>`
- `SPRING_DATASOURCE_PASSWORD=<supabase-db-password>`
- `LEGALGATE_CLOUDMAILIN_USERNAME=<cloudmailin-basic-auth-user>`
- `LEGALGATE_CLOUDMAILIN_PASSWORD=<cloudmailin-basic-auth-password>`
- `LEGALGATE_MAILERSEND_WEBHOOK_SECRET=<mailersend-webhook-secret>`
- `LEGALGATE_INTAKE_ORCHESTRATOR_URL=https://<intake-render-service>.onrender.com`

In CloudMailin, set the target URL to `https://<user>:<password>@<mail-ingress-render-host>/webhooks/cloudmailin` and choose the Normalized JSON format.

In MailerSend, configure an inbound route for `*@intake.legal-gate.co`, set the MX target to `inbound.mailersend.net`, and forward webhooks to `https://<mail-ingress-render-host>/webhooks/mailersend`. Use the same value in MailerSend and Render for `LEGALGATE_MAILERSEND_WEBHOOK_SECRET`.

## Troubleshooting registration `503`

If `POST https://legal-gate-gateway.onrender.com/api/backend/api/auth/register` returns `503`, check these in order:

1. `LEGALGATE_BACKEND_URL` on the Render gateway must point to the public intake service root, for example `https://legal-gate-intake-orchestrator.onrender.com`, without an extra `/api` suffix.
2. The intake Render service must be healthy at `/actuator/health` and `/api/status`.
3. The intake Render service must have JDBC persistence enabled with:
   - `LEGALGATE_INTAKE_PERSISTENCE=jdbc`
   - `SPRING_FLYWAY_ENABLED=true`
   - a valid Supabase pooler `SPRING_DATASOURCE_URL`
  - `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false` for OSS-safe compatibility with the earlier `V1`/`V2` migration consolidation
  - no stale Flyway bootstrap overrides such as `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` or `SPRING_FLYWAY_VERSION=0` unless you intentionally need them
4. If intake starts but registration fails, inspect the intake logs for Flyway migration failures, Postgres authentication errors, or missing-table errors such as `relation "users" does not exist`.

With the current gateway behavior, downstream intake errors should remain visible instead of being rewritten to a generic `503`, which makes Render-side debugging much easier.

## Local Docker Compose

`docker-compose.yml` includes a local Postgres container and runs the intake service with JDBC/Flyway enabled:

```bash
cp .env.example .env
docker compose up --build postgres consultation-classifier intake-orchestrator mail-ingress gateway frontend
```

Local endpoints:

- Gateway health: `http://localhost:8080/actuator/health`
- Gateway API status: `http://localhost:8080/api/status`
- Gateway proxy status: `http://localhost:8080/api/backend/api/status`
- Intake health: `http://localhost:8081/actuator/health`
- Mail ingress health: `http://localhost:8082/actuator/health`
- Consultation classifier health: `http://localhost:8083/health`
- Frontend: `http://localhost:4200`

## Supabase schema and RLS

Flyway migrations create and evolve:

- `tenants`
- `tenant_settings` with tenant-wide `intake_email`, JSON `routing_rules`, and JSON `urgency_levels`
- `consultations` with selected `consultation_type`, `assigned_lawyer_email`, and inbound `source_event_id` / `source_message_id` trace fields

RLS is enabled and forced on tenant-scoped tables. The JDBC repository sets `app.tenant_slug` inside each transaction before reads/writes so policies enforce tenant isolation as a defense-in-depth layer.

`V6__add_routing_rules_to_tenant_settings.sql` adds the production-safe routing model. Existing flat settings rows are backfilled into a single `Default intake route`, preserving the old `urgent_keywords`, `consultation_windows`, and `destination_email` columns for compatibility while new clients use `routing_rules`.

`V7__add_gemini_consultation_classification_fields.sql` adds tenant urgency levels, consultation route/type and assignee columns, inbound trace fields, and a partial unique index on `(tenant_id, source_message_id)` so webhook retries do not duplicate consultations.

Canonical intake addresses are system-owned and stored in `tenant_settings.intake_email` as `{tenantId}@${LEGALGATE_INTAKE_EMAIL_DOMAIN}`. The project default is `intake.legal-gate.co` so registrations match the production intake domain. During rollout, `GET /api/tenants/{tenantId}/settings` backfills missing intake emails to the canonical value without overwriting an existing stored address.

## WorkOS seam

The current milestone intentionally keeps WorkOS as a seam only:

- Frontend auth is isolated behind `AuthService`.
- Gateway exposes `LEGALGATE_AUTH_MODE` with `PUBLIC_PROTOTYPE` today and a failing `WORKOS` placeholder for the next milestone.
- `tenants.workos_organization_id` is present in the schema for the future one-tenant-to-one-WorkOS-organization mapping.

Before exposing real admin data, implement WorkOS AuthKit/JWT validation at the gateway and derive tenant context from WorkOS organization claims rather than browser-supplied tenant path parameters.
