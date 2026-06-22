package com.legalgate.mail.service;

import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.model.NormalizedInboundEmail;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InboundEmailIngestionService {

    private final TenantLookupService tenantLookupService;
    private final InboundEmailClient inboundEmailClient;

    public InboundEmailIngestionService(
            TenantLookupService tenantLookupService,
            InboundEmailClient inboundEmailClient
    ) {
        this.tenantLookupService = tenantLookupService;
        this.inboundEmailClient = inboundEmailClient;
    }

    public InboundEmailReceived ingest(NormalizedInboundEmail email) {
        if (email.recipients() == null || email.recipients().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "missing_recipient");
        }

        String tenantId = email.recipients().stream()
                .map(tenantLookupService::tenantForIntakeEmail)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown_intake_recipient"));

        InboundEmailReceived event = new InboundEmailReceived(
                UUID.randomUUID().toString(),
                tenantId,
                email.recipients().get(0),
                email.recipients(),
                email.envelopeFrom(),
                email.headerFrom(),
                email.subject(),
                email.messageId(),
                email.plain(),
                email.html(),
                Instant.now()
        );

        try {
            inboundEmailClient.send(event);
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "intake_orchestrator_request_failed", ex);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "intake_orchestrator_unavailable", ex);
        }

        return event;
    }
}
