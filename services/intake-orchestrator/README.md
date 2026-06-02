# LegalGate Intake Orchestrator

Spring Boot service for the first LegalGate consultation-intake slice from the container diagram.

## Implemented user-story coverage

- Lawyer configures tenant urgency criteria, consultation windows, and destination email.
- Consultant submits plain-language case information without needing legal terminology.
- Admin reviews consultations stored for a tenant.
- Service returns queued side-effect flags for email notification and calendar update orchestration.

## Local commands

Run tests:

```bash
mvn -pl services/intake-orchestrator test
```

Run the service locally:

```bash
PORT=8081 ./scripts/run-intake-local.sh
```

Smoke-test the service:

```bash
./scripts/test-intake-local.sh
```

## Endpoints

- `GET /api/status`
- `PUT /api/tenants/{tenantId}/settings`
- `POST /api/tenants/{tenantId}/consultations`
- `GET /api/admin/tenants/{tenantId}/consultations`

This implementation is intentionally in-memory and ready for later replacement with Core DB, RabbitMQ, LLM classification, email, and calendar adapters.
