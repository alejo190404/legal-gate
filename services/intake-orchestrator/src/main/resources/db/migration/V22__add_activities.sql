-- Activities: one piece of work of an Activity Type, for one Client.
create table if not exists activities (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    -- The nudge worker cannot read tenants under RLS, so the slug travels with the row.
    tenant_slug text not null,
    client_id uuid not null references clients(id) on delete cascade,
    activity_type_id uuid not null references activity_types(id),
    -- Snapshotted when opened: the catalog never moves a promise already made.
    kind text not null,
    label text,
    start_date date not null,
    turnaround_days integer,
    due_date date,
    scheduled_date date,
    term_days integer,
    -- No cascade: a lawyer cannot leave while still assigned open work.
    assigned_lawyer_id uuid not null references lawyers(id),
    done boolean not null default false,
    done_at timestamptz,
    -- The date a reminder was sent for, so each is sent once per date.
    overdue_nudged_for date,
    term_nudged_for date,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint activities_kind_check check (kind in ('TURNAROUND', 'DATED', 'EXTERNAL')),
    constraint activities_dates_check check (
        (kind = 'TURNAROUND' and turnaround_days > 0 and scheduled_date is null and term_days is null)
        or (kind = 'DATED' and scheduled_date is not null and turnaround_days is null and due_date is null and term_days is null)
        or (kind = 'EXTERNAL' and turnaround_days is null and due_date is null and scheduled_date is null)
    )
);

create index if not exists idx_activities_client on activities (client_id, done, start_date);
create index if not exists idx_activities_open on activities (tenant_id) where not done;

-- History rows about an Activity go with it.
alter table activity_history
    add constraint activity_history_activity_fk foreign key (activity_id) references activities(id) on delete cascade;

alter table activities enable row level security;

drop policy if exists activities_tenant_context on activities;
create policy activities_tenant_context on activities
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

alter table activities force row level security;
