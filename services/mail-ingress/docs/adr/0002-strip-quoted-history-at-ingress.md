# ADR-0002: Strip Quoted History at ingress

- Status: Accepted
- Date: 2026-09-10

## Context

A Diagnostics exchange is a conversation: the firm asks, the potential client replies, the firm asks again. Every mail client stacks a copy of the earlier exchange under each reply — Gmail and Apple Mail behind a one-line attribution, Outlook behind a `De:`/`Enviado el:` header block, older clients behind `-----Mensaje original-----` or a run of `>` lines. It is Quoted History: text the potential client did not write on this round.

Nothing in LegalGate removes it today. CloudMailin does, on its own servers, and offers the result as `reply_plain`; `CloudMailinIngestionService` prefers that field over `plain` and the problem has never reached us. Resend has no equivalent — its inbound payload carries the full body and nothing else.

So the CloudMailin → Resend migration silently deletes a feature. The moment inbound moves, every Diagnostics round arrives carrying all previous rounds, and both LLM paths re-read the whole conversation each time: Classification weighs text that was already weighed, and the Diagnostics transcript starts extracting answers out of the firm's own quoted questions. The firm reading the console sees the thread repeated once per round.

This is the same problem Email Boilerplate posed, at the same boundary, with the same shape — text nobody wrote on this round, appended below what they did write.

## Decision

A `QuotedReplyStripper` in mail-ingress removes Quoted History from the plain-text body during `InboundEmailIngestionService.ingest`, after `EmailBoilerplateStripper` runs. Both cut from a marker to the end of the body, so either order copes with either stacking — with one exception that decides it. A gateway appends its notice below everything the sender's client produced, quote included, and an unquoted notice sitting under a `>` run is exactly what stops that run reaching the end of the body. Removing the notice first leaves the run intact.

Detection follows ADR-0001 exactly, for the reasons ADR-0001 gives. A fixed marker list, not a heuristic. Markers match at the start of a trimmed line, case-folded and accent-folded. Spanish shapes first, because the clients are Colombian. Both guards apply unchanged: a strip that leaves a blank body keeps the original, and a marker at the very top with nothing above it keeps the original — that shape is a forwarded thread whose content is below the attribution, not a trailer hanging off a reply.

Three markers need more than a prefix test:

**The attribution line** wraps a date that is not fixed text, so it is matched by what brackets it: a line opening with `El ` and closing with `escribió:`, or opening with `On ` and closing with `wrote:`.

**The angle-quote run** counts only when every non-blank line below it is quoted too. Quoted history is a trailer, so it reaches the end of the body; a client who pastes a contract clause and keeps answering underneath has written text below the quote, and cutting from it would delete the rest of their matter.

**The Outlook header block** opens with `De:` or `From:`, which is also ordinary writing — "De: mi arrendador recibí una carta" is a client describing their matter. The block counts only when one of its sibling headers (`Enviado el:`, `Para:`, `Sent:`, `To:`) follows within the next three lines.

`legalgate.mail-ingress.strip-quoted-reply` (`LEGALGATE_STRIP_QUOTED_REPLY`, default true) turns the strip off without a redeploy, so a false positive observed in production is an env flip. It is scaffolding for the migration window and is meant to be deleted once the stripper has run on real mail for a while.

The narrowings of ADR-0001 carry over unchanged: plain text only, the raw body is not retained, the marker list is not per-tenant, and only the number of characters removed is logged — never the text, which here is the earlier exchange between the firm and the potential client.

`CloudMailinIngestionService` keeps preferring `reply_plain`. It is strictly better than our marker list while CloudMailin is live, and it is the free oracle for this stripper: run both over real mail and compare.

## Consequences

Inbound is no longer dependent on a provider-specific field. When the MX record moves to Resend, quoted history keeps being removed, by our code rather than CloudMailin's.

A false positive costs more here than it does for boilerplate. Boilerplate sits at the bottom of a message and a wrong cut there loses a signature; an attribution line can sit anywhere the client chose to reply inline, and a wrong cut loses the rest of the matter. The guards catch the destructive shapes, the marker list stays short, and no raw body is retained to compare against — the kill switch exists because of that asymmetry.

Interleaved replies are handled for `>` quoting and not for the other markers. A client answering under a quoted clause keeps their text, because the run-to-the-end rule declines to treat that clause as a trailer. A client answering underneath an attribution line or an Outlook header block still loses everything below it. No mail client produces that second shape by default — it takes someone deliberately typing under the quote — and the first observed case is what reopens this.

A wrapped attribution line is not detected. Gmail wraps it when the display name and address are long enough, and the wrapped form matches no marker. The header-block and `>` markers usually catch the same mail. Left alone until one is observed slipping through.

## Alternatives considered

**Keep depending on `reply_plain` and find its Resend equivalent.** There isn't one. Resend's inbound payload is the parsed message, not a reply-extracted one.

**A library — `talon`, `email-reply-parser`, `mailgun/talon`.** They are Python, or Ruby, or trained heuristics with a false-positive rate nobody publishes per-language. The Spanish shapes are the ones that matter here and are the ones least likely to be covered. A twenty-line marker list we can read at 3am beats a dependency whose failure mode is a lost matter.

**An LLM pre-pass.** Rejected for the same reasons ADR-0001 rejected it: a network call, a latency budget and a second model failure mode on every inbound email, to remove text that fixed string matching removes.

**Strip in the intake context instead.** Intake receives from every provider through mail-ingress, so the boundary is the same, but mail-ingress is where the body is already being chosen and where ADR-0001 put the sibling decision. Splitting them across two services would mean two marker lists.
