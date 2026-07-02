package com.legalgate.intake.billing;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.legalgate.intake.billing.BillingModels.Quote;

@Component
public class SubscriptionProviderClient {
    private final RestClient restClient;
    private final BillingProperties properties;

    public SubscriptionProviderClient(RestClient.Builder builder, BillingProperties properties) {
        this.properties = properties;
        Duration timeout = properties.providerTimeout();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = builder
                .baseUrl(properties.mercadoPagoApiUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + nullToEmpty(properties.mercadoPagoAccessToken()))
                .build();
    }

    public JsonNode createPending(Quote quote, String payerEmail, String externalReference, String idempotencyKey) {
        ensureEnabled();
        int frequency = "YEARLY".equals(quote.plan().interval()) ? 12 : 1;
        Map<String, Object> body = Map.of(
                "reason", "LegalGate - " + quote.plan().displayName(),
                "external_reference", externalReference,
                "payer_email", payerEmail,
                "auto_recurring", Map.of(
                        "frequency", frequency,
                        "frequency_type", "months",
                        "transaction_amount", quote.recurringAmountCop(),
                        "currency_id", "COP"),
                "back_url", properties.returnUrl(),
                "status", "pending");
        return restClient.post()
                .uri("/preapproval")
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(JsonNode.class);
    }

    public Optional<JsonNode> findByExternalReference(String externalReference) {
        ensureEnabled();
        String uri = UriComponentsBuilder.fromPath("/preapproval/search")
                .queryParam("q", externalReference)
                .queryParam("limit", 100)
                .build().encode().toUriString();
        JsonNode response = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        if (response == null || !response.path("results").isArray() || response.path("results").isEmpty()) {
            return Optional.empty();
        }
        return java.util.stream.StreamSupport.stream(response.path("results").spliterator(), false)
                .filter(item -> externalReference.equals(item.path("external_reference").asText()))
                .findFirst();
    }

    public JsonNode subscription(String providerId) {
        ensureEnabled();
        return restClient.get().uri("/preapproval/{id}", providerId).retrieve().body(JsonNode.class);
    }

    public JsonNode payment(String paymentId) {
        ensureEnabled();
        return restClient.get().uri("/v1/payments/{id}", paymentId).retrieve().body(JsonNode.class);
    }

    public JsonNode authorizedPayment(String authorizedPaymentId) {
        ensureEnabled();
        return restClient.get().uri("/authorized_payments/{id}", authorizedPaymentId)
                .retrieve().body(JsonNode.class);
    }

    public JsonNode authorizedPayments(String providerSubscriptionId) {
        ensureEnabled();
        String uri = UriComponentsBuilder.fromPath("/authorized_payments/search")
                .queryParam("preapproval_id", providerSubscriptionId)
                .queryParam("limit", 100)
                .build().encode().toUriString();
        return restClient.get().uri(uri).retrieve().body(JsonNode.class);
    }

    public void cancel(String providerId) {
        ensureEnabled();
        restClient.put().uri("/preapproval/{id}", providerId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "cancelled"))
                .retrieve().toBodilessEntity();
    }

    public void updateAmount(String providerId, java.math.BigDecimal amount) {
        ensureEnabled();
        restClient.put().uri("/preapproval/{id}", providerId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("auto_recurring", Map.of(
                        "transaction_amount", amount,
                        "currency_id", "COP")))
                .retrieve().toBodilessEntity();
    }

    private void ensureEnabled() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Billing is disabled.");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
