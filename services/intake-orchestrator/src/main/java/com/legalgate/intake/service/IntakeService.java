package com.legalgate.intake.service;

import com.legalgate.intake.model.ClassificationResult;
import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.CreateConsultationRequest;
import com.legalgate.intake.model.NotificationStatus;
import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.TenantSettingsResponse;
import com.legalgate.intake.repository.IntakeRepository;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntakeService {

    private static final TenantRoutingRule DEFAULT_ROUTING_RULE = new TenantRoutingRule(
            "Default intake route",
            List.of("audiencia", "captura", "tutela", "vencimiento"),
            List.of(),
            null
    );

    private static final TenantSettingsResponse DEFAULT_SETTINGS = new TenantSettingsResponse(
            "default",
            DEFAULT_ROUTING_RULE.urgentKeywords(),
            DEFAULT_ROUTING_RULE.consultationWindows(),
            DEFAULT_ROUTING_RULE.destinationEmail(),
            null,
            List.of(DEFAULT_ROUTING_RULE)
    );

    private final IntakeRepository intakeRepository;
    private final IntakeProperties intakeProperties;

    public IntakeService(IntakeRepository intakeRepository, IntakeProperties intakeProperties) {
        this.intakeRepository = intakeRepository;
        this.intakeProperties = intakeProperties;
    }

    public TenantSettingsResponse saveSettings(String tenantId, TenantSettingsRequest request) {
        List<TenantRoutingRule> routingRules = routingRulesFrom(request);
        TenantRoutingRule primaryRule = primaryRoutingRule(routingRules);
        TenantSettingsResponse settings = new TenantSettingsResponse(
                tenantId,
                primaryRule.urgentKeywords(),
                primaryRule.consultationWindows(),
                primaryRule.destinationEmail(),
                intakeProperties.canonicalIntakeEmail(tenantId),
                routingRules
        );
        return intakeRepository.saveSettings(tenantId, settings);
    }

    public TenantSettingsResponse settingsForTenant(String tenantId) {
        return settingsFor(tenantId);
    }

    public ConsultationResponse createConsultation(String tenantId, CreateConsultationRequest request) {
        TenantSettingsResponse settings = settingsFor(tenantId);
        TenantRoutingRule routingRule = routingRuleForSummary(request.summary(), routingRulesFor(settings));
        List<String> matchedKeywords = matchUrgentKeywords(request.summary(), routingRule.urgentKeywords());
        String preferredWindow = preferredWindowFor(request.preferredWindow(), routingRule.consultationWindows());
        String urgency = matchedKeywords.isEmpty() ? "NORMAL" : "URGENT";
        ClassificationResult classification = new ClassificationResult(
                "MANUAL_REVIEW",
                matchedKeywords,
                "Pending LLM classification; routed to " + routingRule.name() + " for lawyer review."
        );
        NotificationStatus notifications = new NotificationStatus(
                true,
                true,
                routingRule.destinationEmail(),
                preferredWindow
        );
        ConsultationResponse consultation = new ConsultationResponse(
                UUID.randomUUID().toString(),
                tenantId,
                request.clientName().trim(),
                request.clientEmail().trim(),
                request.summary().trim(),
                preferredWindow,
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
                intakeProperties.canonicalIntakeEmail(tenantId),
                DEFAULT_SETTINGS.routingRules()
        );
        TenantSettingsResponse settings = intakeRepository.settingsFor(tenantId, defaults);
        if (settings.intakeEmail() == null || settings.intakeEmail().isBlank()) {
            TenantSettingsResponse healedSettings = new TenantSettingsResponse(
                    settings.tenantId(),
                    settings.urgentKeywords(),
                    settings.consultationWindows(),
                    settings.destinationEmail(),
                    intakeProperties.canonicalIntakeEmail(tenantId),
                    settings.routingRules()
            );
            // TODO(rollout): This GET mutates state only to backfill missing intake emails
            // for tenants created before system-owned intake addresses existed.
            return intakeRepository.saveSettings(tenantId, healedSettings);
        }
        return settings;
    }

    private List<TenantRoutingRule> routingRulesFrom(TenantSettingsRequest request) {
        if (request.routingRules() != null && !request.routingRules().isEmpty()) {
            List<TenantRoutingRule> rules = new ArrayList<>();
            for (int index = 0; index < request.routingRules().size(); index++) {
                rules.add(sanitizeRoutingRule(request.routingRules().get(index), index));
            }
            return List.copyOf(rules);
        }

        String destinationEmail = sanitizeRequiredEmail(
                request.destinationEmail(),
                "destination_email_required"
        );
        return List.of(new TenantRoutingRule(
                "Default intake route",
                sanitize(request.urgentKeywords()),
                sanitize(request.consultationWindows()),
                destinationEmail
        ));
    }

    private TenantRoutingRule sanitizeRoutingRule(TenantRoutingRule rule, int index) {
        if (rule == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "routing_rule_required");
        }
        String name = rule.name() == null || rule.name().isBlank()
                ? "Route " + (index + 1)
                : rule.name().trim();
        return new TenantRoutingRule(
                name,
                sanitize(rule.urgentKeywords()),
                sanitize(rule.consultationWindows()),
                sanitizeRequiredEmail(rule.destinationEmail(), "routing_rule_destination_email_required")
        );
    }

    private TenantRoutingRule routingRuleForSummary(String summary, List<TenantRoutingRule> routingRules) {
        return routingRules.stream()
                .filter(rule -> !matchUrgentKeywords(summary, rule.urgentKeywords()).isEmpty())
                .findFirst()
                .orElse(primaryRoutingRule(routingRules));
    }

    private TenantRoutingRule primaryRoutingRule(List<TenantRoutingRule> routingRules) {
        return routingRules == null || routingRules.isEmpty() ? DEFAULT_ROUTING_RULE : routingRules.get(0);
    }

    private List<TenantRoutingRule> routingRulesFor(TenantSettingsResponse settings) {
        if (settings.routingRules() != null && !settings.routingRules().isEmpty()) {
            return settings.routingRules();
        }
        return List.of(new TenantRoutingRule(
                "Default intake route",
                settings.urgentKeywords(),
                settings.consultationWindows(),
                settings.destinationEmail()
        ));
    }

    private String preferredWindowFor(String requestedWindow, List<String> configuredWindows) {
        if (requestedWindow != null && !requestedWindow.isBlank()) {
            return requestedWindow.trim();
        }
        return configuredWindows == null || configuredWindows.isEmpty() ? null : configuredWindows.get(0);
    }

    private String sanitizeRequiredEmail(String email, String reason) {
        String sanitizedEmail = sanitizeEmail(email);
        if (sanitizedEmail == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }
        return sanitizedEmail;
    }

    private String sanitizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> matchUrgentKeywords(String summary, List<String> urgentKeywords) {
        if (urgentKeywords == null) {
            return List.of();
        }
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
