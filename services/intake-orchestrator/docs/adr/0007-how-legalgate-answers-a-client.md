# ADR-0007: How LegalGate answers a Client

- Status: Accepted
- Date: 2026-10-07

## Context

Until now, every email reaching a firm's intake address without a Reply Token became a new
Consultation and entered Diagnostics. A Client asking "how is my contract going?" was qualified like
a stranger and could be sent a Non-Engagement Notice by the firm that represents them.

Firms want the opposite: Clients asking about the status of their work should get an answer without
a lawyer stopping to write one. That answer is the firm, in writing, telling a represented person
about their legal matter. Two things make that dangerous. Inbound mail is unauthenticated —
mail-ingress checks no SPF, DKIM or DMARC — so `From` can be forged by anyone who knows a Client's
address. And a status is only as true as the record behind it: an Activity nobody closed reads as
late work.

## Decision

Mail is matched in this order: a Reply Token for a pending Diagnostics session, then a Contact
Address, then a new Consultation. Mail from a Contact Address belongs to the Clients context and
never reaches Diagnostics.

Jev classifies each Client email as a Status Inquiry or as something that needs a lawyer. Only a
Status Inquiry is answered automatically. Everything else, and every Status Inquiry from a Client
with no open Activities, is forwarded to the Responsible Lawyer with `Reply-To` set to the Client,
and the Client gets a receipt in the Firm Voice. A Jev outage forwards; it never answers.

The answer goes to the Contact Address the inquiry came from, never to its `From` display or its
`Reply-To`. A forger receives nothing; at worst the real Client gets a status they did not ask for.

The answer is a fixed template filled from the record — every open Activity with its label, start,
and expected date, hearing date or statutory term. No model writes any of it.

If any open Activity is Overdue, nothing is answered automatically: the whole inquiry goes to a
person and the Assigned Lawyer is nudged. LegalGate never tells a Client on the firm's behalf that
the firm is late.

Automatic answers are a per-tenant setting, off by default. While off, Client Correspondence still
records the exact answer LegalGate would have sent, so a firm can judge it before switching it on.

## Consequences

A Client writing from an address the firm does not have on file is a stranger to LegalGate: their
email becomes a Consultation and can still draw a Non-Engagement Notice. The mitigation is that
Engagement can attach a Consultation to an existing Client, adding the address. Closing the gap
fully would mean asking a model whether a stranger is already a client, which is trivially gamed.

A Contact Address is unique within a Tenant. Shared addresses — a spouse, an outsourced accountant —
cannot be on file for two Clients, because a Status Inquiry from one would disclose the other's
matters.

What a Client wrote is purged from Client Correspondence after one year. What LegalGate did, and
the exact words it sent on the firm's behalf, are kept while the Client exists — the same split as
ADR-0003, for the same reason: the firm's accountability record is what is worth keeping. Deleting a
Client removes all of it and frees their Contact Addresses, so that person goes through Diagnostics
if they write again.

## Alternatives considered

**Reply to the sender after checking SPF/DKIM/DMARC.** Correct, but new verification code in
mail-ingress to protect a path that replying to the address on file already protects for free.

**Have Gemini write the answer.** Nicer prose, but every sentence of a status answer is a fact from
the record, and ADR-0004 already keeps fixed sentences out of the model's hands.

**Answer Overdue Activities with soft wording.** "Taking a little longer than expected" is still an
automated admission, and the most common cause of Overdue is work that is done but was never closed.
