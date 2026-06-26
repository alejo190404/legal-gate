alter table lawyers
    add column if not exists meeting_url text;

alter table events
    add column if not exists meeting_url text,
    add column if not exists scheduled_within_sla bool;

update events
set scheduled_within_sla = case
    when scheduled_end is null then null
    else scheduled_end <= sla_deadline
  end
where scheduled_within_sla is null;

create table if not exists notification_outbox (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    tenant_slug text not null,
    consultation_id uuid not null references consultations(id) on delete cascade,
    event_id uuid not null references events(id) on delete cascade,
    notification_type text not null,
    recipient_role text not null,
    recipient_email text not null,
    subject text not null,
    body text not null,
    ics_content text not null,
    status text not null default 'PENDING',
    attempts integer not null default 0,
    provider_message_id text,
    last_error text,
    next_attempt_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint notification_outbox_type_check check (notification_type in ('CONSULTATION_SCHEDULED', 'CONSULTATION_RESCHEDULED')),
    constraint notification_outbox_role_check check (recipient_role in ('LAWYER', 'CLIENT')),
    constraint notification_outbox_status_check check (status in ('PENDING', 'SENDING', 'SENT', 'FAILED', 'DEAD'))
);

create unique index if not exists idx_notification_outbox_dedupe
    on notification_outbox (tenant_id, consultation_id, event_id, notification_type, recipient_role)
    where status in ('PENDING', 'SENDING', 'FAILED');

create index if not exists idx_notification_outbox_pending
    on notification_outbox (status, next_attempt_at, created_at);

create unique index if not exists idx_notification_outbox_provider_message_id
    on notification_outbox (provider_message_id)
    where provider_message_id is not null;

alter table notification_outbox enable row level security;

drop policy if exists notification_outbox_tenant_context on notification_outbox;
create policy notification_outbox_tenant_context on notification_outbox
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

alter table notification_outbox force row level security;
