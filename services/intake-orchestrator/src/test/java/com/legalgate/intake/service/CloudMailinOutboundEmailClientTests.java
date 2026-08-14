package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.repository.IntakeRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class CloudMailinOutboundEmailClientTests {

    @Test
    void clientFacingMailSendsUnderTheFirmName() {
        String from = client("Vargas & Asociados").fromHeader(notification("CLIENT", "diag+tok3n@intake.legal-gate.co"));

        assertThat(from).isEqualTo("\"Vargas & Asociados\" <diag+tok3n@intake.legal-gate.co>");
    }

    @Test
    void staffMailKeepsLegalGateBranding() {
        String from = client("Vargas & Asociados").fromHeader(notification("LAWYER", null));

        assertThat(from).isEqualTo("LegalGate Agenda <agenda@legal-gate.co>");
    }

    @Test
    void quotesAndEscapesFirmNamesThatWouldBreakTheFromHeader() {
        String from = client("Vargas, \"Los\" Abogados\r\nBcc: leak@example.com")
                .fromHeader(notification("CLIENT", null));

        assertThat(from).isEqualTo("\"Vargas, \\\"Los\\\" Abogados Bcc: leak@example.com\" <agenda@legal-gate.co>");
    }

    private CloudMailinOutboundEmailClient client(String tenantDisplayName) {
        IntakeProperties properties = new IntakeProperties(
                "memory", false, "intake.legal-gate.co", null, null, null, null, null, null,
                false, null, null, null, null, false, "test-service-token", "sk_test", "https://api.workos.com");
        IntakeRepository repository = mock(IntakeRepository.class);
        when(repository.tenantDisplayName(anyString())).thenReturn(Optional.ofNullable(tenantDisplayName));
        return new CloudMailinOutboundEmailClient(
                properties, RestClient.builder(), new FirmNameResolver(repository, properties));
    }

    private NotificationOutboxItem notification(String recipientRole, String fromEmail) {
        return new NotificationOutboxItem(
                "notification-1", "firma-demo", "consultation-1", null, "DIAGNOSTICS_QUESTION", recipientRole,
                "cliente@example.com", fromEmail, "Asunto", "Cuerpo", null, null,
                "PENDING", 0, null, null, null, null, null);
    }
}
