package com.legalgate.mail.service;

import com.legalgate.mail.config.MailIngressProperties;
import com.legalgate.mail.model.InboundEmailReceived;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class InboundEmailClient {

    private final RestClient restClient;

    public InboundEmailClient(RestClient.Builder restClientBuilder, MailIngressProperties properties) {
        this.restClient = restClientBuilder
                .baseUrl(properties.intakeOrchestrator().baseUrl().toString())
                .build();
    }

    public void send(InboundEmailReceived event) {
        restClient.post()
                .uri("/api/internal/inbound-emails")
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .toBodilessEntity();
    }
}
