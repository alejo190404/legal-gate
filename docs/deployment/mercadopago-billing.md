# Mercado Pago tenant billing

LegalGate v1 uses Mercado Pago Colombia, COP, and subscriptions without an associated
provider plan. LegalGate's database is the plan and coupon catalog; each checkout creates
a pending `/preapproval` and redirects the payer to Mercado Pago.

## 1. Create and configure the Mercado Pago application

1. In Mercado Pago **Tus integraciones**, create an application owned by the production
   seller account. Use separate test and production credentials.
2. Copy the test or production **Access Token** into `MERCADOPAGO_ACCESS_TOKEN`. Never put
   this token in Vercel or the browser bundle.
3. Under **Webhooks > Configurar notificaciones**, set:
   - Test URL: `https://<staging-gateway>/api/webhooks/mercadopago`
   - Production URL: `https://<production-gateway>/api/webhooks/mercadopago`
   - Topics: payments, subscription preapprovals, and authorized subscription payments.
4. Reveal and copy the webhook secret generated for that URL into
   `MERCADOPAGO_WEBHOOK_SECRET`. Test and production URLs can have different secrets.
5. The webhook must point to the gateway, not directly to the intake orchestrator. The
   gateway route is anonymous but forwards only the raw payload, query string,
   `Content-Type`, `x-signature`, and `x-request-id`.

Official references:

- Subscription creation: <https://www.mercadopago.com.co/developers/es/reference/online-payments/subscriptions/create-preapproval/post>
- Pending hosted checkout: <https://www.mercadopago.com.co/developers/en/docs/subscriptions/integration-configuration/subscription-no-associated-plan/pending-payments>
- Webhook signature and topics: <https://www.mercadopago.com.co/developers/es/docs/subscriptions/additional-content/your-integrations/notifications/webhooks>
- Cancellation and amount updates: <https://www.mercadopago.com.co/developers/es/docs/subscriptions/subscription-management>
- Invoice reconciliation: <https://www.mercadopago.com.co/developers/en/reference/online-payments/subscriptions/authorized-payment-search/get>

## 2. Production environment variables

Configure these on the intake-orchestrator service:

```dotenv
LEGALGATE_BILLING_ENABLED=true
LEGALGATE_BILLING_ENFORCEMENT_ENABLED=false
MERCADOPAGO_ACCESS_TOKEN=APP_USR-...
MERCADOPAGO_WEBHOOK_SECRET=...
MERCADOPAGO_API_URL=https://api.mercadopago.com
MERCADOPAGO_PROVIDER_TIMEOUT=8s
LEGALGATE_BILLING_RETURN_URL=https://www.legal-gate.co/?billing=return
LEGALGATE_BILLING_RECONCILIATION_INTERVAL=5m
LEGALGATE_BILLING_PENDING_CHECKOUT_TTL=24h
LEGALGATE_BILLING_GRACE_PERIOD=7d
LEGALGATE_BILLING_WEBHOOK_PROCESSING_DELAY_MS=5000
LEGALGATE_BILLING_RECONCILIATION_DELAY_MS=300000
```

Configure this on the gateway:

```dotenv
GATEWAY_BILLING_REQUEST_TIMEOUT=10s
```

Existing required variables remain unchanged: `LEGALGATE_INTERNAL_SERVICE_TOKEN` must be
identical on the gateway and intake orchestrator, and the intake service must use JDBC
persistence with Flyway enabled.

Startup fails when billing is enabled without the access token, webhook secret, or return
URL. Billing-disabled environments start without Mercado Pago secrets. Enforcement cannot
be enabled while billing itself is disabled.

## 3. Create plans and coupons

Plans are immutable. A price change retires the current row and inserts a higher version.
Do not edit the price or interval of an existing plan.

```sql
-- Create initial plans.
insert into billing_plans
  (code, version, display_name, description, billing_interval, price_cop, display_order)
values
  ('firma-mensual', 1, 'LegalGate Mensual', 'Acceso para toda la firma', 'MONTHLY', 199000, 10),
  ('firma-anual',   1, 'LegalGate Anual',   'Acceso para toda la firma', 'YEARLY', 1990000, 20);

-- Retire a plan before adding a new price version.
update billing_plans
set active = false, retired_at = now()
where code = 'firma-mensual' and active;

insert into billing_plans
  (code, version, display_name, description, billing_interval, price_cop, display_order)
values
  ('firma-mensual', 2, 'LegalGate Mensual', 'Acceso para toda la firma', 'MONTHLY', 229000, 10);

-- 20% off the first approved cycle.
insert into coupons
  (code, discount_type, discount_value, duration, valid_from, valid_until, max_redemptions)
values
  ('BIENVENIDA20', 'PERCENTAGE', 20, 'ONCE', now(), now() + interval '30 days', 100);

-- COP 50,000 off the first three approved cycles.
insert into coupons
  (code, discount_type, discount_value, duration, duration_cycles)
values
  ('LANZAMIENTO3', 'FIXED', 50000, 'REPEATING', 3);

-- Validate the public catalog and current coupon state.
select code, version, billing_interval, price_cop, active
from billing_plans order by code, version;
select code, discount_type, discount_value, duration, duration_cycles,
       redemption_count, max_redemptions, active
from coupons order by created_at;
```

`100%` discounts and discounts that reduce the charge to zero are intentionally rejected,
because subscription activation requires an approved payment.

## 4. Safe rollout and sandbox verification

1. Deploy migration `V12` and the application with billing and enforcement both disabled.
2. Add test plans and set test Mercado Pago credentials, webhook URL/secret, and
   `LEGALGATE_BILLING_ENABLED=true`; leave enforcement false.
3. Use a Mercado Pago test buyer to verify checkout, the signed webhook response, first
   approved payment, renewal/failure handling, cancellation, and the paid-through date.
   Confirm duplicate webhook delivery creates no duplicate payment.
4. Verify logs show successful reconciliation and that no webhook event remains in
   `FAILED` or `DEAD`:

   ```sql
   select status, count(*) from billing_webhook_events group by status;
   select id, event_type, resource_id, processing_attempts, last_error
   from billing_webhook_events where status in ('FAILED', 'DEAD');
   ```

5. Replace test credentials with production credentials and confirm the production
   webhook secret.
6. Only after the production smoke test, set
   `LEGALGATE_BILLING_ENFORCEMENT_ENABLED=true` and redeploy.

The return URL never grants access. The SPA polls the server for up to one minute, and only
an approved canonical Mercado Pago payment activates the subscription.
