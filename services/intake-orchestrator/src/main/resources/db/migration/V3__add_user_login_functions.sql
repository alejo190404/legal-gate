create or replace function find_active_user_for_login(login_email text)
returns table (
    email text,
    tenant_id text,
    display_name text,
    role text,
    hashed_password text
)
language sql
security definer
set search_path = public
as $$
    select u.email, t.slug as tenant_id, u.full_name as display_name, u.role, u.hashed_password
    from users u
    join tenants t on t.id = u.firm_id
    where u.email = lower(trim(login_email))
      and u.is_active = true
    limit 1;
$$;

create or replace function record_user_login(login_email text)
returns void
language sql
security definer
set search_path = public
as $$
    update users
    set last_login_at = now()
    where email = lower(trim(login_email))
      and is_active = true;
$$;

comment on function find_active_user_for_login(text) is 'RLS-safe lookup used by prototype email/password login. Password verification remains in application code.';
comment on function record_user_login(text) is 'RLS-safe last-login update for authenticated prototype firm-owner users.';
