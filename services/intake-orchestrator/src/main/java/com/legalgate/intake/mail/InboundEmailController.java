package com.legalgate.intake.mail;

import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.service.IntakeService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/internal/inbound-emails")
class InboundEmailController {

    private static final Logger log = LoggerFactory.getLogger(InboundEmailController.class);

    private final IntakeService intakeService;

    InboundEmailController(IntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> receive(@RequestBody InboundEmailReceived event) {
        validate(event);
        log.info(
                "Received inbound email event id={} tenant={} from={} subject={}",
                event.eventId(),
                event.tenantId(),
                event.envelopeFrom(),
                event.subject()
        );
        ConsultationResponse consultation = intakeService.createConsultationFromInboundEmail(event);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "created");
        response.put("eventId", event.eventId());
        response.put("tenantId", event.tenantId());
        response.put("consultationId", consultation.id());
        return ResponseEntity.ok(response);
    }

    private void validate(InboundEmailReceived event) {
        if (event == null || event.tenantId() == null || event.tenantId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_tenant_id");
        }
    }
}
