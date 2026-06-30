-- INTENTIONALLY DESTRUCTIVE: authentication/tenant cutover approved for the pre-production dataset.
truncate table tenants cascade;

drop function if exists app_find_active_user_for_login(text);
drop function if exists app_record_user_login(text);
drop table if exists users cascade;

alter table tenants
    alter column workos_organization_id drop default,
    add column if not exists provisioning_status varchar(16) not null default 'PENDING',
    add column if not exists provisioning_owner_id varchar(128),
    add column if not exists provisioning_error text;

alter table tenants
    add constraint tenants_provisioning_status_check
        check (provisioning_status in ('PENDING', 'ACTIVE', 'FAILED'));

create unique index if not exists uq_tenants_workos_organization
    on tenants (workos_organization_id)
    where workos_organization_id is not null;

create unique index if not exists uq_tenants_provisioning_owner
    on tenants (provisioning_owner_id)
    where provisioning_owner_id is not null;

create or replace function app_find_tenant_by_workos_organization(p_organization_id text)
returns table (
    id uuid,
    slug text,
    display_name text,
    workos_organization_id text,
    provisioning_status text,
    provisioning_owner_id text
)
language sql
stable
security definer
set search_path = public
set row_security = off
as $$
    select t.id, t.slug, t.display_name, t.workos_organization_id,
           t.provisioning_status, t.provisioning_owner_id
    from tenants t
    where t.workos_organization_id = p_organization_id
    limit 1
$$;

create or replace function app_find_tenant_by_provisioning_owner(p_owner_id text)
returns table (
    id uuid,
    slug text,
    display_name text,
    workos_organization_id text,
    provisioning_status text,
    provisioning_owner_id text
)
language sql
stable
security definer
set search_path = public
set row_security = off
as $$
    select t.id, t.slug, t.display_name, t.workos_organization_id,
           t.provisioning_status, t.provisioning_owner_id
    from tenants t
    where t.provisioning_owner_id = p_owner_id
    limit 1
$$;

revoke all on function app_find_tenant_by_workos_organization(text) from public;
revoke all on function app_find_tenant_by_provisioning_owner(text) from public;
grant execute on function app_find_tenant_by_workos_organization(text) to current_user;
grant execute on function app_find_tenant_by_provisioning_owner(text) to current_user;

comment on column tenants.workos_organization_id is
    'WorkOS organization ID. Exactly one active WorkOS organization maps to one LegalGate tenant.';
comment on column tenants.provisioning_owner_id is
    'WorkOS user ID that initiated self-service provisioning; unique to enforce one firm per user.';
