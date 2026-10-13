-- A reminder names a Client and its work, so it goes when the Activity goes (Ley 1581).
alter table notification_outbox
    add column if not exists activity_id uuid references activities(id) on delete cascade;

-- The Activity History is append-only. Rows leave only with their Client or Activity, through
-- the cascade, which runs one trigger level deeper than a direct statement.
create or replace function activity_history_append_only() returns trigger as $$
begin
    if tg_op = 'DELETE' and pg_trigger_depth() > 1 then
        return old;
    end if;
    raise exception 'activity_history is append-only';
end;
$$ language plpgsql;

drop trigger if exists activity_history_append_only on activity_history;
create trigger activity_history_append_only
    before update or delete on activity_history
    for each row execute function activity_history_append_only();
