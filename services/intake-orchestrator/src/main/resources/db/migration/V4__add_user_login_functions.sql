drop policy if exists users_login_lookup on users;
create policy users_login_lookup on users
    for select
    using (current_setting('app.auth_lookup', true) = 'true');

drop policy if exists users_login_update on users;
create policy users_login_update on users
    for update
    using (current_setting('app.auth_lookup', true) = 'true')
    with check (current_setting('app.auth_lookup', true) = 'true');

create or replace function app_find_active_user_for_login(login_email text)
returns table (
    email text,
    tenant_id text,
    display_name text,
    role text,
    hashed_password text
)
language plpgsql
as $$
begin
    perform set_config('app.auth_lookup', 'true', true);

    return query
    select u.email,
           t.slug,
           u.full_name,
           u.role,
           u.hashed_password
    from users u
    join tenants t on t.id = u.firm_id
    where u.is_active = true
      and u.email = login_email
    limit 1;
end;
$$;

create or replace function app_record_user_login(login_email text)
returns void
language plpgsql
as $$
begin
    perform set_config('app.auth_lookup', 'true', true);

    update users
    set last_login_at = now()
    where email = login_email
      and is_active = true;
end;
$$;
