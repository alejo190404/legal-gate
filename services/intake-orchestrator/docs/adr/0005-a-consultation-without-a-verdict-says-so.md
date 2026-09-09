# ADR-0005: A Consultation that arrives without a Verdict says so

- Status: Accepted
- Date: 2026-09-09

## Context

ADR-0001 made Diagnostics the gate on scheduling, and the gate fails open: when the model cannot be
reached, or answers unusably, the matter proceeds as accepted rather than being swallowed by an
outage. That decision is right and is not revisited here.

What the failure looked like afterwards was wrong. The fail-open path wrote a sentence of LegalGate's
own — "Diagnostics no estuvo disponible tras reintentar durante una hora" — into
`diagnostics_sessions.reason`, the same column that otherwise holds the model's justification for
its Verdict. The console renders that column as **Motivo**. So a firm reading Motivo was reading the
model's reasoning about their potential client most of the time, and LegalGate's own infrastructure
status the rest of the time, with nothing to tell the two apart.

Worse, the sentence was frequently a lie. Three separate paths reached it: Diagnostics unreachable,
Diagnostics reachable but answering unusably, and Classification unreachable while scheduling a
matter the model *had* already accepted. All three claimed Diagnostics was unavailable. The second
and third produce clean provider logs — the service answered — so a firm checking whether anything
was down would find nothing wrong and conclude the console was lying to them. It was.

Underneath both problems is the same modelling error. The glossary defines a Verdict as the outcome
LegalGate *reaches*. A fail-open session reaches none, yet it stored `verdict = accept` and a
`reason`, making it indistinguishable in the database from a matter the model genuinely accepted.

## Decision

`reason` holds the model's words about the matter, and nothing else.

Why a Consultation arrived without a Verdict is recorded separately, on
`diagnostics_sessions.unfiltered_cause`, as one of a closed set of causes:
`DIAGNOSTICS_UNAVAILABLE`, `DIAGNOSTICS_INVALID_RESPONSE`, `CLASSIFICATION_UNAVAILABLE`. Null means a
Verdict was reached. The console renders it as its own **Aviso** row, badged `SIN FILTRAR` — or
`SIN CLASIFICAR`, since a classification failure means the Verdict *was* reached and only the
routing is missing.

The retry ladder is 5m/10m/15m: half an hour, not the hour it was. The budget is spent out of a
potential client's patience waiting on a first reply, not out of compute.

## Consequences

An Unfiltered Acceptance is now a first-class fact a firm can see, filter on and audit, rather than
a sentence in a free-text field. That matters more than the display bug it fixes: a matter nobody
qualified is exactly the one a human should look at hardest, and until now the only trace of it was
prose that also happened to be wrong.

Sessions resolved before this migration keep the old sentence in `reason` and have a null
`unfiltered_cause`. They are not backfilled — the sentence conflated three causes, so there is
nothing faithful to backfill *to*, and inventing one would be worse than leaving history legible as
what it was.

The auto-responder and max-rounds messages stay in `reason` on purpose. They read like LegalGate
prose but they describe what happened to *the matter* — an automated sender, a potential client who
never supplied what the firm asked for — which is exactly what a firm reads Motivo to learn. The
console "accept now" escape hatch keeps its message there too: it is the firm's own action, and the
firm knows it took it.

## Alternatives considered

**Hide the message in the console.** The cheapest fix and the worst one. The information is real and
safety-relevant — this matter was never qualified — and hiding it would leave a fail-open accept
visually identical to a genuine one.

**Infer it from `status`, `verdict` and a null `reason`.** No migration, but it rests on the model
never returning an accept with a blank reason. "This was never vetted" is not a signal for a law
firm to derive from a coincidence of three nullable columns.

**A new `status` value.** `ACCEPTED_UNFILTERED` would have broken the check constraint, ADR-0001's
documented lifecycle, and every reader of `status`, to encode one orthogonal bit.
