package com.legalgate.mail.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.model.InboundEmailIngestionResult;
import com.legalgate.mail.model.MailerSendWebhook;
import com.legalgate.mail.service.MailerSendIngestionService;
import com.legalgate.mail.service.MailerSendSignatureVerifier;
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
@RequestMapping("/webhooks/mailersend")
class MailerSendWebhookController {

    private final ObjectMapper objectMapper;
    private final MailerSendSignatureVerifier signatureVerifier;
    private final MailerSendIngestionService mailerSendIngestionService;

    MailerSendWebhookController(
            ObjectMapper objectMapper,
            MailerSendSignatureVerifier signatureVerifier,
            MailerSendIngestionService mailerSendIngestionService
    ) {
        this.objectMapper = objectMapper;
        this.signatureVerifier = signatureVerifier;
        this.mailerSendIngestionService = mailerSendIngestionService;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(name = "X-MailerSend-Signature", required = false) String signature,
            @RequestHeader(name = "X-MailerSend-Webhook-Secret", required = false) String secret,
            @RequestBody String rawBody
    ) throws Exception {
        if (!signatureVerifier.isValid(rawBody, signature, secret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_mailersend_signature");
        }

        MailerSendWebhook webhook = objectMapper.readValue(rawBody, MailerSendWebhook.class);
        if ("webhook.test".equals(webhook.eventType())) {
            return ResponseEntity.ok(Map.of("status", "ok"));
        }

        InboundEmailIngestionResult result = mailerSendIngestionService.ingest(webhook);
        InboundEmailReceived event = result.event();
        return ResponseEntity.ok(Map.of(
                "status", result.status(),
                "eventId", event.eventId(),
                "tenantId", event.tenantId()
        ));
    }
}
