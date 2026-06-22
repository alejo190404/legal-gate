alter table tenant_settings
    add column if not exists urgency_levels jsonb not null default '["NORMAL","URGENT"]'::jsonb;

alter table tenant_settings
    drop constraint if exists tenant_settings_urgency_levels_array;

alter table tenant_settings
    add constraint tenant_settings_urgency_levels_array
    check (jsonb_typeof(urgency_levels) = 'array' and jsonb_array_length(urgency_levels) > 0);

alter table consultations
    add column if not exists consultation_type text,
    add column if not exists assigned_lawyer_email text,
    add column if not exists source_event_id text,
    add column if not exists source_message_id text;

create unique index if not exists idx_consultations_tenant_source_message_unique
    on consultations (tenant_id, source_message_id)
    where source_message_id is not null;
