# LegalGate Intake Orchestrator

Spring Boot service for the first LegalGate consultation-intake slice from the container diagram.

## Implemented user-story coverage

- Firm admin configures tenant routing rules, each with keywords, consultation windows, and destination email.
- Firm admin sees the system-owned LegalGate intake email and configures routing rules.
- Consultant submits plain-language case information without needing legal terminology.
- Admin reviews consultations stored for a tenant.
- Service returns queued side-effect flags for email notification and calendar update orchestration.
- Service can consume inbound email events from RabbitMQ when `LEGALGATE_MAIL_ENABLED=true`.

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
- `GET /api/tenants/{tenantId}/settings`
- `PUT /api/tenants/{tenantId}/settings`
- `POST /api/tenants/{tenantId}/consultations`
- `GET /api/admin/tenants/{tenantId}/consultations`

The inbound email consumer currently logs and acknowledges validated events only. It does not create consultations yet.

Tenant settings use one tenant-wide system-owned `intakeEmail` and a `routingRules` array. The canonical address is `{tenantId}@${LEGALGATE_INTAKE_EMAIL_DOMAIN}` and defaults locally to `intake.legal-gate.local`. Production should use `intake.legal-gate.co`. Each rule groups the content keywords, destination email, and consultation windows for a lawyer or team. The service still accepts the earlier flat settings payload and converts it into one default routing rule, but `PUT /settings` rejects any payload containing `intakeEmail`.
