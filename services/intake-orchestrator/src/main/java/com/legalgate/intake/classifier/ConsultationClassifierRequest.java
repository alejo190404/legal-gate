package com.legalgate.intake.classifier;

import java.util.List;

public record ConsultationClassifierRequest(
        InboundEmail email,
        List<Route> routes,
        String systemPrompt,
        String promptVersion
) {
    public record InboundEmail(
            String subject,
            String plain,
            String html,
            String sender,
            List<String> recipients,
            String messageId
    ) {
    }

    public record Route(
            int routeIndex,
            String name,
            String description,
            String destinationEmail,
            List<String> keywords,
            List<String> windows,
            List<String> urgencyLevels
    ) {
    }
}

