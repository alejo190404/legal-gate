package com.legalgate.intake.mail;

import com.legalgate.intake.clients.ClientMailService;
import com.legalgate.intake.clients.ClientsModels.Correspondence;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.service.DiagnosticsService;
import org.springframework.stereotype.Service;

/**
 * Decides whose an inbound email is, in order (ADR-0007): a Reply Token for a pending Diagnostics
 * session, then a Contact Address of the tenant, then a new Consultation. Persists and returns.
 */
@Service
public class InboundMailRouter {

    private final DiagnosticsService diagnostics;
    private final ClientMailService clientMail;

    public InboundMailRouter(DiagnosticsService diagnostics, ClientMailService clientMail) {
        this.diagnostics = diagnostics;
        this.clientMail = clientMail;
    }

    /** Exactly one side is set; {@code correspondence} is null too for a Client email already recorded. */
    public record Received(ConsultationResponse consultation, Correspondence correspondence) {
    }

    public Received receive(InboundEmailReceived event) {
        if (!diagnostics.isPendingDiagnosticsReply(event)) {
            var client = clientMail.clientFor(event);
            if (client.isPresent()) {
                return new Received(null, clientMail.receive(event, client.get()).orElse(null));
            }
        }
        return new Received(diagnostics.receiveInboundEmail(event), null);
    }
}
