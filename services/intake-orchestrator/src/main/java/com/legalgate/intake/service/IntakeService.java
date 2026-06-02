package com.legalgate.intake.service;

import com.legalgate.intake.model.ClassificationResult;
import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.CreateConsultationRequest;
import com.legalgate.intake.model.NotificationStatus;
import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.TenantSettingsResponse;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class IntakeService {

    private static final TenantSettingsResponse DEFAULT_SETTINGS = new TenantSettingsResponse(
            "default",
            List.of("audiencia", "captura", "tutela", "vencimiento"),
            List.of(),
            null
    );

    private final ConcurrentMap<String, TenantSettingsResponse> tenantSettings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ConsultationResponse>> consultationsByTenant = new ConcurrentHashMap<>();

    public TenantSettingsResponse saveSettings(String tenantId, TenantSettingsRequest request) {
        TenantSettingsResponse settings = new TenantSettingsResponse(
                tenantId,
                sanitize(request.urgentKeywords()),
                sanitize(request.consultationWindows()),
                request.destinationEmail().trim()
        );
        tenantSettings.put(tenantId, settings);
        return settings;
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
        consultationsByTenant.computeIfAbsent(tenantId, ignored -> new ArrayList<>()).add(consultation);
        return consultation;
    }

    public ConsultationListResponse consultationsForTenant(String tenantId) {
        List<ConsultationResponse> consultations = consultationsByTenant.getOrDefault(tenantId, List.of());
        return new ConsultationListResponse(tenantId, List.copyOf(consultations));
    }

    private TenantSettingsResponse settingsFor(String tenantId) {
        return tenantSettings.getOrDefault(tenantId, new TenantSettingsResponse(
                tenantId,
                DEFAULT_SETTINGS.urgentKeywords(),
                DEFAULT_SETTINGS.consultationWindows(),
                DEFAULT_SETTINGS.destinationEmail()
        ));
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
