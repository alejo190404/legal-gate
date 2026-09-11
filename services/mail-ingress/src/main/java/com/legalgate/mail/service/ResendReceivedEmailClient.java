package com.legalgate.mail.service;

import com.legalgate.mail.config.MailIngressProperties;
import com.legalgate.mail.model.ResendReceivedEmail;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Fetches the stored message an {@code email.received} event points at. */
@Service
public class ResendReceivedEmailClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;

    private final RestClient restClient;
    private final MailIngressProperties properties;

    public ResendReceivedEmailClient(RestClient.Builder restClientBuilder, MailIngressProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
    }

    /**
     * Throws on failure rather than returning null. This runs inside the webhook path, so a
     * failed fetch must reach the caller as a 5xx and let Resend retry — ingesting the event
     * without its body would file an empty Consultation that nothing later corrects.
     */
    public ResendReceivedEmail fetch(String messageStoreId) {
        MailIngressProperties.Resend resend = properties.resend();
        if (resend == null || resend.apiKey() == null || resend.apiKey().isBlank()) {
            throw new IllegalStateException("RESEND_API_KEY is not configured.");
        }
        ResendReceivedEmail email;
        try {
            email = restClient.get()
                    .uri("https://api.resend.com/emails/receiving/{id}", messageStoreId)
                    .headers(headers -> headers.setBearerAuth(resend.apiKey().trim()))
                    .retrieve()
                    .body(ResendReceivedEmail.class);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "resend_retrieve_failed", ex);
        }
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "resend_retrieve_empty");
        }
        return email;
    }
}
