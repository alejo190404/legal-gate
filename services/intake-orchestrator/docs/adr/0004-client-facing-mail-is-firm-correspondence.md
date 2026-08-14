# Client-facing mail is firm correspondence, not product notification

A potential client's first contact with a firm arrived as a bare LLM question from "LegalGate Agenda", on a fresh thread, with no salutation or signature. Everything sent to a potential client now carries the Firm Voice instead: the firm's name as sender, a salutation, a signature from the firm's consultation team, and no mention of LegalGate. Mail to lawyers and firm staff keeps LegalGate branding, because they are the customer and know what LegalGate is.

## Consequences

Two things here look like inconsistencies and are not, so they should not be "fixed":

**Conversational mail is plaintext; transactional mail is HTML.** The scheduled and rescheduled notices stay as rendered HTML cards because they are receipts and a receipt should look like one. Diagnostics questions and the Non-Engagement Notice are plaintext, because a branded card asking a stranger for their ID number reads as automation no matter how well designed it is. Consistency across the two was rejected as a goal — sounding like a person was the goal.

**The Consultation Thread is anchored flat, not chained.** Every message LegalGate sends a potential client about a Consultation points at that client's original email rather than at the previous message in the exchange. Mail clients group on a shared root, so a flat anchor and a true chain land in the same conversation; the chain would have cost persisted message identifiers on two more tables to buy correct nesting in a handful of readers.

The thread is the conversation with the potential client, so only client-facing mail carries `In-Reply-To` and `References`. Notices to lawyers and firm staff are about the Consultation but not part of that conversation, and anchoring them to a stranger's Message-ID would fold firm mail into the client's thread. Every outbound message still carries its own `Message-ID`, on the domain it was sent from — that is the message's identity, not a claim about a thread. The anchor lives on the Consultation and is read at send time, so the outbox carries no message identifiers; when it is missing the mail sends unthreaded, because threading is an enhancement and never a reason not to deliver.

Signatures name the firm's consultation team and never a lawyer. During Diagnostics no lawyer has been assigned and nobody has read the file, so signing with a person's name would be a claim the firm cannot stand behind.

Sounding human is bounded by what LegalGate is allowed to assert. The Acknowledgment repeats the potential client's own words back and stops there. The template — not the model — supplies every fixed sentence, so a bad generation cannot put an inappropriate line into a law firm's first contact with a stranger.
