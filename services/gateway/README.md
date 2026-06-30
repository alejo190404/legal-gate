# LegalGate Gateway

The Gateway is the only public business API. It validates WorkOS access-token JWTs, enforces
organization-scoped `firm_admin` access, strips untrusted identity headers, and forwards trusted
identity plus `X-LegalGate-Service-Token` to Intake.

Public routes are limited to `GET /api/status`, health probes, CORS preflight, and framework error
handling. `POST /api/onboarding/organization` requires any valid WorkOS session. The following
routes additionally require `org_id` and role `firm_admin`:

- `GET /api/session`
- `GET|PUT /api/tenant/settings`
- `GET|POST /api/consultations`

Required configuration:

- `WORKOS_CLIENT_ID`
- `WORKOS_ISSUER` (normally `https://api.workos.com`)
- `WORKOS_JWKS_URL` (`https://api.workos.com/sso/jwks/<client-id>`)
- `LEGALGATE_INTERNAL_SERVICE_TOKEN`
- `LEGALGATE_BACKEND_URL`

See [WorkOS deployment setup](../../docs/deployment/workos-authkit.md).
