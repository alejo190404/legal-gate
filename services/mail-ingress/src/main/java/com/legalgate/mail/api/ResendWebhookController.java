package com.legalgate.mail.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.mail.model.InboundEmailIngestionResult;
import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.model.ResendInboundWebhook;
import com.legalgate.mail.service.ResendIngestionService;
import com.legalgate.mail.service.ResendSignatureVerifier;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/webhooks/resend")
class ResendWebhookController {

    private static final String RECEIVED_EVENT = "email.received";

    private final ObjectMapper objectMapper;
    private final ResendSignatureVerifier signatureVerifier;
    private final ResendIngestionService resendIngestionService;

    ResendWebhookController(
            ObjectMapper objectMapper,
            ResendSignatureVerifier signatureVerifier,
            ResendIngestionService resendIngestionService
    ) {
        this.objectMapper = objectMapper;
        this.signatureVerifier = signatureVerifier;
        this.resendIngestionService = resendIngestionService;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(name = "svix-id", required = false) String svixId,
            @RequestHeader(name = "svix-timestamp", required = false) String svixTimestamp,
            @RequestHeader(name = "svix-signature", required = false) String svixSignature,
            @RequestBody String rawBody
    ) throws Exception {
        if (!signatureVerifier.isValid(rawBody, svixId, svixTimestamp, svixSignature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_resend_signature");
        }

        ResendInboundWebhook webhook = objectMapper.readValue(rawBody, ResendInboundWebhook.class);
        // email.sent, email.delivered and email.bounced can land on this same endpoint. They are
        // not errors and must not be retried, so they are acknowledged and dropped.
        if (!RECEIVED_EVENT.equals(webhook.type())) {
            return ResponseEntity.ok(Map.of("status", "ignored_event_type"));
        }

        String messageStoreId = webhook.data() == null ? null : webhook.data().messageStoreId();
        if (messageStoreId == null || messageStoreId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "missing_email_id");
        }

        InboundEmailIngestionResult result = resendIngestionService.ingest(messageStoreId);
        InboundEmailReceived event = result.event();
        return ResponseEntity.ok(Map.of(
                "status", result.status(),
                "eventId", event.eventId(),
                "tenantId", event.tenantId()
        ));
    }
}
