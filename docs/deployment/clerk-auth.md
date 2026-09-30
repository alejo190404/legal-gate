# Clerk production setup

LegalGate uses the Clerk Account Portal for browser authentication. Clerk organizations map
one-to-one to rows in `tenants`, and every business request requires an organization-scoped
`org:admin` session token.

## 1. Create the Clerk instances

Use separate Clerk development and production instances. In each:

1. Create the application and copy its Publishable Key (browser) and Secret Key (server only).
2. **Authentication**: enable Email + Password. Require email verification. Keep the hosted
   password-reset flow. Do not enable social login or enterprise SSO.
3. **Organizations**: enable Organizations, and **enable Personal Accounts**.

   This toggle is load-bearing. With Personal Accounts disabled — Clerk's default since August
   2025 — a new user's session stays `pending` until they create an organization in Clerk's own
   UI. LegalGate creates the organization from Intake *after* it has provisioned the local
   tenant, because the tenant slug is what derives the firm's canonical intake email address
   (`OrganizationOnboardingService.uniqueSlug()` -> `IntakeProperties.canonicalIntakeEmail()`).
   Letting Clerk create the organization first would mean a firm exists before its inbound
   address does, and would require a webhook to reconcile. Leave Personal Accounts on.
4. **Roles**: use the built-in `org:admin` and `org:member` only. Do not create a custom role —
   custom organization roles require the B2B Authentication add-on in production, and the Gateway
   maps the administrator role to `ROLE_FIRM_ADMIN` precisely to avoid that cost. Clerk grants it
   automatically to the `created_by` user when Intake creates the organization.

   The dashboard names these roles with the `org:` prefix, but the session token abbreviates.
   Organization data arrives in one compact `o` claim, `{"id": "org_...", "rol": "admin"}`, with no
   prefix on the role; the Gateway reads it through `SessionClaims`. The flat `org_id` and
   `org_role` claims belong to session token v1, deprecated in April 2025 — no instance created
   since then emits them.

5. **Paths**: set the after-sign-in and after-sign-up URLs to `<origin>/dashboard`, and the
   after-sign-out URL to the origin root — sign-out returns to `/`, the public landing. Register
   each deployed origin exactly; avoid wildcards in production.
   - Local: `http://localhost:4200/dashboard`
   - Production: `https://www.legal-gate.co/dashboard`
   - Add the exact staging URL separately.
6. **Branding**: the free Hobby plan shows a "Secured by Clerk" badge on the Account Portal.
   Removing it requires the Pro plan. Build on Hobby; switch to Pro at cutover.

## 2. DNS for the production instance

The production instance serves its Frontend API from your own domain. Add the CNAME records the
Clerk Dashboard generates:

| Record | Purpose |
| --- | --- |
| `clerk.legal-gate.co` | Frontend API — this is the token issuer and JWKS host |
| `accounts.legal-gate.co` | Account Portal (hosted sign-in, sign-up, password reset) |
| `clkmail.legal-gate.co` + DKIM records | Auth email delivery from `legal-gate.co` |

This is why LegalGate is on Clerk: a first-party Frontend API domain is part of standard
production setup rather than a paid add-on, so the browser session cookie is first-party and
httpOnly. No SDK flag keeps a refresh token in `localStorage`. See
[ADR 0001](../adr/0001-clerk-as-auth-provider.md).

Clerk delegates SPF through the `clkmail` subdomain, so these records do not collide with the
outbound-mail records on `LEGALGATE_INTAKE_EMAIL_DOMAIN`. Confirm both still pass after adding
them. Setting up DMARC is recommended.

## 3. Configure environment variables

Use one long random value for `LEGALGATE_INTERNAL_SERVICE_TOKEN` and set the exact same value on
Gateway, Intake, and Mail Ingress. Generate one with `openssl rand -base64 48`.

### Frontend (Vercel or frontend image build)

