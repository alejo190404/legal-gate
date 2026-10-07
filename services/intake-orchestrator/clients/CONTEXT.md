# Clients — Glossary

Ubiquitous language for the clients context: the people a firm has taken on, and the work it does for them. Lives in the intake-orchestrator service beside Intake, but speaks its own language — Intake is about people entering a firm, this is about people inside it. Glossary only: no implementation detail, no spec.

## Client

A person or organization the firm has taken on: an attorney-client relationship exists. Distinct from the potential client of a Consultation. A Client comes from an Engagement, or is entered directly by firm staff — firms arrive at LegalGate with clients already on their books. A Client never stops being one: a Client with no open Activities is idle, not former, and is still recognized when they write again a year later. The only way out is deletion, after which LegalGate no longer knows them.
_Avoid_: customer, lead, contact

## Engagement

The firm formally taking on the potential client of a Consultation, which makes them a Client. The counterpart of Intake's Non-Engagement Notice. It is a deliberate act by firm staff, never inferred, and it records who the Client is rather than copying the Consultation blindly.
_Avoid_: conversion, onboarding, signup

## Contact Address

An email address the firm has on file for a Client. A Client may have several — a corporate client writes in from its general counsel, its CEO and an assistant. Mail from a Contact Address is the Client's; mail from anywhere else is not, whatever name it signs with.

## Responsible Lawyer

The lawyer who owns the relationship with a Client. Client mail LegalGate does not answer on its own goes to them. Always an active lawyer of the firm: a lawyer cannot leave while still responsible for anyone.

## Assigned Lawyer

The lawyer doing a given Activity. Defaults to the Client's Responsible Lawyer. Nudges about stale work go to them. The Assigned Lawyer does the work; firm staff keep the Activity's record — the two need not be the same person.

## Activity Type

A kind of work the firm does for Clients, from the firm's own catalog: the firm names it and sets its default Turnaround or term. Every Activity Type is one of three kinds:

- **Turnaround** — the firm controls how long it takes: drafting a contract, a legal opinion. Expected completion follows from its start and its Turnaround.
- **Dated** — happens on a set day: a meeting, a hearing, a notarial signing.
- **External** — the firm's part is done and someone outside it holds the clock: a court ruling on a tutela, an entity answering a derecho de petición, a trademark at the SIC. May carry a statutory term in Business Days; never a promise of the firm's. Past its term it is not Overdue — the firm is waiting, and says so.

An Activity Type is retired, never removed, once work of that type exists.

## Activity

One piece of work of a given Activity Type, for one Client. It is open or done; nothing else, and done is final. How long it has run and how long remains are read from its start and its Turnaround, date or term — never reported by a lawyer. An Activity keeps the Turnaround it was created with: changing the firm's catalog never moves a promise already made.

## Turnaround

The business days a firm commits to for completing an Activity of a Turnaround kind. A promise about the firm's own work on a Client's behalf. A single Activity may carry a due date fixed from outside instead — a legal term for answering a lawsuit — and then that date is the promise. Not the SLA on an Urgency, which is a promise about how fast the firm first responds to a matter it has taken.
_Avoid_: SLA (for Activities)

## Overdue

An open Turnaround or Dated Activity whose own date has passed: the firm is late, or nobody closed it. LegalGate never tells a Client on its own that either happened; an Overdue Activity puts the whole Status Inquiry in front of a lawyer. External Activities are never Overdue — the clock is not the firm's.

## Status Inquiry

A Client's email asking how their work is going. The only Client email LegalGate answers on its own, and the answer covers every open Activity. Every other Client email goes to the Responsible Lawyer. The answer goes to the Contact Address it came from, never to whatever reply address the inquiry asks for.

## Activity History

The record of every change made to an Activity or a Client — what changed, from what, to what, when, and by which firm user. Internal to the firm; a Client never sees it. Changes to Contact Addresses matter most, since adding one grants that address the firm's answers.
_Avoid_: audit log, changelog

## Client Correspondence

Every email a Client sent the firm, with what LegalGate did about it: answered it, passed it to a lawyer, or held it back because something was Overdue — and the exact words of any answer it sent, or would have sent while automatic answers are off.
_Avoid_: inbox, mail log
