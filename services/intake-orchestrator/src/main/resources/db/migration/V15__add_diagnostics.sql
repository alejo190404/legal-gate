-- Diagnostics: qualify inbound consultations before any lawyer time is committed.
-- A blank diagnostics_prompt leaves a tenant on the pre-diagnostics behaviour.
alter table tenant_settings
    add column if not exists diagnostics_prompt text,
    add column if not exists non_engagement_notice text;

create table if not exists diagnostics_sessions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    tenant_slug text not null,
    consultation_id uuid not null unique references consultations(id) on delete cascade,
    reply_token text not null unique,
    status text not null default 'PENDING',
    verdict text,
    reason text,
    extracted_summary text,
    -- The prompt as it read at decision time. Duplicated per session on purpose: it is the
    -- only way an old verdict stays interpretable after the firm rewrites its prompt.
    prompt_snapshot text not null,
    original_email jsonb,
    rounds integer not null default 0,
    attempts integer not null default 0,
    -- next_attempt_at null means "waiting on the potential client", not "due for work".
    next_attempt_at timestamptz,
    awaiting_reply_since timestamptz,
    last_error text,
    resolved_at timestamptz,
    transcript_purged_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint diagnostics_sessions_status_check
        check (status in ('PENDING', 'ACCEPTED', 'REJECTED', 'ABANDONED')),
    constraint diagnostics_sessions_verdict_check
        check (verdict is null or verdict in ('accept', 'ask', 'reject'))
);

create index if not exists idx_diagnostics_sessions_due
    on diagnostics_sessions (next_attempt_at)
    where status = 'PENDING' and next_attempt_at is not null;

create index if not exists idx_diagnostics_sessions_awaiting
    on diagnostics_sessions (awaiting_reply_since)
    where status = 'PENDING' and awaiting_reply_since is not null;

create index if not exists idx_diagnostics_sessions_resolved
    on diagnostics_sessions (resolved_at)
    where transcript_purged_at is null;

create table if not exists diagnostics_messages (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    session_id uuid not null references diagnostics_sessions(id) on delete cascade,
    role text not null,
    body text not null,
    created_at timestamptz not null default now(),
    constraint diagnostics_messages_role_check check (role in ('CLIENT', 'LEGALGATE'))
);

create index if not exists idx_diagnostics_messages_session
    on diagnostics_messages (session_id, created_at);

alter table diagnostics_sessions enable row level security;
alter table diagnostics_messages enable row level security;

drop policy if exists diagnostics_sessions_tenant_context on diagnostics_sessions;
create policy diagnostics_sessions_tenant_context on diagnostics_sessions
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

drop policy if exists diagnostics_messages_tenant_context on diagnostics_messages;
create policy diagnostics_messages_tenant_context on diagnostics_messages
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

alter table diagnostics_sessions force row level security;
alter table diagnostics_messages force row level security;

-- Diagnostics notifications belong to a consultation that has no event yet, carry no
-- calendar invite, and are sent from the tenant's token-bearing reply address.
alter table notification_outbox
    alter column event_id drop not null,
    alter column ics_content drop not null,
    add column if not exists from_email text;

alter table notification_outbox drop constraint if exists notification_outbox_type_check;
alter table notification_outbox add constraint notification_outbox_type_check
    check (notification_type in (
        'CONSULTATION_SCHEDULED', 'CONSULTATION_RESCHEDULED',
        'DIAGNOSTICS_QUESTION', 'NON_ENGAGEMENT_NOTICE'
    ));

-- Scheduling notifications stay deduped by event; diagnostics messages are one per round
-- and are inserted once, in the same transaction that advances the session.
drop index if exists idx_notification_outbox_dedupe;
create unique index if not exists idx_notification_outbox_dedupe
    on notification_outbox (tenant_id, consultation_id, event_id, notification_type, recipient_role)
    where status in ('PENDING', 'SENDING', 'FAILED') and event_id is not null;
