-- A Consultation can reach the firm without a Verdict, when Diagnostics cannot produce one and
-- the matter proceeds unfiltered rather than being swallowed. The cause of that belongs beside
-- the session and not inside `reason`: `reason` carries the model's own words about the matter,
-- and a firm reading LegalGate's outage there has no way to tell the two apart.
alter table diagnostics_sessions
    add column if not exists unfiltered_cause text;

comment on column diagnostics_sessions.unfiltered_cause is
    'Null when a Verdict was reached. Otherwise why Diagnostics could not reach one.';
