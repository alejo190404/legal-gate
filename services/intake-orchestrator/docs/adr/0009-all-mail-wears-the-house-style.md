# All mail wears the House Style

- Status: Accepted
- Date: 2026-10-22
- Partially supersedes: ADR-0004 ("Conversational mail is plaintext; transactional mail is HTML", the LegalGate fallback in the client header, and the client card's footer)

Every email LegalGate sends is now an HTML card in one House Style — the frame the scheduled-consultation cards already used — with a plaintext part alongside. ADR-0004 kept the Diagnostics question and the Non-Engagement Notice plaintext because a card "reads as automation"; we are trading that for a consistent, recognisable look across all mail, and as the base a later feature will let each firm replace with its own. The Firm Voice is unchanged: client-facing cards frame the firm's name and nothing else, and mail to lawyers and firm staff adds LegalGate's mark.

## Consequences

**ADR-0007 never required plaintext.** It requires the Firm Voice and a fixed template filled from the record; code comments citing it for plaintext were wrong and are corrected. The client receipt and status answer become cards like the rest.

**The plaintext part carries every sentence the card does.** Headlines, labels and footer lines included — only purely visual pieces have no text equivalent. Client Correspondence keeps the plaintext as the exact words sent on the firm's behalf, so it must not be a shorter letter than the one the client read.

**Client mail never says LegalGate, not even as a fallback.** A tenant with no display name gets an empty header band rather than "LegalGate Agenda" — the same rule ADR-0004 already applied to signatures. A test renders every client-facing template and fails on the word. The sender address, `.ics` identifiers and the fallback intake domain still carry legal-gate.co; moving them is separate work, because sending scheduling mail from the firm's intake address would route token-less replies into new Consultations.

**The client-forward card carries no LegalGate mark either.** It goes to a lawyer, but its `Reply-To` is the Client, so it is the one staff email that predictably reaches a client quoted in the lawyer's reply.

**The scheduling footer says replies are not read**, because they are not: nothing receives mail at the scheduling sender. ADR-0004's "line inviting a reply" was never true.

**A rescheduled consultation reuses the scheduled cards with a different headline and eyebrow** rather than templates of its own — a simplification for the MVP, revisit if the two notices need to say materially different things.

**Free text stays text.** Every merge value is HTML-escaped and keeps its line breaks; the Non-Engagement body is still inserted verbatim and cannot carry markup.
