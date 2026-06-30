create table billing_plans (
    id uuid primary key default gen_random_uuid(),
    code text not null,
    version integer not null default 1,
    display_name text not null,
    description text,
    billing_interval text not null,
    price_cop numeric(14,2) not null,
    active boolean not null default true,
    display_order integer not null default 0,
    created_at timestamptz not null default now(),
    retired_at timestamptz,
    constraint billing_plans_code_version_unique unique (code, version),
    constraint billing_plans_code_normalized check (code = lower(code) and code ~ '^[a-z0-9][a-z0-9_-]*$'),
    constraint billing_plans_interval_check check (billing_interval in ('MONTHLY', 'YEARLY')),
    constraint billing_plans_price_check check (price_cop > 0),
    constraint billing_plans_retired_check check ((active and retired_at is null) or not active)
);

create unique index uq_billing_plans_active_code
    on billing_plans (code) where active;
create index idx_billing_plans_display
    on billing_plans (active, display_order, code, version desc);

create table coupons (
    id uuid primary key default gen_random_uuid(),
    code text not null,
    discount_type text not null,
    discount_value numeric(14,2) not null,
    duration text not null,
    duration_cycles integer,
    valid_from timestamptz,
    valid_until timestamptz,
    active boolean not null default true,
    max_redemptions integer,
    redemption_count integer not null default 0,
    created_at timestamptz not null default now(),
    retired_at timestamptz,
    constraint coupons_code_unique unique (code),
    constraint coupons_code_normalized check (code = upper(code) and code ~ '^[A-Z0-9][A-Z0-9_-]*$'),
    constraint coupons_type_check check (discount_type in ('FIXED', 'PERCENTAGE')),
    constraint coupons_value_check check (
        (discount_type = 'FIXED' and discount_value > 0)
        or (discount_type = 'PERCENTAGE' and discount_value > 0 and discount_value < 100)
    ),
    constraint coupons_duration_check check (
        (duration = 'REPEATING' and duration_cycles > 0)
        or (duration in ('ONCE', 'FOREVER') and duration_cycles is null)
    ),
    constraint coupons_validity_check check (valid_until is null or valid_from is null or valid_until > valid_from),
    constraint coupons_redemptions_check check (
        redemption_count >= 0 and (max_redemptions is null or max_redemptions > 0)
    )
);

create unique index uq_coupons_case_insensitive_code on coupons (upper(code));

alter table tenants
    add constraint tenants_id_slug_unique unique (id, slug);

create table subscriptions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    tenant_slug text not null,
    plan_id uuid not null references billing_plans(id),
    plan_code text not null,
    plan_name text not null,
    plan_interval text not null,
    plan_price_cop numeric(14,2) not null,
    coupon_id uuid references coupons(id),
    coupon_code text,
    coupon_type text,
    coupon_value numeric(14,2),
    coupon_duration text,
    coupon_duration_cycles integer,
    original_amount_cop numeric(14,2) not null,
    current_amount_cop numeric(14,2) not null,
    status text not null,
    provider_status text,
    provider_subscription_id text,
    provider_init_point text,
    payer_email text not null,
    current_period_start timestamptz,
    paid_through timestamptz,
    grace_deadline timestamptz,
    pending_expires_at timestamptz,
    canceled_at timestamptz,
    cancel_at_period_end boolean not null default false,
    approved_cycle_count integer not null default 0,
    amount_transition_pending boolean not null default false,
    amount_transition_attempts integer not null default 0,
    amount_transition_claimed_until timestamptz,
    amount_transition_error text,
    idempotency_key text not null,
    last_provider_sync_at timestamptz,
    reconciliation_claimed_until timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint subscriptions_plan_interval_check check (plan_interval in ('MONTHLY', 'YEARLY')),
    constraint subscriptions_status_check check (
        status in ('PENDING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'EXPIRED', 'REFUNDED')
    ),
    constraint subscriptions_amount_check check (
        original_amount_cop > 0 and current_amount_cop > 0
    ),
    constraint subscriptions_cycle_count_check check (approved_cycle_count >= 0),
    constraint subscriptions_idempotency_unique unique (tenant_id, idempotency_key),
    constraint subscriptions_tenant_identity_unique unique (id, tenant_id, tenant_slug),
    constraint subscriptions_tenant_identity_fk foreign key (tenant_id, tenant_slug)
        references tenants(id, slug)
);

