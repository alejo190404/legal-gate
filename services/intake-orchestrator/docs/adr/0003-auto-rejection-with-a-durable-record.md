# ADR-0003: Auto-rejection with a durable record

- Status: Accepted
- Date: 2026-08-10

## Context

Diagnostics reaches a Verdict from the firm's own Diagnostics Prompt. When the Verdict is that the firm does not take matters of this kind, someone has to act on it.

Letting a model decline potential clients on a firm's behalf is the sharpest edge in this feature. A wrong acceptance costs one meeting slot and is visible. A wrong rejection costs a client, is invisible, and the firm never learns it happened.

The counterweight is that a Prompt only improves if the firm can see what it rejected and why.

## Decision

Rejection is automatic. No lawyer approval gate.

Every Diagnostics session is recorded durably: the transcript, the Verdict, the model's stated reason, and the Diagnostics Prompt text **as it read at the moment of the decision**. The Prompt snapshot is stored on the session row rather than referenced through a version table, because "what exactly did the model read" is the entire question a firm is answering when it tunes the Prompt, and firms edit Prompts freely.

Rejected potential clients receive a Non-Engagement Notice: firm-configurable text stating no attorney-client relationship was formed and that other counsel should be sought promptly, since deadlines may apply. It never contains legal advice.

Transcripts for rejected and abandoned Consultations are purged after 180 days. The Verdict, the reason, and the Prompt snapshot are kept indefinitely.

## Consequences

The firm carries the risk of an automated decline, mitigated by the console "accept now" override from ADR-0001 and by the Prompt being their own words, which they can rewrite the moment the record shows it misfiring.

Retention is deliberately split. A transcript is a stranger's unfiltered account of a legal problem, held by a firm that never took them on — the smallest thing worth keeping long-term is the Verdict and the reason. Purging transcripts shrinks the blast radius of a breach without costing the tuning signal.

Storing the Prompt snapshot per session duplicates text across rows. Accepted: it removes a join from the query firms will run most, and it is the only way an old Verdict stays interpretable after the Prompt changes.

## Alternatives considered

**Auto-accept, park rejections for lawyer review.** Safer against wrong rejections, but reintroduces the manual triage step the feature exists to remove.

**Reject silently, no client email.** Auto-declining and then ghosting is the worst combination available for a firm's exposure and reputation.
