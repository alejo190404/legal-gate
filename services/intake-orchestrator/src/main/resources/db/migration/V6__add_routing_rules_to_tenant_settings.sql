alter table tenant_settings
    add column if not exists routing_rules jsonb not null default '[]'::jsonb;

update tenant_settings
set routing_rules = jsonb_build_array(
        jsonb_build_object(
            'name', 'Default intake route',
            'urgentKeywords', coalesce(urgent_keywords, '[]'::jsonb),
            'consultationWindows', coalesce(consultation_windows, '[]'::jsonb),
            'destinationEmail', destination_email
        )
    )
where routing_rules = '[]'::jsonb;

alter table tenant_settings
    drop constraint if exists tenant_settings_routing_rules_array;

alter table tenant_settings
    add constraint tenant_settings_routing_rules_array
    check (jsonb_typeof(routing_rules) = 'array');
