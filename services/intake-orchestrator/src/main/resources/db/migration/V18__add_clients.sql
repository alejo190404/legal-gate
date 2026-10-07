-- Clients: the people and organizations a firm has taken on. See clients/CONTEXT.md.
create table if not exists clients (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    name text not null,
    kind text not null,
    id_number text,
    phone text,
    notes text,
    -- No cascade: a lawyer cannot leave while still responsible for anyone.
    responsible_lawyer_id uuid not null references lawyers(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint clients_kind_check check (kind in ('PERSON', 'ORGANIZATION'))
);

create index if not exists idx_clients_tenant on clients (tenant_id);

create table if not exists client_contact_addresses (
    tenant_id uuid not null references tenants(id) on delete cascade,
    client_id uuid not null references clients(id) on delete cascade,
    address text not null,
    created_at timestamptz not null default now(),
    primary key (client_id, address)
);

-- One address, one Client, per Tenant: a Status Inquiry from a shared address would disclose
-- another Client's matters (ADR-0007).
create unique index if not exists uq_client_contact_addresses_tenant_address
    on client_contact_addresses (tenant_id, lower(address));

-- Append-only. activity_id is null when the subject is the Client itself.
create table if not exists activity_history (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    client_id uuid not null references clients(id) on delete cascade,
    activity_id uuid,
    field text not null,
    old_value text,
    new_value text,
    actor_user_id text,
    created_at timestamptz not null default clock_timestamp()
);

create index if not exists idx_activity_history_client on activity_history (client_id, created_at);

alter table clients enable row level security;
alter table client_contact_addresses enable row level security;
alter table activity_history enable row level security;

drop policy if exists clients_tenant_context on clients;
create policy clients_tenant_context on clients
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

drop policy if exists client_contact_addresses_tenant_context on client_contact_addresses;
create policy client_contact_addresses_tenant_context on client_contact_addresses
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

drop policy if exists activity_history_tenant_context on activity_history;
create policy activity_history_tenant_context on activity_history
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

alter table clients force row level security;
alter table client_contact_addresses force row level security;
alter table activity_history force row level security;
