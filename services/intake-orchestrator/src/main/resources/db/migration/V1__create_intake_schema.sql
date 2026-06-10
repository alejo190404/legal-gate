create extension if not exists pgcrypto;

create table if not exists tenants (
    id uuid primary key default gen_random_uuid(),
    slug text not null unique,
    display_name text not null,
    workos_organization_id text unique,
    created_at timestamptz not null default now()
);

create table if not exists tenant_settings (
    tenant_id uuid primary key references tenants(id) on delete cascade,
    urgent_keywords jsonb not null default '["audiencia", "captura", "tutela", "vencimiento"]'::jsonb,
    consultation_windows jsonb not null default '[]'::jsonb,
    destination_email text,
    updated_at timestamptz not null default now()
);

create table if not exists consultations (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    client_name text not null,
    client_email text not null,
    summary text not null,
    preferred_window text,
    status text not null,
    urgency text not null,
    classification jsonb not null,
    notifications jsonb not null,
    created_at timestamptz not null default now()
);

create index if not exists idx_consultations_tenant_created_at
    on consultations (tenant_id, created_at desc);

alter table tenants enable row level security;
alter table tenant_settings enable row level security;
alter table consultations enable row level security;
alter table tenants force row level security;
alter table tenant_settings force row level security;
alter table consultations force row level security;

drop policy if exists tenants_tenant_context on tenants;
create policy tenants_tenant_context on tenants
    for all
    using (slug = current_setting('app.tenant_slug', true))
    with check (slug = current_setting('app.tenant_slug', true));

drop policy if exists tenant_settings_tenant_context on tenant_settings;
create policy tenant_settings_tenant_context on tenant_settings
    for all
    using (tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true)))
    with check (tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true)));

drop policy if exists consultations_tenant_context on consultations;
create policy consultations_tenant_context on consultations
    for all
    using (tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true)))
    with check (tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true)));

comment on column tenants.workos_organization_id is 'Future WorkOS organization ID. One LegalGate tenant maps to exactly one WorkOS organization.';
