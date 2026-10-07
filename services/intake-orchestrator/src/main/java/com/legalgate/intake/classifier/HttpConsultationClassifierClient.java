package com.legalgate.intake.classifier;

import com.legalgate.intake.config.IntakeProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
class HttpConsultationClassifierClient implements ConsultationClassifierClient {

    private final IntakeProperties intakeProperties;
    private final RestTemplate restTemplate;

    HttpConsultationClassifierClient(IntakeProperties intakeProperties, RestTemplateBuilder restTemplateBuilder) {
        this.intakeProperties = intakeProperties;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(intakeProperties.consultationClassifierTimeout())
                .setReadTimeout(intakeProperties.consultationClassifierTimeout())
                .build();
    }

    @Override
    public ConsultationClassifierResponse classify(ConsultationClassifierRequest request) {
        String classifierUrl = normalizedClassifierUrl();
        if (classifierUrl == null) {
            throw new ClassifierUnavailableException("consultation_classifier_not_configured");
        }
        try {
            return restTemplate.postForObject(
                    classifierUrl + "/classify-consultation",
                    request,
                    ConsultationClassifierResponse.class
            );
        } catch (RestClientException ex) {
            throw new ClassifierUnavailableException("consultation_classifier_unavailable", ex);
        }
    }

    @Override
    public ConsultationDiagnosticsResponse diagnose(ConsultationDiagnosticsRequest request) {
        String classifierUrl = normalizedClassifierUrl();
        if (classifierUrl == null) {
            throw new ClassifierUnavailableException("consultation_classifier_not_configured");
        }
        try {
            return restTemplate.postForObject(
                    classifierUrl + "/diagnose-consultation",
                    request,
                    ConsultationDiagnosticsResponse.class
            );
        } catch (RestClientException ex) {
            throw new ClassifierUnavailableException("consultation_classifier_unavailable", ex);
        }
    }

    @Override
    public ClientMailIntentResponse classifyClientMail(ClientMailIntentRequest request) {
        String classifierUrl = normalizedClassifierUrl();
        if (classifierUrl == null) {
            throw new ClassifierUnavailableException("consultation_classifier_not_configured");
        }
        ClientMailIntentResponse response;
        try {
            response = restTemplate.postForObject(
                    classifierUrl + "/classify-client-mail",
                    request,
                    ClientMailIntentResponse.class
            );
        } catch (RestClientException ex) {
            throw new ClassifierUnavailableException("consultation_classifier_unavailable", ex);
        }
        if (response == null || !(response.isStatusInquiry() || ClientMailIntentResponse.NEEDS_LAWYER.equals(response.intent()))) {
            throw new ClassifierUnavailableException("consultation_classifier_unusable_response");
        }
        return response;
    }

    private String normalizedClassifierUrl() {
        String configuredUrl = intakeProperties.consultationClassifierUrl();
        if (configuredUrl == null || configuredUrl.isBlank()) {
            return null;
        }
        return configuredUrl.endsWith("/")
                ? configuredUrl.substring(0, configuredUrl.length() - 1)
                : configuredUrl;
    }
}
