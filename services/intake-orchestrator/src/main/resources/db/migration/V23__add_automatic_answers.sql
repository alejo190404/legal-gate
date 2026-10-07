-- Status Inquiries answered on their own (ADR-0007). Off for every tenant until the firm opts in;
-- while off, Client Correspondence still records what would have been sent.
alter table tenant_settings
    add column if not exists automatic_answers boolean not null default false;
