# ADR-0006: Verdicts and routing are decided by Jev; Gemini only writes

- Status: Accepted
- Date: 2026-09-29

## Context

Every Diagnostics Round and every Classification was a full Gemini call returning both the
decision (Verdict, Routing Rule, Urgency) and prose (the question, the Acknowledgment, the reason,
the summary). An accepted Consultation cost two Gemini calls, and a rejected one cost one call to
produce text that nobody outside the firm reads.

Jev (TypeSafe's System One model) answers typed questions directly: pick one option, place on an
ordered scale, or judge true/false, with probabilities. It writes no text. The goal is lower cost
and latency on the decisions, which are almost all of the traffic.

## Decision

The decision and the prose are split between two models.

- **Verdict**: Jev, as a single choice among accept / ask / reject. It reads the firm's Diagnostics
  Prompt, the original email and the exchange so far. Its answer is final: no confidence gate and
  no second opinion from Gemini.
- **Prose**: Gemini, only when someone will read it. On *ask* it writes the question and the
  Acknowledgment, and on *accept* it writes the summary and the reason. Gemini is told the Verdict
  and cannot change it. A *reject* makes no Gemini call.
- **Classification**: Jev chooses the Routing Rule, and within that rule's own Urgencies, how fast.
  Gemini still writes the summary and client name on every path, including after Diagnostics
  accepts. That summary is what the potential client reads in the scheduling receipt, and the
  Diagnostics summary can't replace it: it is internal and may name LegalGate (ADR-0004).
- Classification stays a separate call from the Verdict, not folded into the same Jev request.
  Folding them together would mean storing the route on the Diagnostics session so an interrupted
  acceptance could resume, which needs a migration to save one cheap call.
- A Jev outage is handled like a Gemini outage today: retried, then an Unfiltered Acceptance
  (ADR-0005). Gemini is not a fallback for Jev.
- Jev decides from day one, with no shadow period.

The intake contract with the classifier service is unchanged.

## Consequences

Per Consultation, Gemini calls drop from 1 to 0 on a reject. An accept still makes two Gemini calls,
but both are text-only: the decisions in them moved to Jev.

A rejected session's reason is no longer the model's own words. It is a fixed sentence carrying
Jev's confidence, and the summary is the email's subject. This tensions two earlier records:

- **ADR-0003** keeps "the model's stated reason" so a firm can tune its Diagnostics Prompt from what
  it rejected. A rejection now says *how sure* the model was, not *why*. The transcript and Prompt
  snapshot are still kept, and they remain the tuning signal. If firms can't tune from them, the
  next step is a second Jev choice over a fixed list of rejection causes, not a return to Gemini.
- **ADR-0005** reserves `reason` for the model's words about the matter. The fixed sentence is
  still the model's judgment about the matter (a Verdict plus its confidence) and never
  infrastructure status, so it belongs there. Unfiltered causes stay in `unfiltered_cause`.

The docs don't state Jev's language support, and there was no offline evaluation against past
Spanish-language transcripts. Real traffic is the first test. The first days of Verdicts need
watching, and the console's "accept now" override (ADR-0001) is the backstop for wrong rejections.

## Alternatives considered

**Jev decides clear cases, Gemini the unclear ones (confidence-gated).** Safer, but it keeps Gemini
on the path for exactly the hard cases and needs a threshold nobody has data to set yet.

**Shadow Jev beside Gemini before switching.** Rejected in favor of switching straight away. The
Unfiltered Acceptance path and the console override already bound the damage of a wrong Verdict.

**Gemini writes the reason on every Verdict, reject included.** Better text in the console, but it
keeps a Gemini call on every email, which is the cost this decision exists to remove.
