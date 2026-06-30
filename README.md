# LegalGate

LegalGate automates legal consultation intake, classification, scheduling, and notifications.

## Services

- `services/frontend`: Angular SPA with WorkOS AuthKit Hosted UI.
- `services/gateway`: WorkOS JWT resource server and public API boundary.
- `services/intake-orchestrator`: tenant-isolated business logic, onboarding, and PostgreSQL RLS.
- `services/mail-ingress`: verified inbound-mail adapter.
- `services/consultation-classifier`: FastAPI/Gemini classification sidecar.

## Authentication and APIs

Each LegalGate tenant maps to one WorkOS organization. Browser business requests require an access
token with `org_id` and role `firm_admin`. Public business contracts are:

- `POST /api/onboarding/organization`
- `GET /api/session`
- `GET|PUT /api/tenant/settings`
- `GET|POST /api/consultations`
- `GET|POST /api/billing/**`
- `POST /api/webhooks/mercadopago` (signed Mercado Pago webhook)

Tenant slugs and local passwords are not accepted from browsers.

## Development

Copy `.env.example` to `.env`, supply a WorkOS test Client ID/API key and a random shared service
token, then run:

```bash
docker compose up --build
```

Run verification suites:

```bash
mvn test
cd services/frontend && npm test && npm run build
cd ../consultation-classifier && pytest
```

## Production

Follow [WorkOS AuthKit production setup](docs/deployment/workos-authkit.md) before deploying. The
V11 Flyway migration is intentionally destructive and requires a verified Supabase backup. Follow
[Mercado Pago tenant billing setup](docs/deployment/mercadopago-billing.md) before enabling billing
enforcement.
