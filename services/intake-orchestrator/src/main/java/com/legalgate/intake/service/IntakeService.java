package com.legalgate.intake.service;

import com.legalgate.intake.model.ClassificationResult;
import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.CreateConsultationRequest;
import com.legalgate.intake.model.NotificationStatus;
import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.TenantSettingsResponse;
import com.legalgate.intake.repository.IntakeRepository;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IntakeService {

    private static final TenantSettingsResponse DEFAULT_SETTINGS = new TenantSettingsResponse(
            "default",
            List.of("audiencia", "captura", "tutela", "vencimiento"),
            List.of(),
            null,
            null
    );

    private final IntakeRepository intakeRepository;

    public IntakeService(IntakeRepository intakeRepository) {
        this.intakeRepository = intakeRepository;
    }

    public TenantSettingsResponse saveSettings(String tenantId, TenantSettingsRequest request) {
        TenantSettingsResponse settings = new TenantSettingsResponse(
                tenantId,
                sanitize(request.urgentKeywords()),
                sanitize(request.consultationWindows()),
                request.destinationEmail().trim(),
                sanitizeEmail(request.intakeEmail())
        );
        return intakeRepository.saveSettings(tenantId, settings);
    }

    public TenantSettingsResponse settingsForTenant(String tenantId) {
        return settingsFor(tenantId);
    }

    public ConsultationResponse createConsultation(String tenantId, CreateConsultationRequest request) {
        TenantSettingsResponse settings = settingsFor(tenantId);
        List<String> matchedKeywords = matchUrgentKeywords(request.summary(), settings.urgentKeywords());
        String urgency = matchedKeywords.isEmpty() ? "NORMAL" : "URGENT";
        ClassificationResult classification = new ClassificationResult(
                "MANUAL_REVIEW",
                matchedKeywords,
                "Pending LLM classification; plain-language consultation accepted for lawyer review."
        );
        NotificationStatus notifications = new NotificationStatus(
                true,
                true,
                settings.destinationEmail(),
                request.preferredWindow()
        );
        ConsultationResponse consultation = new ConsultationResponse(
                UUID.randomUUID().toString(),
                tenantId,
                request.clientName().trim(),
                request.clientEmail().trim(),
                request.summary().trim(),
                request.preferredWindow(),
                "RECEIVED",
                urgency,
                classification,
                notifications,
                Instant.now()
        );
        return intakeRepository.saveConsultation(tenantId, consultation);
    }

    public ConsultationListResponse consultationsForTenant(String tenantId) {
        // TODO(workos): Protected/admin routes should derive tenant context from WorkOS organization claims
        // at the gateway, not from arbitrary browser-supplied tenant path parameters.
        return intakeRepository.consultationsForTenant(tenantId);
    }

    private TenantSettingsResponse settingsFor(String tenantId) {
        TenantSettingsResponse defaults = new TenantSettingsResponse(
                tenantId,
                DEFAULT_SETTINGS.urgentKeywords(),
                DEFAULT_SETTINGS.consultationWindows(),
                DEFAULT_SETTINGS.destinationEmail(),
                DEFAULT_SETTINGS.intakeEmail()
        );
        return intakeRepository.settingsFor(tenantId, defaults);
    }

    private String sanitizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> matchUrgentKeywords(String summary, List<String> urgentKeywords) {
        String normalizedSummary = normalize(summary);
        return urgentKeywords.stream()
                .filter(keyword -> normalizedSummary.contains(normalize(keyword)))
                .toList();
    }

    private List<String> sanitize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents.toLowerCase(Locale.ROOT);
    }
}
