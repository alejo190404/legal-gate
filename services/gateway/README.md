# LegalGate Gateway

The Gateway is the only public business API. It validates Clerk session-token JWTs, enforces
organization-scoped firm-administrator access, strips untrusted identity headers, and forwards trusted
identity plus `X-LegalGate-Service-Token` to Intake.

Public routes are limited to `GET /api/status`, health probes, CORS preflight, and framework error
handling. `POST /api/onboarding/organization` requires any valid session. The following
routes additionally require `org_id` and `org_role: org:admin`:

- `GET /api/session`
- `GET|PUT /api/tenant/settings`
- `GET|POST /api/consultations`

Required configuration:

- `LEGALGATE_AUTH_ISSUER` (the Clerk Frontend API domain, e.g. `https://clerk.legal-gate.co`)
- `LEGALGATE_AUTH_JWKS_URL` (`<issuer>/.well-known/jwks.json`)
- `LEGALGATE_AUTH_AUTHORIZED_PARTY` (exact frontend origin, matched against the `azp` claim)
- `LEGALGATE_INTERNAL_SERVICE_TOKEN`
- `LEGALGATE_BACKEND_URL`

See [Clerk deployment setup](../../docs/deployment/clerk-auth.md).
