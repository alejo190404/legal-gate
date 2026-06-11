create table if not exists users (
    id uuid primary key default gen_random_uuid(),
    firm_id uuid not null references tenants(id) on delete cascade,
    email text not null unique,
    full_name text not null,
    role text not null,
    hashed_password text not null,
    last_login_at timestamptz,
    is_active bool not null default true,
    created_at timestamptz not null default now(),
    constraint users_role_check check (role in ('FIRM_ADMIN', 'FIRM_MEMBER'))
);

create index if not exists idx_users_firm_id
    on users (firm_id);

alter table users enable row level security;
alter table users force row level security;

drop policy if exists users_firm_context on users;
create policy users_firm_context on users
    for all
    using (firm_id in (select id from tenants where slug = current_setting('app.tenant_slug', true)))
    with check (firm_id in (select id from tenants where slug = current_setting('app.tenant_slug', true)));

comment on table users is 'Firm owner/admin user accounts. Separate from lawyers, which are business entities that may be associated later.';
comment on column users.firm_id is 'References tenants as the current firm table until the ERD firm naming is fully adopted.';
