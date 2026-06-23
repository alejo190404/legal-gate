update tenant_settings
set routing_rules = coalesce((
    select jsonb_agg(
            rule.value
            || jsonb_build_object(
                'description', coalesce(rule.value->>'description', ''),
                'urgencyLevels', case
                    when jsonb_typeof(rule.value->'urgencyLevels') = 'array'
                        and jsonb_array_length(rule.value->'urgencyLevels') > 0
                    then rule.value->'urgencyLevels'
                    else urgency_levels
                end
            )
            order by rule.ordinality
        )
    from jsonb_array_elements(routing_rules) with ordinality as rule(value, ordinality)
), '[]'::jsonb);

with urgency_union as (
    select tenant_id, coalesce(jsonb_agg(level order by first_seen), '["NORMAL", "URGENT"]'::jsonb) as levels
    from (
        select tenant_settings.tenant_id,
               urgency.level,
               min(rule.ordinality * 100000 + urgency.ordinality) as first_seen
        from tenant_settings
        cross join jsonb_array_elements(tenant_settings.routing_rules) with ordinality as rule(value, ordinality)
        cross join jsonb_array_elements_text(rule.value->'urgencyLevels') with ordinality as urgency(level, ordinality)
        where urgency.level is not null and btrim(urgency.level) <> ''
        group by tenant_settings.tenant_id, urgency.level
    ) deduplicated_levels
    group by tenant_id
)
update tenant_settings
set urgency_levels = urgency_union.levels
from urgency_union
where tenant_settings.tenant_id = urgency_union.tenant_id;
