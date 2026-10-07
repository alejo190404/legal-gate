-- Each firm's own catalog of the kinds of work it does for Clients.
create table if not exists activity_types (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    name text not null,
    kind text not null,
    default_turnaround_days integer,
    default_term_days integer,
    retired_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint activity_types_kind_check check (kind in ('TURNAROUND', 'DATED', 'EXTERNAL')),
    constraint activity_types_turnaround_check check (
        (kind = 'TURNAROUND' and default_turnaround_days > 0 and default_term_days is null)
        or (kind = 'DATED' and default_turnaround_days is null and default_term_days is null)
        or (kind = 'EXTERNAL' and default_turnaround_days is null and (default_term_days is null or default_term_days > 0))
    )
);

create index if not exists idx_activity_types_tenant on activity_types (tenant_id);

-- The starter set is seeded once: a firm that retires all of it is not reseeded.
create table if not exists activity_type_catalogs (
    tenant_id uuid primary key references tenants(id) on delete cascade,
    seeded_at timestamptz not null default now()
);

alter table activity_types enable row level security;
alter table activity_type_catalogs enable row level security;

drop policy if exists activity_types_tenant_context on activity_types;
create policy activity_types_tenant_context on activity_types
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

drop policy if exists activity_type_catalogs_tenant_context on activity_type_catalogs;
create policy activity_type_catalogs_tenant_context on activity_type_catalogs
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

alter table activity_types force row level security;
alter table activity_type_catalogs force row level security;
