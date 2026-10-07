-- Client Correspondence: every email a Client sent the firm, and what LegalGate did about it.
create table if not exists client_correspondence (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    -- The worker cannot read tenants under RLS, so the slug travels with the row.
    tenant_slug text not null,
    client_id uuid not null references clients(id) on delete cascade,
    -- The Contact Address that matched: the only address an answer may go to (ADR-0007).
    contact_address text not null,
    message_id text not null,
    subject text,
    -- What the Client wrote. Nulled after a year; outcome and answer_text are kept.
    body text,
    received_at timestamptz not null,
    intent text,
    -- Null while the worker has yet to act on it.
    outcome text,
    answer_text text,
    purged_at timestamptz,
    attempts integer not null default 0,
    next_attempt_at timestamptz,
    last_error text,
    created_at timestamptz not null default now(),
    constraint client_correspondence_outcome_check check (
        outcome is null or outcome in ('ANSWERED', 'WOULD_HAVE_ANSWERED', 'FORWARDED', 'HELD_BACK', 'IGNORED')
    ),
    constraint client_correspondence_intent_check check (
        intent is null or intent in ('status_inquiry', 'needs_lawyer')
    )
);

-- A webhook redelivery of the same email is handled once.
create unique index if not exists uq_client_correspondence_message
    on client_correspondence (tenant_id, message_id);

create index if not exists idx_client_correspondence_client
    on client_correspondence (client_id, received_at);

create index if not exists idx_client_correspondence_due
    on client_correspondence (next_attempt_at)
    where outcome is null and next_attempt_at is not null;

alter table client_correspondence enable row level security;

drop policy if exists client_correspondence_tenant_context on client_correspondence;
create policy client_correspondence_tenant_context on client_correspondence
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

alter table client_correspondence force row level security;

-- Client mail belongs to no Consultation. It threads on the Client's own Message-ID, read at send
-- time through correspondence_id, so the outbox still carries no message identifiers (ADR-0004).
alter table notification_outbox
    alter column consultation_id drop not null,
    add column if not exists correspondence_id uuid references client_correspondence(id) on delete cascade,
    add column if not exists reply_to text;

alter table notification_outbox drop constraint if exists notification_outbox_type_check;
alter table notification_outbox add constraint notification_outbox_type_check
    check (notification_type in (
        'CONSULTATION_SCHEDULED', 'CONSULTATION_RESCHEDULED',
        'DIAGNOSTICS_QUESTION', 'NON_ENGAGEMENT_NOTICE',
        'CLIENT_MAIL_FORWARD', 'CLIENT_RECEIPT', 'CLIENT_STATUS_ANSWER', 'ACTIVITY_NUDGE'
    ));
