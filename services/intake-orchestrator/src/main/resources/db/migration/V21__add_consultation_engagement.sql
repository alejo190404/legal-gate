-- Engagement: the Client a Consultation's potential client became. Set at most once; cleared when
-- the Client is deleted, so the Consultation stays and only forgets whom it became.
alter table consultations
    add column if not exists engaged_client_id uuid references clients(id) on delete set null;
