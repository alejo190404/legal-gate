# Vercel, Render, Supabase, and WorkOS

Deploy the Angular frontend to Vercel, Gateway/Intake/Mail Ingress to Render, and PostgreSQL to
Supabase. The browser calls the Gateway directly using `LEGALGATE_API_BASE_URL`; it must never call
Intake or Mail Ingress.

The complete environment-variable list, WorkOS Dashboard configuration, destructive migration
warning, and rollout order are in [workos-authkit.md](workos-authkit.md).

Production topology:

1. Angular obtains and refreshes a WorkOS access token through AuthKit.
2. Angular calls the public Gateway `/api/*` routes with that bearer token.
3. Gateway validates the WorkOS signature and claims and calls private Intake with trusted identity
   headers and the shared internal service token.
4. Mail Ingress calls private Intake with the same internal service token.
5. Intake resolves `org_id` to a tenant before setting PostgreSQL tenant RLS context.

Set `LEGALGATE_API_BASE_URL` in Vercel to the public Render Gateway origin, without `/api/backend`.
Set `LEGALGATE_WORKOS_CLIENT_ID` in Vercel; never set `WORKOS_API_KEY` there.
