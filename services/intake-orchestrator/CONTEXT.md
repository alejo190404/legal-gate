# Intake — Glossary

Ubiquitous language for the intake context. Glossary only: no implementation detail, no spec.

## Consultation

A potential client's legal matter as it enters a firm. Created from an inbound email to the firm's intake address, or entered directly in the console by firm staff.

## Diagnostics

The exchange that happens **before** a Consultation is worth scheduling: LegalGate replies to the potential client, asks for what the firm needs to assess the matter, and decides whether the matter is one the firm takes.

Diagnostics runs only on Consultations that arrived by inbound email. A Consultation under Diagnostics has no Event and no lawyer time reserved.

Not to be confused with the Render keepalive script `health_monitor.py`, which is unrelated to this context.

## Diagnostics Prompt

Firm-authored free text describing which matters the firm takes and what information it needs before it will look at one. It is the firm's own words, not a fixed questionnaire — LegalGate hands it to the LLM, which decides what to ask the potential client next.

## Verdict

The outcome LegalGate reaches on a Consultation under Diagnostics: the matter is ready to proceed, more information is needed from the potential client, or the firm does not take matters of this kind.

## Unfiltered Acceptance

A Consultation that reaches the firm without a Verdict, because Diagnostics could not produce one and
LegalGate let the matter through rather than swallow it. It is not an accept — it is the absence of a
decision, recorded as such, so the firm knows the matter was never qualified.
_Avoid_: fail-open accept, auto-accept

## Diagnostics Round

One exchange within Diagnostics: LegalGate asks, the potential client answers. Rounds are capped — a potential client who never supplies what the firm needs, or who stops replying, leaves the Consultation abandoned rather than scheduled.

## Reply Token

The unguessable identifier that ties a potential client's reply back to the Consultation it belongs to. It travels in the address the client replies to, and is what stops a reply from being mistaken for a brand-new Consultation.

## Acknowledgment

The opening line of a Diagnostics message that repeats back, in the potential client's own terms, what they wrote in about. It is a receipt of the message, never a characterization of the matter — naming a legal concept the potential client did not name would read as an assessment.

## Consultation Thread

Every message exchanged with a potential client about one Consultation, seen as the single email conversation it is. The potential client's first email is what the thread hangs from; nothing LegalGate sends about that Consultation stands on its own.

## Firm Voice

The identity a potential client sees on everything LegalGate sends them: the firm's name, signed by the firm. A potential client wrote to a firm and hears back from that firm — LegalGate is not a party to the correspondence and never appears in it. Distinct from what lawyers and firm staff receive, which is LegalGate's own product surface and is branded as such.

## Non-Engagement Notice

The message sent to a potential client whose matter the firm declines. It states that no attorney-client relationship was formed and that other counsel should be sought promptly. It never contains legal advice.

## Classification

Assigning a Consultation to a Routing Rule and an Urgency. Distinct from Diagnostics: Classification decides *who in the firm* handles the matter and *how fast*; Diagnostics decides *whether the firm takes it at all*.

## Routing Rule

A firm-configured lane for Consultations: a name, keywords, a destination lawyer, and its own Urgency definitions.

## Urgency

A named priority level on a Routing Rule, carrying an SLA in business days and a rank used to break ties when lawyer slots are contested.

## Event

A reserved block of a lawyer's time for a Consultation, held tentatively until confirmed. Created only once a Consultation is cleared to be scheduled.

## Tenant

One subscribing firm. Every Consultation, Routing Rule, lawyer and Event belongs to exactly one Tenant.
