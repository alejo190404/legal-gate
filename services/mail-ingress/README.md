# LegalGate Mail Ingress

Spring Boot adapter for CloudMailin and MailerSend inbound email webhooks.

## Flow

1. CloudMailin sends Normalized JSON to `POST /webhooks/cloudmailin`; the endpoint requires Basic Auth configured with `LEGALGATE_CLOUDMAILIN_USERNAME` and `LEGALGATE_CLOUDMAILIN_PASSWORD`.
2. MailerSend sends inbound JSON to `POST /webhooks/mailersend`; the endpoint verifies `LEGALGATE_MAILERSEND_WEBHOOK_SECRET` when configured and accepts `webhook.test` validation payloads.
3. Provider payloads are normalized into one internal inbound-email shape.
4. The service matches recipient addresses exactly against canonical `tenant_settings.intake_email` values.
5. The normalized `InboundEmailReceived` payload is posted synchronously to `intake-orchestrator` over HTTP.
6. `intake-orchestrator` validates and logs the event before the webhook response is returned.

## Local commands

Run tests:

```bash
mvn -pl services/mail-ingress test
```

Run through Docker Compose:

```bash
docker compose up --build postgres intake-orchestrator mail-ingress
```

CloudMailin should be configured to use the Normalized JSON format.

MailerSend production setup:

- Inbound route: `*@intake.legal-gate.co`
- Webhook URL: `https://<mail-ingress-host>/webhooks/mailersend`
- MX target: `inbound.mailersend.net`

For production on Render, set `LEGALGATE_INTAKE_ORCHESTRATOR_URL` to the Intake Orchestrator service root and expose only this service's HTTPS webhook URLs.
