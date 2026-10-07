# Context Map

LegalGate is split into service contexts. Each has its own `CONTEXT.md` glossary.

| Context | Location | Responsibility |
| --- | --- | --- |
| Intake | `services/intake-orchestrator/CONTEXT.md` | Consultations, diagnostics, classification, routing, scheduling, notifications, billing |
| Clients | `services/intake-orchestrator/clients/CONTEXT.md` | Clients the firm has taken on, the Activities done for them, and answering their Status Inquiries |
| Mail ingress | `services/mail-ingress/CONTEXT.md` | Normalizing inbound provider webhooks into inbound emails for a tenant |
| Consultation classifier | `services/consultation-classifier/` | LLM calls behind an HTTP contract |
| Gateway | `services/gateway/CONTEXT.md` | Auth edge and reverse proxy |
| Frontend | `services/frontend/` | Landing site and firm console |

Clients shares the intake-orchestrator service with Intake. Engagement is where a Consultation hands its potential client over to Clients; inbound mail from a Contact Address belongs to Clients and never reaches Diagnostics.

System-wide decisions live in `docs/adr/`. Context-scoped decisions live in `services/<name>/docs/adr/`.
