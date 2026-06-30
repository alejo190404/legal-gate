# WorkOS AuthKit production setup

LegalGate uses WorkOS Hosted UI for browser authentication. WorkOS organizations map one-to-one
to rows in `tenants`, and every business request requires an organization-scoped `firm_admin`
access token.

## 1. Create the WorkOS environments

Use separate WorkOS staging and production environments. In each environment:

1. Create an AuthKit application and copy its Client ID.
2. Enable Email + Password in Authentication methods.
3. Require email verification and leave the Hosted UI password-reset flow enabled.
4. Under Authorization roles, use single-role mode. Create the environment role with slug
   `firm_admin` and make it the default role. Do not rename the slug.
5. Add exact CORS origins for the application:
   - Local: `http://localhost:4200`
   - Production: the exact HTTPS frontend origin, for example `https://www.legal-gate.co`
   - Add the exact staging origin separately. Avoid wildcard production origins.
6. Add the same origins as redirect URIs. The SPA SDK redirects to the origin root, so register
   `http://localhost:4200` and the exact production/staging origin without a callback suffix.
7. Set the application homepage and post-logout URL to the corresponding frontend origin.
8. Create a server API key for Intake. Never put this key in Vercel or browser configuration.

No custom JWT template, FGA model, social login, enterprise SSO, organization switching UI, or
webhooks are needed for this release.

## 2. Configure environment variables

Use one long random value for `LEGALGATE_INTERNAL_SERVICE_TOKEN` and set the exact same value on
Gateway, Intake, and Mail Ingress. A suitable value can be generated with
`openssl rand -base64 48`.

### Frontend (Vercel or frontend image build)

| Variable | Value |
| --- | --- |
| `LEGALGATE_WORKOS_CLIENT_ID` | WorkOS application Client ID |
| `LEGALGATE_API_BASE_URL` | Public Gateway origin, such as `https://api.legal-gate.co`; leave empty only when `/api` is reverse-proxied to Gateway |

### Gateway

| Variable | Value |
| --- | --- |
| `WORKOS_CLIENT_ID` | Same application Client ID used by the frontend |
| `WORKOS_ISSUER` | `https://api.workos.com` |
| `WORKOS_JWKS_URL` | `https://api.workos.com/sso/jwks/<WORKOS_CLIENT_ID>` |
| `LEGALGATE_INTERNAL_SERVICE_TOKEN` | Shared random service token |
| `LEGALGATE_BACKEND_URL` | Private Intake base URL |
| `LEGALGATE_CORS_ALLOWED_ORIGINS` | Comma-separated exact frontend origins |

Do not configure `WORKOS_AUDIENCE`; WorkOS identifies this application through the JWT
`client_id` claim.

### Intake

| Variable | Value |
| --- | --- |
| `WORKOS_API_KEY` | Server API key from the matching WorkOS environment |
| `LEGALGATE_INTERNAL_SERVICE_TOKEN` | Shared random service token |
| `LEGALGATE_INTAKE_PERSISTENCE` | `jdbc` |
| `SPRING_DATASOURCE_URL` | Production PostgreSQL/Supabase connection |
| `SPRING_DATASOURCE_USERNAME` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | Database password |
| `SPRING_FLYWAY_ENABLED` | `true` for the cutover deployment |

### Mail Ingress

| Variable | Value |
| --- | --- |
| `LEGALGATE_INTERNAL_SERVICE_TOKEN` | Shared random service token |
| `LEGALGATE_INTAKE_ORCHESTRATOR_URL` | Private Intake base URL |

All three services intentionally fail startup when their required WorkOS/service-token settings
are absent.

## 3. Production rollout

Migration `V11__cut_over_to_workos_tenants.sql` intentionally truncates all tenant business data,
drops the local `users` table and password login functions, and creates the WorkOS provisioning
mapping. Before applying it:

1. Back up Supabase and confirm the backup can be restored.
2. Configure the fresh production WorkOS environment and all variables above.
3. Deploy Intake with Flyway enabled and confirm V11 completed.
4. Deploy Mail Ingress, then Gateway, then the frontend.
5. Sign up through Hosted UI, verify the email, enter a firm name, and confirm the refreshed token
   contains `org_id`, `sid`, and role `firm_admin`.
6. Verify `/api/session`, settings, and consultations work; verify unauthenticated Gateway and
   direct Intake business calls return 401.
7. Confirm a second organization cannot be created for the same WorkOS user.

After the cutover, disable any old deployment variables or routes referring to
`PUBLIC_PROTOTYPE`, `WORKOS_AUDIENCE`, `/api/auth/*`, or tenant slugs.
