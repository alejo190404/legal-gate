package com.legalgate.intake.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.legalgate.intake.model.ClassificationResult;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.NotificationStatus;
import com.legalgate.intake.service.IntakeService;
import com.legalgate.intake.billing.BillingAccessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class InboundEmailControllerTests {

    private final IntakeService intakeService = mock(IntakeService.class);
    private final BillingAccessService billingAccessService = mock(BillingAccessService.class);
    private final InboundEmailController controller =
            new InboundEmailController(intakeService, billingAccessService);

    @Test
    void acceptsValidInboundEmailEvents() {
        when(billingAccessService.isEntitled("firma-demo")).thenReturn(true);
        InboundEmailReceived event = new InboundEmailReceived(
                "event-1",
                "firma-demo",
                "intake@firma.test",
                List.of("intake@firma.test"),
                "client@example.com",
                "Client <client@example.com>",
                "Consulta",
                "<message@example.com>",
                "Necesito orientacion.",
                "<p>Necesito orientacion.</p>",
                Instant.now()
        );
        when(intakeService.createConsultationFromInboundEmail(event)).thenReturn(new ConsultationResponse(
                "consultation-1",
                "firma-demo",
                "Client",
                "client@example.com",
                "Necesito orientacion.",
                null,
                "RECEIVED",
                "NORMAL",
                "Default intake route",
                "intake@firma.test",
                new ClassificationResult("LLM_CLASSIFIED", List.of(), "General", "Routed.", 0.8),
                new NotificationStatus(false, false, "intake@firma.test", null),
                "event-1",
                "<message@example.com>",
                Instant.now()
        ));

        ResponseEntity<Map<String, Object>> response = controller.receive(event);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "created")
                .containsEntry("eventId", "event-1")
                .containsEntry("tenantId", "firma-demo")
                .containsEntry("consultationId", "consultation-1");
        verify(intakeService).createConsultationFromInboundEmail(event);
    }

    @Test
    void acknowledgesButIgnoresEmailForInactiveSubscription() {
        InboundEmailReceived event = new InboundEmailReceived(
                "event-inactive", "firma-demo", "intake@firma.test", List.of("intake@firma.test"),
                "client@example.com", null, "Consulta", null, "Contenido", null, Instant.now());
        when(billingAccessService.isEntitled("firma-demo")).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.receive(event);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "ignored_subscription_inactive");
    }

    @Test
    void rejectsMalformedInboundEmailEventsWithBadRequest() {
        InboundEmailReceived event = new InboundEmailReceived(
                "event-1",
                "",
                "intake@firma.test",
                List.of("intake@firma.test"),
                "client@example.com",
                null,
                "Consulta",
                null,
                "Necesito orientacion.",
                null,
                Instant.now()
        );

        assertThatThrownBy(() -> controller.receive(event))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException responseStatus = (ResponseStatusException) exception;
                    assertThat(responseStatus.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(responseStatus.getReason()).isEqualTo("missing_tenant_id");
                });
    }
}
