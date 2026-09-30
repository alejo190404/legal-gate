# ADR-0001: Clerk is the authentication provider

- Status: Accepted

LegalGate ran on WorkOS AuthKit. WorkOS's cookie-based session refresh requires a custom AuthKit
domain, a $99/mo add-on, because without one the session cookie lives on `api.workos.com` and is a
third-party cookie that browsers block — sessions died as soon as the access token expired. Rather
than pay it, the SPA created the AuthKit client with `devMode: true`, which kept the rotating
refresh token in `localStorage` and sent it in the refresh body. That worked from any origin, and
it left a refresh token readable by any injected script on a law firm's console.

We moved to Clerk because a production Clerk instance serves its Frontend API from a CNAME on our
own domain (`clerk.legal-gate.co`) as part of standard setup, not as a paid add-on. The session
cookie is therefore first-party and httpOnly, and no SDK flag has to trade session survival for
token exposure. Auth emails also send from `legal-gate.co` by default via the same DNS setup,
which matters for a firm whose client correspondence is already standardized on its own domain.

## Considered options

- **Stay on WorkOS and keep `devMode: true`.** Free, zero work, and the option we had been living
  with. Rejected: the exposure is on the console of a law practice, and it was never a decision so
  much as a deferral.
- **Stay on WorkOS and pay $99/mo.** Fixes the same problem with no code change. Rejected on cost:
  Clerk solves it for $0–25/mo.
- **Self-host (Spring Security + OIDC, or Better Auth).** No recurring cost at all. Rejected: it
  moves password reset, email verification, MFA, and session security in-house for a single-role
  product with one administrator per firm. Not where the time should go.

## Consequences

- `firm_admin` was a custom WorkOS role slug. Clerk charges for custom organization roles in
  production (the B2B Authentication add-on), so the Gateway maps Clerk's built-in `org:admin` to
  `ROLE_FIRM_ADMIN` instead. A second role, when one is needed, uses the built-in `org:member`.
- Personal Accounts must stay **enabled** in Clerk. Disabled — Clerk's default — a new user's
  session is `pending` until they create an organization in Clerk's UI, which would put Clerk
  ahead of Intake in provisioning and break the derivation of a firm's canonical intake email
  address from its tenant slug.
- `tenants.workos_organization_id` became `auth_organization_id` in V17. The vendor's name is no
  longer in the schema; this is the second auth migration and the column name should not have to
  change again for a third.
- The frontend bundle grew: `@clerk/clerk-js` is substantially larger than
  `@workos-inc/authkit-js`, and the Angular initial-bundle budget was raised to match. If that
  cost matters later, load Clerk from the Frontend API host instead of bundling it.
