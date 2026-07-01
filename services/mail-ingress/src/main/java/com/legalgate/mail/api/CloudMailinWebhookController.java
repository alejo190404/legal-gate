package com.legalgate.mail.api;

import com.legalgate.mail.model.CloudMailinMessage;
import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.model.InboundEmailIngestionResult;
import com.legalgate.mail.service.BasicAuthVerifier;
import com.legalgate.mail.service.CloudMailinIngestionService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/cloudmailin")
class CloudMailinWebhookController {

    private final BasicAuthVerifier basicAuthVerifier;
    private final CloudMailinIngestionService cloudMailinIngestionService;

    CloudMailinWebhookController(
            BasicAuthVerifier basicAuthVerifier,
            CloudMailinIngestionService cloudMailinIngestionService
    ) {
        this.basicAuthVerifier = basicAuthVerifier;
        this.cloudMailinIngestionService = cloudMailinIngestionService;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CloudMailinMessage message
    ) {
        if (!basicAuthVerifier.isValid(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"LegalGate CloudMailin\"")
                    .body(Map.of("error", "unauthorized"));
        }

        InboundEmailIngestionResult result = cloudMailinIngestionService.ingest(message);
        InboundEmailReceived event = result.event();
        return ResponseEntity.ok(Map.of(
                "status", result.status(),
                "eventId", event.eventId(),
                "tenantId", event.tenantId()
        ));
    }
}