| Variable | Value |
| --- | --- |
| `LEGALGATE_CLERK_PUBLISHABLE_KEY` | Clerk Publishable Key (`pk_live_...`) |
| `LEGALGATE_API_BASE_URL` | Public Gateway origin, such as `https://api.legal-gate.co`; leave empty only when `/api` is reverse-proxied to Gateway |

### Gateway

| Variable | Value |
| --- | --- |
| `LEGALGATE_AUTH_ISSUER` | `https://clerk.legal-gate.co` |
| `LEGALGATE_AUTH_JWKS_URL` | `https://clerk.legal-gate.co/.well-known/jwks.json` |
| `LEGALGATE_AUTH_AUTHORIZED_PARTY` | Exact frontend origin, e.g. `https://www.legal-gate.co` — matched against the token's `azp` claim |
| `LEGALGATE_INTERNAL_SERVICE_TOKEN` | Shared random service token |
| `LEGALGATE_BACKEND_URL` | Private Intake base URL |
| `LEGALGATE_CORS_ALLOWED_ORIGINS` | Comma-separated exact frontend origins |

### Intake

| Variable | Value |
| --- | --- |
| `CLERK_SECRET_KEY` | Clerk Secret Key (`sk_live_...`) from the matching instance |
| `LEGALGATE_INTERNAL_SERVICE_TOKEN` | Shared random service token |
| `LEGALGATE_INTAKE_PERSISTENCE` | `jdbc` |
| `SPRING_DATASOURCE_URL` | Production PostgreSQL/Neon connection |
| `SPRING_DATASOURCE_USERNAME` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | Database password |
| `SPRING_FLYWAY_ENABLED` | `true` for the cutover deployment |

### Mail Ingress

| Variable | Value |
| --- | --- |
| `LEGALGATE_INTERNAL_SERVICE_TOKEN` | Shared random service token |
| `LEGALGATE_INTAKE_ORCHESTRATOR_URL` | Private Intake base URL |

All three services intentionally fail startup when their required auth/service-token settings are
absent. Never set `CLERK_SECRET_KEY` in Vercel or any browser configuration.

## 4. Production rollout

Migration `V17__cut_over_to_clerk_tenants.sql` intentionally truncates all tenant business data,
renames `tenants.workos_organization_id` to `auth_organization_id`, and rebuilds the tenant lookup
functions. Every WorkOS organization and user ID is a dead reference under Clerk, so no tenant row
survives. Before applying it:

1. Back up the Neon branch (or take a snapshot) and confirm the backup can be restored.
2. Configure the production Clerk instance, DNS records, and all variables above.
3. Deploy Intake with Flyway enabled and confirm V17 completed.
4. Deploy Mail Ingress, then Gateway, then the frontend.
5. Sign up through `accounts.legal-gate.co` and verify the email. Confirm the verification email
   arrives **from `legal-gate.co`**, not a Clerk-owned domain.
6. Land on `/dashboard` with no organization on the session and confirm the firm-name form renders.
7. Enter a firm name. Confirm the tenant goes `PENDING` -> `ACTIVE`, a Clerk organization exists,
   and the refreshed token carries `sid`, `azp`, and an `o` claim of
   `{"id": "org_...", "rol": "admin"}`.
8. Confirm the generated canonical intake address is correct for the new slug.
9. In DevTools, confirm `localStorage` holds no session or refresh token, and that the Clerk
   session cookie on `legal-gate.co` is httpOnly. This is the reason for the migration.
10. Sign out, sign back in, and confirm you land in the console rather than the onboarding form.
11. Verify `/api/session`, settings, and consultations work; verify unauthenticated Gateway calls
    and direct Intake business calls return 401.
12. Confirm a second organization cannot be created for the same Clerk user.

After the cutover, remove any deployment variables still referring to `WORKOS_CLIENT_ID`,
`WORKOS_API_KEY`, `WORKOS_ISSUER`, `WORKOS_JWKS_URL`, or `LEGALGATE_WORKOS_CLIENT_ID`, and switch
the Clerk plan to Pro to drop the badge.
