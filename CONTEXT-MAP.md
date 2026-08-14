# Context Map

LegalGate is split into service contexts. Each has its own `CONTEXT.md` glossary.

| Context | Location | Responsibility |
| --- | --- | --- |
| Intake | `services/intake-orchestrator/CONTEXT.md` | Consultations, diagnostics, classification, routing, scheduling, notifications, billing |
| Mail ingress | `services/mail-ingress/CONTEXT.md` | Normalizing inbound provider webhooks into inbound emails for a tenant |
| Consultation classifier | `services/consultation-classifier/` | LLM calls behind an HTTP contract |
| Gateway | `services/gateway/` | Auth edge and reverse proxy |
| Frontend | `services/frontend/` | Landing site and firm console |

System-wide decisions live in `docs/adr/`. Context-scoped decisions live in `services/<name>/docs/adr/`.
