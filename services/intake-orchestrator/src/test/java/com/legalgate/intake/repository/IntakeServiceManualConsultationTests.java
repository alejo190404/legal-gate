package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.legalgate.intake.classifier.ClassifierUnavailableException;
import com.legalgate.intake.classifier.ConsultationClassifierClient;
import com.legalgate.intake.classifier.ConsultationClassifierRequest;
import com.legalgate.intake.classifier.ConsultationClassifierResponse;
import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.CreateConsultationRequest;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.UrgencyDefinition;
import com.legalgate.intake.service.EmailTemplateRenderer;
import com.legalgate.intake.service.IntakeService;
import java.util.List;
import org.junit.jupiter.api.Test;

// Manually created consultations must take the same path as inbound email:
// LLM classification, lawyer scheduling, and queued notifications. MANUAL_REVIEW is gone.
// Lives in the repository package to reach the package-private InMemoryIntakeRepository.
class IntakeServiceManualConsultationTests {

    private static final String TENANT = "tenant-a";

    @Test
    void manualConsultationIsLlmClassifiedScheduledAndEmailed() {
        InMemoryIntakeRepository repository = new InMemoryIntakeRepository();
        StubClassifier classifier = new StubClassifier();
        classifier.response = new ConsultationClassifierResponse(
                0, "Contratos", "NORMAL", "Arriendo", "Resumen", "Juan", "Routed to general.", 0.9);
        IntakeService service = seededService(repository, classifier);

        ConsultationResponse consultation = service.createConsultation(TENANT, request());

        assertThat(consultation.classification().label()).isEqualTo("LLM_CLASSIFIED");
        assertThat(consultation.eventId()).isNotNull();
        assertThat(consultation.event().scheduledStart()).isNotNull();
        assertThat(repository.claimPendingNotifications(10)).isNotEmpty();
    }

    @Test
    void manualConsultationStillScheduledWhenClassifierUnavailable() {
        InMemoryIntakeRepository repository = new InMemoryIntakeRepository();
        StubClassifier classifier = new StubClassifier();
        classifier.failure = new ClassifierUnavailableException("down");
        IntakeService service = seededService(repository, classifier);

        ConsultationResponse consultation = service.createConsultation(TENANT, request());

        assertThat(consultation.classification().label()).isEqualTo("LLM_FAILED");
        assertThat(consultation.classification().label()).isNotEqualTo("MANUAL_REVIEW");
        assertThat(consultation.eventId()).isNotNull();
        assertThat(repository.claimPendingNotifications(10)).isNotEmpty();
    }

    private IntakeService seededService(InMemoryIntakeRepository repository, ConsultationClassifierClient classifier) {
        IntakeService service = new IntakeService(repository, properties(), classifier, new EmailTemplateRenderer());
        service.saveSettings(TENANT, new TenantSettingsRequest(
                List.of(new TenantRoutingRule(
                        "General", "General intake", List.of(), List.of("manana"),
                        List.of("NORMAL", "URGENT"), null,
                        List.of(new UrgencyDefinition("NORMAL", 1, 5, true), new UrgencyDefinition("URGENT", 2, 1, true)),
                        "ana@firm.co")),
                List.of(new LawyerProfile(null, "Ana Abogada", "ana@firm.co", true, 60, null))));
        return service;
    }

    private CreateConsultationRequest request() {
        return new CreateConsultationRequest("Juan Cliente", "juan@client.co",
                "Necesito ayuda con un contrato de arriendo urgente por favor", null);
    }

    private IntakeProperties properties() {
        return new IntakeProperties(
                "memory", false, "intake.legal-gate.co", null, null, null, null,
                false, null, null, null, null, false, "test-token", "test-key", null);
    }

    private static final class StubClassifier implements ConsultationClassifierClient {
        ConsultationClassifierResponse response;
        RuntimeException failure;

        @Override
        public ConsultationClassifierResponse classify(ConsultationClassifierRequest request) {
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
