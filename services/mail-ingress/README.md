# LegalGate Mail Ingress

Spring Boot adapter for CloudMailin inbound email webhooks.

## Flow

1. CloudMailin sends Normalized JSON to `POST /webhooks/cloudmailin`.
2. The endpoint requires Basic Auth configured with `LEGALGATE_CLOUDMAILIN_USERNAME` and `LEGALGATE_CLOUDMAILIN_PASSWORD`.
3. The service matches `envelope.to` / `envelope.recipients` against `tenant_settings.intake_email`.
4. A durable `InboundEmailReceived` event is published to RabbitMQ.
5. `intake-orchestrator` consumes the event asynchronously.

## Local commands

Run tests:

```bash
mvn -pl services/mail-ingress test
```

Run through Docker Compose:

```bash
docker compose up --build postgres rabbitmq intake-orchestrator mail-ingress
```

CloudMailin should be configured to use the Normalized JSON format. For production on Render, keep RabbitMQ private and expose only this service's HTTPS webhook URL.
