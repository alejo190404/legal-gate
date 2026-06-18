alter table tenant_settings
    add column if not exists intake_email text;

create unique index if not exists idx_tenant_settings_intake_email_unique
    on tenant_settings (lower(intake_email))
    where intake_email is not null;

drop policy if exists tenants_intake_email_lookup on tenants;
create policy tenants_intake_email_lookup on tenants
    for select
    using (current_setting('app.intake_email_lookup', true) = 'true');

drop policy if exists tenant_settings_intake_email_lookup on tenant_settings;
create policy tenant_settings_intake_email_lookup on tenant_settings
    for select
    using (current_setting('app.intake_email_lookup', true) = 'true');

create or replace function app_find_tenant_for_intake_email(email_address text)
returns text
language plpgsql
as $$
declare
    tenant_slug text;
begin
    perform set_config('app.intake_email_lookup', 'true', true);

    select t.slug
    into tenant_slug
    from tenant_settings s
    join tenants t on t.id = s.tenant_id
    where lower(s.intake_email) = lower(trim(email_address))
    limit 1;

    return tenant_slug;
end;
$$;