create unique index uq_subscriptions_current_per_tenant
    on subscriptions (tenant_id)
    where status in ('PENDING', 'ACTIVE', 'PAST_DUE');
create unique index uq_subscriptions_provider_id
    on subscriptions (provider_subscription_id)
    where provider_subscription_id is not null;
create index idx_subscriptions_worker
    on subscriptions (status, pending_expires_at, grace_deadline, last_provider_sync_at);

create table subscription_payments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    tenant_slug text not null,
    subscription_id uuid not null references subscriptions(id) on delete cascade,
    provider_payment_id text not null,
    provider_authorized_payment_id text,
    provider_status text not null,
    amount_cop numeric(14,2),
    currency text not null default 'COP',
    paid_at timestamptz,
    period_start timestamptz,
    period_end timestamptz,
    raw_payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint subscription_payments_provider_unique unique (provider_payment_id),
    constraint subscription_payments_amount_check check (amount_cop is null or amount_cop >= 0),
    constraint subscription_payments_currency_check check (currency = 'COP'),
    constraint subscription_payments_tenant_identity_fk
        foreign key (subscription_id, tenant_id, tenant_slug)
        references subscriptions(id, tenant_id, tenant_slug) on delete cascade
);

create index idx_subscription_payments_subscription
    on subscription_payments (subscription_id, paid_at desc, created_at desc);

create table billing_webhook_events (
    id uuid primary key default gen_random_uuid(),
    provider_event_id text not null,
    event_type text not null,
    action text,
    resource_id text not null,
    request_id text,
    raw_body jsonb not null,
    status text not null default 'PENDING',
    processing_attempts integer not null default 0,
    last_error text,
    next_attempt_at timestamptz not null default now(),
    received_at timestamptz not null default now(),
    processed_at timestamptz,
    constraint billing_webhook_events_provider_unique unique (provider_event_id, event_type),
    constraint billing_webhook_events_status_check check (status in ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED', 'DEAD'))
);

create index idx_billing_webhook_events_pending
    on billing_webhook_events (status, next_attempt_at, received_at);

create or replace function prevent_billing_plan_mutation()
returns trigger
language plpgsql
as $$
begin
    if old.code is distinct from new.code
       or old.version is distinct from new.version
       or old.display_name is distinct from new.display_name
       or old.description is distinct from new.description
       or old.billing_interval is distinct from new.billing_interval
       or old.price_cop is distinct from new.price_cop
       or old.created_at is distinct from new.created_at then
        raise exception 'billing plans are immutable; create a new version instead';
    end if;
    return new;
end
$$;

create trigger billing_plans_immutable_fields
before update on billing_plans
for each row execute function prevent_billing_plan_mutation();

create or replace function app_billing_tenant_for_provider_subscription(p_provider_id text)
returns table (tenant_slug text, subscription_id uuid)
language sql
stable
security definer
set search_path = public
set row_security = off
as $$
    select s.tenant_slug, s.id
    from subscriptions s
    where s.provider_subscription_id = p_provider_id
    limit 1
$$;

create or replace function app_billing_tenant_for_external_reference(p_external_reference text)
returns table (tenant_slug text, subscription_id uuid)
language sql
stable
security definer
set search_path = public
set row_security = off
as $$
    select s.tenant_slug, s.id
    from subscriptions s
    where s.id::text = p_external_reference
    limit 1
$$;

revoke all on function app_billing_tenant_for_provider_subscription(text) from public;
revoke all on function app_billing_tenant_for_external_reference(text) from public;
grant execute on function app_billing_tenant_for_provider_subscription(text) to current_user;
grant execute on function app_billing_tenant_for_external_reference(text) to current_user;

alter table subscriptions enable row level security;
alter table subscription_payments enable row level security;
alter table subscriptions force row level security;
alter table subscription_payments force row level security;

create policy subscriptions_tenant_context on subscriptions
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

create policy subscription_payments_tenant_context on subscription_payments
    for all
    using (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    )
    with check (
        current_setting('app.tenant_slug', true) = '__worker__'
        or tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true))
    );

comment on table billing_plans is 'Versioned plan catalog. Price changes require a new row/version.';
comment on table coupons is 'Database-managed coupon catalog. Codes are stored uppercase.';
comment on table billing_webhook_events is 'Idempotent Mercado Pago webhook inbox; contains no tenant-scoped business data.';
