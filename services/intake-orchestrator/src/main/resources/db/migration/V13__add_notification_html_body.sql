alter table notification_outbox
    add column if not exists html_body text;
