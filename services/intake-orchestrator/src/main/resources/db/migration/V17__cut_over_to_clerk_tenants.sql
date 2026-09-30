-- INTENTIONALLY DESTRUCTIVE: auth provider cutover (WorkOS -> Clerk) approved for the
-- pre-production dataset. Every stored organization and owner ID is a WorkOS identifier and
-- is a dead reference under Clerk, so no tenant row can be carried across.
truncate table tenants cascade;

-- The column holds whatever the current auth provider calls an organization. Naming the vendor
-- here is what made this migration necessary twice; the name stays provider-neutral from now on.
alter table tenants
    rename column workos_organization_id to auth_organization_id;

alter index if exists uq_tenants_workos_organization
    rename to uq_tenants_auth_organization;

-- Both lookup functions return the renamed column, so their result type changes and
-- create-or-replace cannot be used.
drop function if exists app_find_tenant_by_workos_organization(text);
drop function if exists app_find_tenant_by_provisioning_owner(text);

create function app_find_tenant_by_auth_organization(p_organization_id text)
returns table (
    id uuid,
    slug text,
    display_name text,
    auth_organization_id text,
    provisioning_status text,
    provisioning_owner_id text
)
language sql
stable
security definer
set search_path = public
set row_security = off
as $$
    select t.id, t.slug, t.display_name, t.auth_organization_id,
           t.provisioning_status, t.provisioning_owner_id
    from tenants t
    where t.auth_organization_id = p_organization_id
    limit 1
$$;

create function app_find_tenant_by_provisioning_owner(p_owner_id text)
returns table (
    id uuid,
    slug text,
    display_name text,
    auth_organization_id text,
    provisioning_status text,
    provisioning_owner_id text
)
language sql
stable
security definer
set search_path = public
set row_security = off
as $$
    select t.id, t.slug, t.display_name, t.auth_organization_id,
           t.provisioning_status, t.provisioning_owner_id
    from tenants t
    where t.provisioning_owner_id = p_owner_id
    limit 1
$$;

revoke all on function app_find_tenant_by_auth_organization(text) from public;
revoke all on function app_find_tenant_by_provisioning_owner(text) from public;
grant execute on function app_find_tenant_by_auth_organization(text) to current_user;
grant execute on function app_find_tenant_by_provisioning_owner(text) to current_user;

comment on column tenants.auth_organization_id is
    'Auth provider organization ID. Exactly one active provider organization maps to one LegalGate tenant.';
comment on column tenants.provisioning_owner_id is
    'Auth provider user ID that initiated self-service provisioning; unique to enforce one firm per user.';
