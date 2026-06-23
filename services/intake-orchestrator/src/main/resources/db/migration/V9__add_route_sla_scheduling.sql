create table if not exists lawyers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    display_name text not null,
    email text not null,
    active bool not null default true,
    default_event_duration_minutes integer not null default 60,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint lawyers_duration_positive check (default_event_duration_minutes > 0)
);

create unique index if not exists idx_lawyers_tenant_lower_email_unique
    on lawyers (tenant_id, lower(email));

create table if not exists lawyer_availability_windows (
    id uuid primary key default gen_random_uuid(),
    lawyer_id uuid not null references lawyers(id) on delete cascade,
    weekday integer not null,
    start_time time not null,
    end_time time not null,
    timezone text not null default 'America/Bogota',
    constraint lawyer_availability_weekday_check check (weekday between 1 and 7),
    constraint lawyer_availability_time_check check (start_time < end_time)
);

alter table consultations
    add column if not exists event_id uuid;

create table if not exists events (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    lawyer_id uuid references lawyers(id) on delete set null,
    consultation_id uuid unique references consultations(id) on delete cascade,
    route_name text,
    route_id_snapshot text,
    urgency_name text not null,
    sla_days integer not null,
    sla_deadline timestamptz not null,
    priority_score integer not null,
    scheduled_start timestamptz,
    scheduled_end timestamptz,
    status text not null,
    source text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint events_sla_days_non_negative check (sla_days >= 0),
    constraint events_status_check check (status in ('TENTATIVE', 'CONFIRMED', 'MANUAL', 'NEEDS_MANUAL_SCHEDULING')),
    constraint events_source_check check (source in ('LEGALGATE', 'MANUAL')),
    constraint events_scheduled_range_check check (scheduled_start is null or scheduled_end is null or scheduled_start < scheduled_end)
);

alter table consultations
    drop constraint if exists consultations_event_id_fkey;

alter table consultations
    add constraint consultations_event_id_fkey foreign key (event_id) references events(id) on delete set null;

create index if not exists idx_lawyers_tenant_active on lawyers (tenant_id, active);
create index if not exists idx_lawyer_availability_lawyer_weekday on lawyer_availability_windows (lawyer_id, weekday);
create index if not exists idx_events_tenant_lawyer_schedule on events (tenant_id, lawyer_id, scheduled_start, scheduled_end);
create index if not exists idx_events_tenant_priority on events (tenant_id, priority_score desc, sla_deadline asc);

alter table lawyers enable row level security;
alter table lawyer_availability_windows enable row level security;
alter table events enable row level security;
drop policy if exists lawyers_tenant_context on lawyers;
create policy lawyers_tenant_context on lawyers
    for all
    using (tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true)))
    with check (tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true)));

drop policy if exists lawyer_availability_tenant_context on lawyer_availability_windows;
create policy lawyer_availability_tenant_context on lawyer_availability_windows
    for all
    using (lawyer_id in (
        select lawyers.id from lawyers
        join tenants on tenants.id = lawyers.tenant_id
        where tenants.slug = current_setting('app.tenant_slug', true)
    ))
    with check (lawyer_id in (
        select lawyers.id from lawyers
        join tenants on tenants.id = lawyers.tenant_id
        where tenants.slug = current_setting('app.tenant_slug', true)
    ));

drop policy if exists events_tenant_context on events;
create policy events_tenant_context on events
    for all
    using (tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true)))
    with check (tenant_id in (select id from tenants where slug = current_setting('app.tenant_slug', true)));

insert into lawyers (tenant_id, display_name, email, active, default_event_duration_minutes)
select t.id,
       coalesce(nullif(split_part(s.destination_email, '@', 1), ''), 'Default lawyer'),
       s.destination_email,
       true,
       60
from tenant_settings s
join tenants t on t.id = s.tenant_id
where s.destination_email is not null
  and not exists (
      select 1 from lawyers l where l.tenant_id = s.tenant_id and lower(l.email) = lower(s.destination_email)
  );

insert into lawyer_availability_windows (lawyer_id, weekday, start_time, end_time, timezone)
select l.id, weekday.day, '09:00'::time, '17:00'::time, 'America/Bogota'
from lawyers l
cross join generate_series(1, 5) as weekday(day)
where not exists (select 1 from lawyer_availability_windows w where w.lawyer_id = l.id);

update tenant_settings s
set routing_rules = coalesce((
    select jsonb_agg(
        rule.value
        || jsonb_build_object(
            'lawyerId', coalesce(rule.value->>'lawyerId', lawyer_match.id::text),
            'urgencyDefinitions', case
                when jsonb_typeof(rule.value->'urgencyDefinitions') = 'array'
                    and jsonb_array_length(rule.value->'urgencyDefinitions') > 0
                then rule.value->'urgencyDefinitions'
                else (
                    select jsonb_agg(jsonb_build_object(
                        'name', level.value,
                        'rank', level.ordinality,
                        'slaDays', case when upper(level.value) = 'URGENT' then 1 else 5 end,
                        'active', true
                    ) order by level.ordinality)
                    from jsonb_array_elements_text(
                        case
                            when jsonb_typeof(rule.value->'urgencyLevels') = 'array'
                                and jsonb_array_length(rule.value->'urgencyLevels') > 0
                            then rule.value->'urgencyLevels'
                            else s.urgency_levels
                        end
                    ) with ordinality as level(value, ordinality)
                )
            end
        )
        order by rule.ordinality
    )
    from jsonb_array_elements(s.routing_rules) with ordinality as rule(value, ordinality)
    left join lateral (
        select l.id
        from lawyers l
        where l.tenant_id = s.tenant_id
          and lower(l.email) = lower(coalesce(rule.value->>'destinationEmail', s.destination_email))
        order by l.created_at
        limit 1
    ) lawyer_match on true
), '[]'::jsonb);

alter table lawyers force row level security;
alter table lawyer_availability_windows force row level security;
alter table events force row level security;
