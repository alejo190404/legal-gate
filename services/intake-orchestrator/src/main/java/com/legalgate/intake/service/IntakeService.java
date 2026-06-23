package com.legalgate.intake.service;

import com.legalgate.intake.classifier.ClassifierUnavailableException;
import com.legalgate.intake.classifier.ConsultationClassifierClient;
import com.legalgate.intake.classifier.ConsultationClassifierRequest;
import com.legalgate.intake.classifier.ConsultationClassifierResponse;
import com.legalgate.intake.model.ClassificationResult;
import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.CreateConsultationRequest;
import com.legalgate.intake.model.NotificationStatus;
import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.mail.InboundEmailReceived;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.TenantSettingsResponse;
import com.legalgate.intake.repository.IntakeRepository;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntakeService {

    private static final List<String> DEFAULT_URGENCY_LEVELS = List.of("NORMAL", "URGENT");

    private static final TenantRoutingRule DEFAULT_ROUTING_RULE = new TenantRoutingRule(
            "Default intake route",
            null,
            List.of("audiencia", "captura", "tutela", "vencimiento"),
            List.of(),
            DEFAULT_URGENCY_LEVELS,
            null
    );
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);

    private static final TenantSettingsResponse DEFAULT_SETTINGS = new TenantSettingsResponse(
            "default",
            DEFAULT_ROUTING_RULE.urgentKeywords(),
            DEFAULT_ROUTING_RULE.consultationWindows(),
            DEFAULT_URGENCY_LEVELS,
            DEFAULT_ROUTING_RULE.destinationEmail(),
            null,
            List.of(DEFAULT_ROUTING_RULE)
    );

    private final IntakeRepository intakeRepository;
    private final IntakeProperties intakeProperties;
    private final ConsultationClassifierClient consultationClassifierClient;

    public IntakeService(
            IntakeRepository intakeRepository,
            IntakeProperties intakeProperties,
            ConsultationClassifierClient consultationClassifierClient
    ) {
        this.intakeRepository = intakeRepository;
        this.intakeProperties = intakeProperties;
        this.consultationClassifierClient = consultationClassifierClient;
    }

    public TenantSettingsResponse saveSettings(String tenantId, TenantSettingsRequest request) {
        List<TenantRoutingRule> routingRules = routingRulesFrom(request);
        TenantRoutingRule primaryRule = primaryRoutingRule(routingRules);
        List<String> urgencyLevels = derivedUrgencyLevels(routingRules);
        TenantSettingsResponse settings = new TenantSettingsResponse(
                tenantId,
                primaryRule.urgentKeywords(),
                primaryRule.consultationWindows(),
                urgencyLevels,
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
        List<String> urgencyLevels = urgencyLevelsFor(routingRule);
        String urgency = matchedKeywords.isEmpty() ? urgencyLevels.get(0) : urgencyLevels.get(urgencyLevels.size() - 1);
        ClassificationResult classification = new ClassificationResult(
                "MANUAL_REVIEW",
                matchedKeywords,
                null,
                "Pending LLM classification; routed to " + routingRule.name() + " for lawyer review.",
                null
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
                routingRule.name(),
                routingRule.destinationEmail(),
                classification,
                notifications,
                null,
                null,
                Instant.now()
        );
        return intakeRepository.saveConsultation(tenantId, consultation);
    }

    public ConsultationResponse createConsultationFromInboundEmail(InboundEmailReceived event) {
        String sourceMessageId = sanitizeTraceValue(event.messageId());
        if (sourceMessageId != null) {
            Optional<ConsultationResponse> existing =
                    intakeRepository.consultationForSourceMessageId(event.tenantId(), sourceMessageId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        TenantSettingsResponse settings = settingsFor(event.tenantId());
        List<TenantRoutingRule> routingRules = routingRulesFor(settings);
        ConsultationClassifierResponse classifierResponse;
        try {
            classifierResponse = consultationClassifierClient.classify(
                    classifierRequestFor(event, routingRules)
            );
        } catch (ClassifierUnavailableException ex) {
            return saveFallbackInboundConsultation(event, routingRules, "LLM_FAILED");
        }

        if (!isValidClassifierResponse(classifierResponse, routingRules)) {
            return saveFallbackInboundConsultation(event, routingRules, "LLM_INVALID_RESPONSE");
        }

        TenantRoutingRule selectedRoute = routingRules.get(classifierResponse.routeIndex());
        String summary = firstNonBlank(classifierResponse.summary(), event.plain(), event.subject(), "Inbound email received.");
        String clientName = firstNonBlank(classifierResponse.clientName(), senderDisplayName(event.headerFrom()), "Unknown client");
        String preferredWindow = firstConfiguredWindow(selectedRoute.consultationWindows());
        ClassificationResult classification = new ClassificationResult(
                "LLM_CLASSIFIED",
                List.of(),
                sanitizeNullable(classifierResponse.concept()),
                sanitizeNullable(classifierResponse.explanation()),
                classifierResponse.confidence()
        );
        return intakeRepository.saveConsultation(event.tenantId(), new ConsultationResponse(
                UUID.randomUUID().toString(),
                event.tenantId(),
                clientName,
                clientEmailFor(event),
                summary.trim(),
                preferredWindow,
                "RECEIVED",
                classifierResponse.urgency().trim(),
                selectedRoute.name(),
                selectedRoute.destinationEmail(),
                classification,
                new NotificationStatus(false, false, selectedRoute.destinationEmail(), preferredWindow),
                sanitizeTraceValue(event.eventId()),
                sourceMessageId,
                Instant.now()
        ));
    }

    public ConsultationListResponse consultationsForTenant(String tenantId) {
        // TODO(workos): Protected/admin routes should derive tenant context from WorkOS organization claims
        // at the gateway, not from arbitrary browser-supplied tenant path parameters.
        return intakeRepository.consultationsForTenant(tenantId);
    }

    private ConsultationResponse saveFallbackInboundConsultation(
            InboundEmailReceived event,
            List<TenantRoutingRule> routingRules,
            String label
    ) {
        TenantRoutingRule primaryRoute = primaryRoutingRule(routingRules);
        String preferredWindow = firstConfiguredWindow(primaryRoute.consultationWindows());
        String summary = firstNonBlank(event.plain(), event.subject(), event.html(), "Inbound email received.");
        ClassificationResult classification = new ClassificationResult(
                label,
                List.of(),
                null,
                "Gemini classification was not available or could not be validated; routed to primary route for manual review.",
                null
        );
        return intakeRepository.saveConsultation(event.tenantId(), new ConsultationResponse(
                UUID.randomUUID().toString(),
                event.tenantId(),
                firstNonBlank(senderDisplayName(event.headerFrom()), "Unknown client"),
                clientEmailFor(event),
                summary.trim(),
                preferredWindow,
                "RECEIVED",
                urgencyLevelsFor(primaryRoute).get(0),
                primaryRoute.name(),
                primaryRoute.destinationEmail(),
                classification,
                new NotificationStatus(false, false, primaryRoute.destinationEmail(), preferredWindow),
                sanitizeTraceValue(event.eventId()),
                sanitizeTraceValue(event.messageId()),
                Instant.now()
        ));
    }

    private ConsultationClassifierRequest classifierRequestFor(
            InboundEmailReceived event,
            List<TenantRoutingRule> routingRules
    ) {
        List<ConsultationClassifierRequest.Route> routes = new ArrayList<>();
        for (int index = 0; index < routingRules.size(); index++) {
            TenantRoutingRule rule = routingRules.get(index);
            routes.add(new ConsultationClassifierRequest.Route(
                    index,
                    rule.name(),
                    rule.description(),
                    rule.destinationEmail(),
                    rule.urgentKeywords(),
                    rule.consultationWindows(),
                    rule.urgencyLevels()
            ));
        }
        return new ConsultationClassifierRequest(
                new ConsultationClassifierRequest.InboundEmail(
                        event.subject(),
                        event.plain(),
                        event.html(),
                        firstNonBlank(event.headerFrom(), event.envelopeFrom(), clientEmailFor(event)),
                        event.recipients() == null ? List.of() : event.recipients(),
                        event.messageId()
                ),
                routes,
                intakeProperties.consultationClassifierSystemPrompt(),
                intakeProperties.consultationClassifierPromptVersion()
        );
    }

    private boolean isValidClassifierResponse(
            ConsultationClassifierResponse response,
            List<TenantRoutingRule> routingRules
    ) {
        if (response == null || response.routeIndex() == null || response.urgency() == null) {
            return false;
        }
        if (response.routeIndex() < 0 || response.routeIndex() >= routingRules.size()) {
            return false;
        }
        TenantRoutingRule selectedRoute = routingRules.get(response.routeIndex());
        return urgencyLevelsFor(selectedRoute).contains(response.urgency().trim());
    }

    private TenantSettingsResponse settingsFor(String tenantId) {
        TenantSettingsResponse defaults = new TenantSettingsResponse(
                tenantId,
                DEFAULT_SETTINGS.urgentKeywords(),
                DEFAULT_SETTINGS.consultationWindows(),
                DEFAULT_SETTINGS.urgencyLevels(),
                DEFAULT_SETTINGS.destinationEmail(),
                intakeProperties.canonicalIntakeEmail(tenantId),
                DEFAULT_SETTINGS.routingRules()
        );
        TenantSettingsResponse settings = normalizeSettings(intakeRepository.settingsFor(tenantId, defaults), tenantId);
        if (settings.intakeEmail() == null || settings.intakeEmail().isBlank()) {
            TenantSettingsResponse healedSettings = new TenantSettingsResponse(
                    settings.tenantId(),
                    settings.urgentKeywords(),
                    settings.consultationWindows(),
                    settings.urgencyLevels(),
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

    private TenantSettingsResponse normalizeSettings(TenantSettingsResponse settings, String tenantId) {
        List<TenantRoutingRule> routingRules = routingRulesFor(settings);
        TenantRoutingRule primaryRule = primaryRoutingRule(routingRules);
        return new TenantSettingsResponse(
                settings.tenantId() == null ? tenantId : settings.tenantId(),
                primaryRule.urgentKeywords(),
                primaryRule.consultationWindows(),
                derivedUrgencyLevels(routingRules),
                primaryRule.destinationEmail(),
                settings.intakeEmail(),
                routingRules
        );
    }

    private List<TenantRoutingRule> routingRulesFrom(TenantSettingsRequest request) {
        if (request.routingRules() == null || request.routingRules().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "routing_rules_required");
        }
        List<TenantRoutingRule> rules = new ArrayList<>();
        for (int index = 0; index < request.routingRules().size(); index++) {
            rules.add(sanitizeRoutingRule(request.routingRules().get(index), index));
        }
        return List.copyOf(rules);
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
                sanitizeNullable(rule.description()),
                sanitize(rule.urgentKeywords()),
                sanitize(rule.consultationWindows()),
                sanitizeUrgencyLevels(rule.urgencyLevels()),
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
        List<String> fallbackUrgencyLevels = urgencyLevelsFor(settings);
        if (settings.routingRules() != null && !settings.routingRules().isEmpty()) {
            List<TenantRoutingRule> rules = new ArrayList<>();
            for (int index = 0; index < settings.routingRules().size(); index++) {
                TenantRoutingRule rule = settings.routingRules().get(index);
                List<String> ruleUrgencyLevels = rule.urgencyLevels() == null || rule.urgencyLevels().isEmpty()
                        ? fallbackUrgencyLevels
                        : rule.urgencyLevels();
                rules.add(new TenantRoutingRule(
                        rule.name() == null || rule.name().isBlank() ? "Route " + (index + 1) : rule.name().trim(),
                        sanitizeNullable(rule.description()),
                        sanitize(rule.urgentKeywords()),
                        sanitize(rule.consultationWindows()),
                        sanitizeUrgencyLevels(ruleUrgencyLevels),
                        sanitizeEmail(rule.destinationEmail())
                ));
            }
            return List.copyOf(rules);
        }
        return List.of(new TenantRoutingRule(
                "Default intake route",
                null,
                sanitize(settings.urgentKeywords()),
                sanitize(settings.consultationWindows()),
                fallbackUrgencyLevels,
                sanitizeEmail(settings.destinationEmail())
        ));
    }

    private List<String> urgencyLevelsFor(TenantRoutingRule rule) {
        if (rule.urgencyLevels() == null || rule.urgencyLevels().isEmpty()) {
            return DEFAULT_URGENCY_LEVELS;
        }
        return rule.urgencyLevels();
    }

    private List<String> urgencyLevelsFor(TenantSettingsResponse settings) {
        if (settings.urgencyLevels() == null || settings.urgencyLevels().isEmpty()) {
            return DEFAULT_URGENCY_LEVELS;
        }
        return sanitizeUrgencyLevels(settings.urgencyLevels());
    }

    private List<String> derivedUrgencyLevels(List<TenantRoutingRule> routingRules) {
        LinkedHashSet<String> levels = new LinkedHashSet<>();
        for (TenantRoutingRule rule : routingRules) {
            levels.addAll(urgencyLevelsFor(rule));
        }
        return levels.isEmpty() ? DEFAULT_URGENCY_LEVELS : List.copyOf(levels);
    }

    private String preferredWindowFor(String requestedWindow, List<String> configuredWindows) {
        if (requestedWindow != null && !requestedWindow.isBlank()) {
            return requestedWindow.trim();
        }
        return configuredWindows == null || configuredWindows.isEmpty() ? null : configuredWindows.get(0);
    }

    private String firstConfiguredWindow(List<String> configuredWindows) {
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

    private List<String> sanitizeUrgencyLevels(List<String> values) {
        if (values == null) {
            return DEFAULT_URGENCY_LEVELS;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.trim().isBlank() || !seen.add(value.trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_urgency_levels");
            }
        }
        if (seen.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_urgency_levels");
        }
        return List.copyOf(seen);
    }

    private String clientEmailFor(InboundEmailReceived event) {
        return firstEmail(event.headerFrom())
                .or(() -> firstEmail(event.envelopeFrom()))
                .orElse("unknown@example.invalid")
                .toLowerCase(Locale.ROOT);
    }

    private Optional<String> firstEmail(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = EMAIL_PATTERN.matcher(value);
        if (matcher.find()) {
            return Optional.of(matcher.group().trim());
        }
        return Optional.empty();
    }

    private String senderDisplayName(String headerFrom) {
        if (headerFrom == null || headerFrom.isBlank()) {
            return null;
        }
        String withoutEmail = headerFrom.replaceAll("<[^>]+>", "").trim();
        if (withoutEmail.isBlank() || withoutEmail.contains("@")) {
            return null;
        }
        return withoutEmail.replaceAll("^\"|\"$", "").trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String sanitizeTraceValue(String value) {
        return sanitizeNullable(value);
    }

    private String sanitizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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


