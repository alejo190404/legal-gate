package com.legalgate.mail.service;

import com.legalgate.mail.config.MailIngressProperties;
import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.model.InboundEmailIngestionResult;
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
    private final EmailBoilerplateStripper emailBoilerplateStripper;
    private final QuotedReplyStripper quotedReplyStripper;
    private final boolean stripQuotedReply;

    public InboundEmailIngestionService(
            TenantLookupService tenantLookupService,
            InboundEmailClient inboundEmailClient,
            EmailBoilerplateStripper emailBoilerplateStripper,
            QuotedReplyStripper quotedReplyStripper,
            MailIngressProperties properties
    ) {
        this.tenantLookupService = tenantLookupService;
        this.inboundEmailClient = inboundEmailClient;
        this.emailBoilerplateStripper = emailBoilerplateStripper;
        this.quotedReplyStripper = quotedReplyStripper;
        this.stripQuotedReply = properties.stripQuotedReply();
    }

    public InboundEmailIngestionResult ingest(NormalizedInboundEmail email) {
        if (email.recipients() == null || email.recipients().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "missing_recipient");
        }

        // Diagnostics replies come back to a plus-addressed variant of the intake address
        // (firma-demo+d<token>@...). The tag is stripped for tenant lookup only; the raw
        // recipient list is forwarded untouched so the intake service can read the token.
        String tenantId = email.recipients().stream()
                .map(InboundEmailIngestionService::withoutPlusTag)
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
                // Every provider funnels through here, so boilerplate dies once for all of them.
                // ponytail: the HTML body is left alone — line-anchored markers are unreliable
                // against tag soup, and intake only falls back to HTML when plain is empty.
                cleaned(email.plain()),
                email.html(),
                Instant.now(),
                email.autoResponder()
        );

        try {
            String downstreamStatus = inboundEmailClient.send(event);
            String status = "ignored_subscription_inactive".equals(downstreamStatus)
                    ? downstreamStatus : "received";
            return new InboundEmailIngestionResult(event, status);
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "intake_orchestrator_request_failed", ex);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "intake_orchestrator_unavailable", ex);
        }

    }

    /**
     * Boilerplate first, then quoted history. Both cut from a marker to the end of the body, so
     * either order handles either stacking — except that a gateway appends its notice below
     * everything, including below a quote, and an unquoted notice under a ">" run is what stops
     * that run reaching the end of the body. Taking the notice out first leaves the run intact.
     */
    private String cleaned(String plain) {
        String withoutBoilerplate = emailBoilerplateStripper.strip(plain);
        return stripQuotedReply ? quotedReplyStripper.strip(withoutBoilerplate) : withoutBoilerplate;
    }

    static String withoutPlusTag(String address) {
        if (address == null) {
            return null;
        }
        int at = address.indexOf('@');
        int plus = address.indexOf('+');
        if (at < 0 || plus < 0 || plus > at) {
            return address;
        }
        return address.substring(0, plus) + address.substring(at);
    }
}
