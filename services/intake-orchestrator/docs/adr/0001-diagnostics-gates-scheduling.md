# ADR-0001: Diagnostics gates scheduling

- Status: Accepted
- Date: 2026-08-10

## Context

Every inbound email produced a scheduled Event immediately: `createConsultationFromInboundEmail` classified the message and called `scheduleEvent` in the same breath, reserving a lawyer slot, computing an SLA deadline, and mailing a calendar invite to both the lawyer and the sender.

That treats every message arriving at a firm's intake address as a matter the firm wants. In practice much of it is out of scope, or too thin to assess. Slots were burned on matters the firm would never take, and `findSlot` could displace another tentative Event to make room for one of them.

Firms want a qualification step first, in their own words, before any of their time is committed.

## Decision

A Consultation from inbound email is created without an Event, in `DIAGNOSTICS_PENDING`. No lawyer time is reserved and no calendar invite is sent while Diagnostics runs.

An Event is created only after a Verdict of accept. Diagnostics runs before Classification, so route and urgency are decided against complete information rather than a thin first email.

The SLA clock anchors to the moment of acceptance, not to arrival.

Diagnostics applies to inbound email only. Console-created Consultations are entered by firm staff who have already read the matter, and schedule immediately as before.

A tenant with no Diagnostics Prompt gets the old behaviour unchanged. This is both the migration path and the kill switch.

## Consequences

Consultations now have a lifecycle rather than a single arrival state: `DIAGNOSTICS_PENDING` resolves to `RECEIVED`, `DIAGNOSTICS_REJECTED`, or `DIAGNOSTICS_ABANDONED`. Anything reading `status` or assuming a non-null `eventId` must handle a Consultation that has neither.

Anchoring the SLA at acceptance means the clock no longer reflects how long the potential client has been waiting overall — a matter can spend a week in Diagnostics and still be within SLA. The SLA is a promise about the firm's response time to matters it has taken, and the firm cannot respond to a matter still being qualified.

Firms get a console list filtered by status, and an "accept now" action to end Diagnostics early. Without that escape hatch, an auto-rejecting system is not safe to switch on.

## Alternatives considered

**Schedule immediately, cancel on reject.** Retracting a calendar invite already in the client's inbox is worse than never sending one, and cancellation would have to unwind the displacement `findSlot` performs on other tentative Events.

**Per-route Diagnostics Prompts.** Attractive — a criminal lane needs different information than a labor lane — but it requires choosing a route before knowing whether the matter is even in scope. Revisit if firms ask for it.
