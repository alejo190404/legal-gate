# LegalGate Mail Ingress

Spring Boot adapter for CloudMailin, MailerSend and Resend inbound email webhooks.

## Flow

1. CloudMailin sends Normalized JSON to `POST /webhooks/cloudmailin`; the endpoint requires Basic Auth configured with `LEGALGATE_CLOUDMAILIN_USERNAME` and `LEGALGATE_CLOUDMAILIN_PASSWORD`.
2. MailerSend sends inbound JSON to `POST /webhooks/mailersend`; the endpoint verifies `LEGALGATE_MAILERSEND_WEBHOOK_SECRET` when configured and accepts `webhook.test` validation payloads.
3. Resend posts a Svix-signed `email.received` event to `POST /webhooks/resend`; the endpoint verifies `LEGALGATE_RESEND_WEBHOOK_SECRET` when configured, then fetches the stored message with `RESEND_API_KEY`, because the event carries only its id. Other Resend event types are acknowledged and dropped.
4. Provider payloads are normalized into one internal inbound-email shape.
5. The service matches recipient addresses exactly against canonical `tenant_settings.intake_email` values.
6. The normalized `InboundEmailReceived` payload is posted synchronously to `intake-orchestrator`
   with `X-LegalGate-Service-Token` from the required `LEGALGATE_INTERNAL_SERVICE_TOKEN`.
7. `intake-orchestrator` validates and logs the event before the webhook response is returned.

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

Resend production setup:

- Webhook URL: `https://<mail-ingress-host>/webhooks/resend`
- Signing secret: from the webhook's page in the Resend dashboard, `whsec_`-prefixed
- MX target: only once Stage 4 of the migration flips it; until then the endpoint is deployed but unrouted

For production on Render, set `LEGALGATE_INTAKE_ORCHESTRATOR_URL` to the Intake Orchestrator service root and expose only this service's HTTPS webhook URLs.
