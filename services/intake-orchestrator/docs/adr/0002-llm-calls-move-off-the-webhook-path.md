# ADR-0002: LLM calls move off the webhook request path

- Status: Accepted
- Date: 2026-08-10

## Context

Classification ran inside the inbound-email webhook request. `HttpConsultationClassifierClient` made one blocking call and caught every `RestClientException` as `ClassifierUnavailableException`; `IntakeService` then stamped the Consultation `LLM_FAILED` and routed it to a human.

One attempt, no retry. A single timeout permanently marked a Consultation as unclassifiable even though a retry seconds later would have succeeded.

Retrying in place does not fix it. The call sits inside an HTTP request the mail provider is holding open, so the retry budget is bounded by the provider's webhook timeout — seconds. A real Gemini outage lasts minutes to hours, so in-request retries would burn a few seconds and still land on `LLM_FAILED`, while risking the provider timing out and redelivering the message.

Diagnostics removes the reason for synchronicity: with ADR-0001, nothing needs scheduling at webhook time.

## Decision

The webhook persists the inbound email, returns 200, and does no LLM work.

A scheduled worker performs Diagnostics and Classification, retrying with exponential backoff — roughly 30s, 1m, 2m, 5m, 15m, 30m, six attempts across about an hour. Only after the last attempt fails does the Consultation fall back to human review.

This mirrors `NotificationDeliveryService`, which already polls, claims a batch, and marks each item sent or failed. Diagnostics work is a new job type in that pattern, not a new queue technology.

Failure after exhaustion is open, not closed: an exhausted Diagnostics attempt proceeds as if accepted rather than parking the matter. A model outage must not silently swallow real clients.

## Consequences

`LLM_FAILED` now means "we tried for an hour", which makes it a signal worth alerting on rather than routine noise.

Classification inherits the retry behaviour for free, since by acceptance it is already off the webhook path. The pre-existing bug where one timeout permanently stamped a Consultation is fixed as a side effect.

A potential client waits up to one poll interval — around 30 seconds — before receiving the first Diagnostics question. Invisible in an email conversation.

The webhook no longer returns a `consultationId` for work it has not yet done, so any caller depending on that field in the response must change.

## Alternatives considered

**Inline retries only.** Simplest diff, but bounded by the webhook timeout and therefore useless against the outage it was meant to survive.

**Inline fast attempt plus a job on failure.** Saves ~30 seconds of email latency at the cost of two code paths and two copies of the fallback decision. Not worth it.
