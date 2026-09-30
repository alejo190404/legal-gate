# Vercel, Render, Neon, and Clerk

Deploy the Angular frontend to Vercel, Gateway/Intake/Mail Ingress to Render, and PostgreSQL to
Neon. The browser calls the Gateway directly using `LEGALGATE_API_BASE_URL`; it must never call
Intake or Mail Ingress.

The complete environment-variable list, Clerk Dashboard configuration, destructive migration
warning, and rollout order are in [clerk-auth.md](clerk-auth.md).
Mercado Pago credentials, webhook configuration, plan SQL, and the staged enforcement rollout are
in [mercadopago-billing.md](mercadopago-billing.md).

Production topology:

1. Angular obtains and refreshes a Clerk session token through the Clerk SDK.
2. Angular calls the public Gateway `/api/*` routes with that bearer token.
3. Gateway validates the Clerk signature and claims and calls private Intake with trusted identity
   headers and the shared internal service token.
4. Mail Ingress calls private Intake with the same internal service token.
5. Intake resolves `org_id` to a tenant before setting PostgreSQL tenant RLS context.
6. Mercado Pago sends signed notifications to the public Gateway
   `/api/webhooks/mercadopago`; the Gateway forwards the restricted webhook envelope to Intake,
   where it is durably recorded before asynchronous processing.

Set `LEGALGATE_API_BASE_URL` in Vercel to the public Render Gateway origin, without `/api/backend`.
Set `LEGALGATE_CLERK_PUBLISHABLE_KEY` in Vercel; never set `CLERK_SECRET_KEY` there.
