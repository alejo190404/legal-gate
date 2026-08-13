# ADR-0001: Strip Email Boilerplate at ingress

- Status: Accepted
- Date: 2026-08-13

## Context

Potential clients writing from a corporate or university mail system get a confidentiality notice appended to every outbound message by their own gateway — a legal disclaimer, a privileged-information warning, an antivirus stamp. It is Email Boilerplate: text nobody in the conversation wrote, carrying no intent from the potential client.

`CloudMailinIngestionService` already prefers CloudMailin's `reply_plain` over `plain`, which removes quoted history. Boilerplate is not quoted history — the gateway appends it below the reply, so it survives and reaches both LLM paths: the Diagnostics transcript and Classification. A three-line answer arrives at the model wrapped in two hundred words of legalese, and the model is being asked to reason about a legal matter using text that is itself legal-sounding noise.

The obvious place to remove it is where the reply body is already being chosen, at the mail boundary, before the intake context sees anything.

## Decision

An `EmailBoilerplateStripper` in mail-ingress removes boilerplate from the plain-text body during `InboundEmailIngestionService.ingest`, so every provider and both LLM paths are covered by one call.

Detection is a hardcoded list of fixed gateway phrases. A marker matches only at the start of a trimmed line, case-folded and accent-folded — Spanish gateways vary on tildes, and one in the wild deliberately omits them. From the earliest match, everything below is cut: gateway trailers are always last, and `reply_plain` has already lifted the client's text above them.

Two guards make a mis-detection a no-op instead of data loss. If the strip leaves a blank body, the original is kept. If the earliest marker sits at the very top with nothing above it, the original is kept — that shape is a forwarded disclaimer-laden thread with the answer below, not a trailer.

The signature delimiter `-- ` and the bare word `disclaimer` are deliberately not markers. A signature carries the client's name, phone and ID, sometimes exactly what the firm asked for; and a client asking about a contract's disclaimer is a real consultation.

Three narrowings come with it:

**Plain text only. The HTML body is untouched.** `DiagnosticsService.replyBodyFor` falls back to HTML only when plain is blank, which is rare, and line-anchored text markers are unreliable against tag soup.

**The raw body is not retained.** mail-ingress persists nothing and is a pass-through, so stripping there means the original exists nowhere in LegalGate. What was removed is logged at INFO in mail-ingress; nothing is stored.

**The marker list is not per-tenant.** One hardcoded list, extended by a pull request.

## Consequences

Both the Diagnostics transcript and Classification receive the potential client's own words, and the firm reading a transcript in the console sees the same.

A false positive is invisible: the guards catch the destructive shapes, but a marker firing mid-message on text that had content below it and above it would silently drop that content, and with no raw body retained there is nothing in LegalGate to compare against. CloudMailin retains originals for its own window if forensics are ever needed.

An HTML-only reply from a corporate sender still reaches the model with its boilerplate attached. Known and accepted; revisit when one is observed.

## Alternatives considered

**Mark rather than strip** — keep the boilerplate and instruct the model to ignore it. Pays tokens for text with zero intent and bets on instruction-following, a failure mode deletion does not have.

**An LLM pre-pass to clean the body.** Adds a network call, a latency budget and a second model failure mode to every inbound email, to remove text that fixed string matching removes.

**Forward the raw body alongside the stripped one.** Real insurance against a bug in the guards, but it costs a contract change across two services and a console decision about which body to show. Worth revisiting the first time a false positive is actually observed.
