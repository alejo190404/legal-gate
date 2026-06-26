
package com.legalgate.intake.service;

import com.legalgate.intake.classifier.ClassifierUnavailableException;
import com.legalgate.intake.classifier.ConsultationClassifierClient;
import com.legalgate.intake.classifier.ConsultationClassifierRequest;
import com.legalgate.intake.classifier.ConsultationClassifierResponse;
import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.mail.InboundEmailReceived;
import com.legalgate.intake.model.ClassificationResult;
import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.CreateConsultationRequest;
import com.legalgate.intake.model.EventResponse;
import com.legalgate.intake.model.LawyerAvailabilityWindow;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.model.NotificationStatus;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.TenantSettingsResponse;
import com.legalgate.intake.model.UrgencyDefinition;
import com.legalgate.intake.repository.IntakeRepository;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Bogota");
    private static final List<UrgencyDefinition> DEFAULT_URGENCY_DEFINITIONS = List.of(
            new UrgencyDefinition("NORMAL", 1, 5, true),
            new UrgencyDefinition("URGENT", 2, 1, true)
    );
    private static final List<String> DEFAULT_URGENCY_LEVELS = List.of("NORMAL", "URGENT");

    private static final TenantRoutingRule DEFAULT_ROUTING_RULE = new TenantRoutingRule(
            "Default intake route", null, List.of("audiencia", "captura", "tutela", "vencimiento"), List.of(),
            DEFAULT_URGENCY_LEVELS, null, DEFAULT_URGENCY_DEFINITIONS, null
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final int POST_SLA_SEARCH_DAYS = 90;
    private static final DateTimeFormatter ICS_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"));

    private static final TenantSettingsResponse DEFAULT_SETTINGS = new TenantSettingsResponse(
            "default", DEFAULT_ROUTING_RULE.urgentKeywords(), DEFAULT_ROUTING_RULE.consultationWindows(),
            DEFAULT_URGENCY_LEVELS, DEFAULT_ROUTING_RULE.destinationEmail(), null, List.of(DEFAULT_ROUTING_RULE), List.of()
    );

    private final IntakeRepository intakeRepository;
    private final IntakeProperties intakeProperties;
    private final ConsultationClassifierClient consultationClassifierClient;

    public IntakeService(IntakeRepository intakeRepository, IntakeProperties intakeProperties, ConsultationClassifierClient consultationClassifierClient) {
        this.intakeRepository = intakeRepository;
        this.intakeProperties = intakeProperties;
        this.consultationClassifierClient = consultationClassifierClient;
    }

    public TenantSettingsResponse saveSettings(String tenantId, TenantSettingsRequest request) {
        List<LawyerProfile> lawyers = lawyersFrom(tenantId, request.lawyers(), request.routingRules());
        List<TenantRoutingRule> routingRules = routingRulesFrom(request, lawyers);
        TenantRoutingRule primaryRule = primaryRoutingRule(routingRules);
        TenantSettingsResponse settings = new TenantSettingsResponse(
                tenantId, primaryRule.urgentKeywords(), primaryRule.consultationWindows(), derivedUrgencyLevels(routingRules),
                primaryRule.destinationEmail(), intakeProperties.canonicalIntakeEmail(tenantId), routingRules, lawyers
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
        Instant createdAt = Instant.now();
        SchedulingResult scheduled = scheduleEvent(tenantId, settings, routingRule, urgency, createdAt, "LEGALGATE");
        EventResponse event = scheduled.event();
        String consultationId = UUID.randomUUID().toString();
        ConsultationResponse consultation = new ConsultationResponse(
                consultationId, tenantId, request.clientName().trim(), request.clientEmail().trim(), request.summary().trim(),
                preferredWindow, "RECEIVED", urgency, routingRule.name(), event.lawyerEmail(),
                new ClassificationResult("MANUAL_REVIEW", matchedKeywords, null,
                        "Pending LLM classification; routed to " + routingRule.name() + " for lawyer review.", null),
                new NotificationStatus(true, true, destinationEmailFor(settings, routingRule), preferredWindow),
                null, null, createdAt, event.id(), event
        );
        return intakeRepository.saveConsultation(
                tenantId,
                consultation,
                scheduled.movedEvents(),
                notificationsForScheduling(tenantId, consultation, scheduled)
        );
    }

    public ConsultationResponse createConsultationFromInboundEmail(InboundEmailReceived event) {
        String sourceMessageId = sanitizeTraceValue(event.messageId());
        if (sourceMessageId != null) {
            Optional<ConsultationResponse> existing = intakeRepository.consultationForSourceMessageId(event.tenantId(), sourceMessageId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        TenantSettingsResponse settings = settingsFor(event.tenantId());
        List<TenantRoutingRule> routingRules = routingRulesFor(settings);
        ConsultationClassifierResponse classifierResponse;
        try {
            classifierResponse = consultationClassifierClient.classify(classifierRequestFor(event, routingRules));
        } catch (ClassifierUnavailableException ex) {
            return saveFallbackInboundConsultation(event, settings, routingRules, "LLM_FAILED");
        }
        if (!isValidClassifierResponse(classifierResponse, routingRules)) {
            return saveFallbackInboundConsultation(event, settings, routingRules, "LLM_INVALID_RESPONSE");
        }

        TenantRoutingRule selectedRoute = routingRules.get(classifierResponse.routeIndex());
        String preferredWindow = firstConfiguredWindow(selectedRoute.consultationWindows());
        Instant createdAt = Instant.now();
        SchedulingResult scheduled = scheduleEvent(event.tenantId(), settings, selectedRoute, classifierResponse.urgency().trim(), createdAt, "LEGALGATE");
        EventResponse scheduledEvent = scheduled.event();
        String consultationId = UUID.randomUUID().toString();
        ConsultationResponse consultation = new ConsultationResponse(
                consultationId, event.tenantId(),
                firstNonBlank(classifierResponse.clientName(), senderDisplayName(event.headerFrom()), "Unknown client"),
                clientEmailFor(event), firstNonBlank(classifierResponse.summary(), event.plain(), event.subject(), "Inbound email received.").trim(),
                preferredWindow, "RECEIVED", classifierResponse.urgency().trim(), selectedRoute.name(), scheduledEvent.lawyerEmail(),
                new ClassificationResult("LLM_CLASSIFIED", List.of(), sanitizeNullable(classifierResponse.concept()), sanitizeNullable(classifierResponse.explanation()), classifierResponse.confidence()),
                new NotificationStatus(true, true, destinationEmailFor(settings, selectedRoute), preferredWindow),
                sanitizeTraceValue(event.eventId()), sourceMessageId, createdAt, scheduledEvent.id(), scheduledEvent
        );
        return intakeRepository.saveConsultation(
                event.tenantId(),
                consultation,
                scheduled.movedEvents(),
                notificationsForScheduling(event.tenantId(), consultation, scheduled)
        );
    }

    public ConsultationListResponse consultationsForTenant(String tenantId) {
        return intakeRepository.consultationsForTenant(tenantId);
    }

    private ConsultationResponse saveFallbackInboundConsultation(InboundEmailReceived event, TenantSettingsResponse settings, List<TenantRoutingRule> routingRules, String label) {
        TenantRoutingRule primaryRoute = primaryRoutingRule(routingRules);
        String preferredWindow = firstConfiguredWindow(primaryRoute.consultationWindows());
        String urgency = urgencyLevelsFor(primaryRoute).get(0);
        Instant createdAt = Instant.now();
        SchedulingResult scheduled = scheduleEvent(event.tenantId(), settings, primaryRoute, urgency, createdAt, "LEGALGATE");
        EventResponse scheduledEvent = scheduled.event();
        String consultationId = UUID.randomUUID().toString();
        ConsultationResponse consultation = new ConsultationResponse(
                consultationId, event.tenantId(), firstNonBlank(senderDisplayName(event.headerFrom()), "Unknown client"),
                clientEmailFor(event), firstNonBlank(event.plain(), event.subject(), event.html(), "Inbound email received.").trim(),
                preferredWindow, "RECEIVED", urgency, primaryRoute.name(), scheduledEvent.lawyerEmail(),
                new ClassificationResult(label, List.of(), null,
                        "Gemini classification was not available or could not be validated; routed to primary route for manual review.", null),
                new NotificationStatus(true, true, destinationEmailFor(settings, primaryRoute), preferredWindow),
                sanitizeTraceValue(event.eventId()), sanitizeTraceValue(event.messageId()), createdAt, scheduledEvent.id(), scheduledEvent
        );
        return intakeRepository.saveConsultation(
                event.tenantId(),
                consultation,
                scheduled.movedEvents(),
                notificationsForScheduling(event.tenantId(), consultation, scheduled)
        );
    }

    private SchedulingResult scheduleEvent(String tenantId, TenantSettingsResponse settings, TenantRoutingRule route, String urgencyName, Instant createdAt, String source) {
        UrgencyDefinition urgency = urgencyDefinitionFor(route, urgencyName);
        LawyerProfile lawyer = lawyerFor(settings, route)
                .map(this::lawyerWithDefaultAvailability)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "active_lawyer_required"));
        Instant deadline = addBusinessDays(createdAt, urgency.slaDays());
        int priorityScore = priorityScore(urgency, deadline, createdAt);
        SlotSearchResult slot = findSlot(lawyer, intakeRepository.eventsForLawyer(tenantId, lawyer.id()), createdAt, deadline, priorityScore);
        return new SchedulingResult(eventResponse(route, urgency, deadline, priorityScore, slot.start(), slot.end(), "TENTATIVE", source, lawyer), slot.movedEvents());
    }

    private SlotSearchResult findSlot(LawyerProfile lawyer, List<EventResponse> events, Instant createdAt, Instant deadline, int priorityScore) {
        int durationMinutes = Math.max(1, lawyer.defaultEventDurationMinutes() == null ? 60 : lawyer.defaultEventDurationMinutes());
        List<EventResponse> scheduled = events.stream()
                .filter(event -> event.scheduledStart() != null && event.scheduledEnd() != null)
                .sorted(Comparator.comparing(EventResponse::scheduledStart))
                .toList();
        for (Slot candidate : inSlaCandidateSlots(lawyer, createdAt, deadline, durationMinutes)) {
            if (isFree(candidate, scheduled)) {
                return new SlotSearchResult(candidate.start(), candidate.end(), List.of());
            }
        }
        List<EventResponse> movable = scheduled.stream()
                .filter(event -> "TENTATIVE".equals(event.status()))
                .filter(event -> "LEGALGATE".equals(event.source()))
                .filter(event -> event.priorityScore() < priorityScore)
                .sorted(Comparator.comparing(EventResponse::priorityScore))
                .toList();
        for (EventResponse displaced : movable) {
            List<EventResponse> withoutDisplaced = scheduled.stream().filter(event -> !event.id().equals(displaced.id())).toList();
            for (Slot candidate : inSlaCandidateSlots(lawyer, createdAt, deadline, durationMinutes)) {
                if (isFree(candidate, withoutDisplaced)) {
                    EventResponse placeholder = new EventResponse(
                            UUID.randomUUID().toString(), lawyer.id(), lawyer.displayName(), lawyer.email(),
                            null, null, null, deadline, priorityScore, candidate.start(), candidate.end(),
                            lawyer.meetingUrl(), true, "TENTATIVE", "LEGALGATE"
                    );
                    int displacedDurationMinutes = Math.max(
                            1,
                            (int) Duration.between(displaced.scheduledStart(), displaced.scheduledEnd()).toMinutes()
                    );
                    Slot displacedSlot = earliestFreeSlot(lawyer, withEvent(withoutDisplaced, placeholder), createdAt, displaced.slaDeadline(), displacedDurationMinutes);
                    EventResponse moved = new EventResponse(
                            displaced.id(), displaced.lawyerId(), displaced.lawyerDisplayName(), displaced.lawyerEmail(),
                            displaced.routeName(), displaced.urgencyName(), displaced.slaDays(), displaced.slaDeadline(),
                            displaced.priorityScore(), displacedSlot.start(), displacedSlot.end(), displaced.meetingUrl(),
                            !displacedSlot.end().isAfter(displaced.slaDeadline()), "TENTATIVE", displaced.source()
                    );
                    return new SlotSearchResult(candidate.start(), candidate.end(), List.of(moved));
                }
            }
        }
        Slot postSlaSlot = postSlaCandidateSlots(lawyer, createdAt, deadline, durationMinutes).stream()
                .filter(candidate -> isFree(candidate, scheduled))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no_available_lawyer_slot"));
        return new SlotSearchResult(postSlaSlot.start(), postSlaSlot.end(), List.of());
    }

    private boolean isFree(Slot candidate, List<EventResponse> scheduled) {
        return scheduled.stream().noneMatch(event -> candidate.start().isBefore(event.scheduledEnd()) && candidate.end().isAfter(event.scheduledStart()));
    }

    private Slot earliestFreeSlot(LawyerProfile lawyer, List<EventResponse> scheduled, Instant createdAt, Instant deadline, int durationMinutes) {
        return inSlaCandidateSlots(lawyer, createdAt, deadline, durationMinutes).stream()
                .filter(candidate -> isFree(candidate, scheduled))
                .findFirst()
                .or(() -> postSlaCandidateSlots(lawyer, createdAt, deadline, durationMinutes).stream()
                        .filter(candidate -> isFree(candidate, scheduled))
                        .findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no_available_lawyer_slot"));
    }

    private List<EventResponse> withEvent(List<EventResponse> events, EventResponse event) {
        List<EventResponse> updated = new ArrayList<>(events);
        updated.add(event);
        return updated;
    }

    private List<Slot> inSlaCandidateSlots(LawyerProfile lawyer, Instant createdAt, Instant deadline, int durationMinutes) {
        return candidateSlots(lawyer, createdAt, deadline, durationMinutes).stream()
                .filter(slot -> !slot.end().isAfter(deadline))
                .toList();
    }

    private List<Slot> postSlaCandidateSlots(LawyerProfile lawyer, Instant createdAt, Instant deadline, int durationMinutes) {
        Instant searchStart = deadline.isAfter(createdAt) ? deadline : createdAt;
        return candidateSlots(lawyer, searchStart, deadline.plus(Duration.ofDays(POST_SLA_SEARCH_DAYS)), durationMinutes).stream()
                .filter(slot -> !slot.start().isBefore(deadline))
                .toList();
    }

    private List<Slot> candidateSlots(LawyerProfile lawyer, Instant searchStart, Instant searchEnd, int durationMinutes) {
        LocalDate date = searchStart.atZone(BUSINESS_ZONE).toLocalDate();
        LocalDate endDate = searchEnd.atZone(BUSINESS_ZONE).toLocalDate();
        List<Slot> slots = new ArrayList<>();
        while (!date.isAfter(endDate)) {
            final int weekday = date.getDayOfWeek().getValue();
            LocalDate slotDate = date;
            lawyer.availabilityWindows().stream()
                    .filter(window -> window.weekday() != null && window.weekday() == weekday)
                    .sorted(Comparator.comparing(window -> LocalTime.parse(window.startTime())))
                    .forEach(window -> {
                        ZoneId zone = zoneFor(window);
                        ZonedDateTime start = ZonedDateTime.of(LocalDateTime.of(slotDate, LocalTime.parse(window.startTime())), zone);
                        ZonedDateTime windowEnd = ZonedDateTime.of(LocalDateTime.of(slotDate, LocalTime.parse(window.endTime())), zone);
                        if (start.toInstant().isBefore(searchStart)) {
                            start = searchStart.atZone(zone).withSecond(0).withNano(0);
                            int minute = start.getMinute();
                            if (minute % 15 != 0) {
                                start = start.plusMinutes(15 - (minute % 15));
                            }
                        }
                        while (!start.plusMinutes(durationMinutes).isAfter(windowEnd)) {
                            Instant slotStart = start.toInstant();
                            Instant slotEnd = start.plusMinutes(durationMinutes).toInstant();
                            if (!slotStart.isBefore(searchStart) && !slotEnd.isAfter(searchEnd)) {
                                slots.add(new Slot(slotStart, slotEnd));
                            }
                            start = start.plusMinutes(15);
                        }
                    });
            date = date.plusDays(1);
        }
        return slots.stream().sorted(Comparator.comparing(Slot::start)).toList();
    }

    private EventResponse eventResponse(TenantRoutingRule route, UrgencyDefinition urgency, Instant deadline, int priorityScore,
                                        Instant start, Instant end, String status, String source, LawyerProfile lawyer) {
        return new EventResponse(
                UUID.randomUUID().toString(), lawyer == null ? null : lawyer.id(), lawyer == null ? null : lawyer.displayName(),
                lawyer == null ? null : lawyer.email(), route.name(), urgency.name(), urgency.slaDays(), deadline,
                priorityScore, start, end, lawyer == null ? null : lawyer.meetingUrl(),
                end == null ? null : !end.isAfter(deadline), status, source
        );
    }

    private List<NotificationOutboxItem> notificationsForScheduling(String tenantId, ConsultationResponse consultation, SchedulingResult scheduled) {
        List<NotificationOutboxItem> notifications = new ArrayList<>(notificationsFor(consultation, scheduled.event(), "CONSULTATION_SCHEDULED"));
        notifications.addAll(rescheduleNotificationsFor(tenantId, scheduled.movedEvents()));
        return List.copyOf(notifications);
    }

    private List<NotificationOutboxItem> rescheduleNotificationsFor(String tenantId, List<EventResponse> movedEvents) {
        List<NotificationOutboxItem> notifications = new ArrayList<>();
        for (EventResponse movedEvent : movedEvents) {
            intakeRepository.consultationForEventId(tenantId, movedEvent.id())
                    .ifPresent(consultation -> notifications.addAll(
                            notificationsFor(
                                    new ConsultationResponse(
                                            consultation.id(), consultation.tenantId(), consultation.clientName(),
                                            consultation.clientEmail(), consultation.summary(), consultation.preferredWindow(),
                                            consultation.status(), consultation.urgency(), consultation.consultationType(),
                                            consultation.assignedLawyerEmail(), consultation.classification(),
                                            consultation.notifications(), consultation.sourceEventId(),
                                            consultation.sourceMessageId(), consultation.createdAt(), consultation.eventId(),
                                            movedEvent
                                    ),
                                    movedEvent,
                                    "CONSULTATION_RESCHEDULED"
                            )
                    ));
        }
        return List.copyOf(notifications);
    }

    private List<NotificationOutboxItem> notificationsFor(ConsultationResponse consultation, EventResponse event, String type) {
        if (event == null || event.scheduledStart() == null || event.scheduledEnd() == null) {
            return List.of();
        }
        List<NotificationOutboxItem> notifications = new ArrayList<>();
        String ics = calendarInviteFor(consultation, event);
        if (event.lawyerEmail() != null && !event.lawyerEmail().isBlank()) {
            notifications.add(new NotificationOutboxItem(
                    consultation.id(), event.id(), type, "LAWYER", event.lawyerEmail(),
                    subjectFor(type, consultation, event),
                    lawyerEmailBody(consultation, event),
                    ics
            ));
        }
        if (consultation.clientEmail() != null && !consultation.clientEmail().isBlank()) {
            notifications.add(new NotificationOutboxItem(
                    consultation.id(), event.id(), type, "CLIENT", consultation.clientEmail(),
                    subjectFor(type, consultation, event),
                    clientEmailBody(consultation, event),
                    ics
            ));
        }
        return notifications;
    }

    private String subjectFor(String type, ConsultationResponse consultation, EventResponse event) {
        String prefix = "CONSULTATION_RESCHEDULED".equals(type) ? "Consulta reagendada" : "Consulta agendada";
        return prefix + ": " + consultation.clientName() + " - " + event.routeName();
    }

    private String lawyerEmailBody(ConsultationResponse consultation, EventResponse event) {
        return """
                LegalGate agendo una consulta.

                Cliente: %s <%s>
                Ruta: %s
                Urgencia: %s
                SLA: %s
                Hora: %s - %s
                En SLA: %s
                %s
                Resumen:
                %s
                """.formatted(
                consultation.clientName(),
                consultation.clientEmail(),
                event.routeName(),
                event.urgencyName(),
                event.slaDeadline(),
                event.scheduledStart(),
                event.scheduledEnd(),
                Boolean.TRUE.equals(event.scheduledWithinSla()) ? "si" : "no",
                event.meetingUrl() == null ? "" : "Meeting: " + event.meetingUrl(),
                consultation.summary()
        ).trim();
    }

    private String clientEmailBody(ConsultationResponse consultation, EventResponse event) {
        return """
                Tu consulta LegalGate fue agendada.

                Hora: %s - %s
                Abogado: %s <%s>
                %s
                Resumen:
                %s
                """.formatted(
                event.scheduledStart(),
                event.scheduledEnd(),
                firstNonBlank(event.lawyerDisplayName(), "Abogado LegalGate"),
                event.lawyerEmail(),
                event.meetingUrl() == null ? "" : "Meeting: " + event.meetingUrl(),
                consultation.summary()
        ).trim();
    }

    private String calendarInviteFor(ConsultationResponse consultation, EventResponse event) {
        String description = "Resumen: " + consultation.summary()
                + "\nCliente: " + consultation.clientName() + " <" + consultation.clientEmail() + ">"
                + "\nAbogado: " + firstNonBlank(event.lawyerDisplayName(), event.lawyerEmail())
                + (event.meetingUrl() == null ? "" : "\nMeeting: " + event.meetingUrl());
        return String.join("\r\n",
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//LegalGate//Consultation Scheduling//EN",
                "METHOD:REQUEST",
                "BEGIN:VEVENT",
                "UID:legalgate-" + event.id() + "@legal-gate.co",
                "DTSTAMP:" + ICS_TIME.format(Instant.now()),
                "DTSTART:" + ICS_TIME.format(event.scheduledStart()),
                "DTEND:" + ICS_TIME.format(event.scheduledEnd()),
                "ORGANIZER;CN=" + icsEscape(intakeProperties.notificationsFromName()) + ":mailto:" + intakeProperties.notificationsFromEmail(),
                "ATTENDEE;CN=" + icsEscape(firstNonBlank(event.lawyerDisplayName(), event.lawyerEmail())) + ";ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:" + event.lawyerEmail(),
                "ATTENDEE;CN=" + icsEscape(consultation.clientName()) + ";ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:" + consultation.clientEmail(),
                "SUMMARY:" + icsEscape("Consulta LegalGate - " + consultation.clientName()),
                "DESCRIPTION:" + icsEscape(description),
                event.meetingUrl() == null ? "LOCATION:LegalGate" : "LOCATION:" + icsEscape(event.meetingUrl()),
                "STATUS:TENTATIVE",
                "END:VEVENT",
                "END:VCALENDAR",
                ""
        );
    }

    private String icsEscape(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }

    private int priorityScore(UrgencyDefinition urgency, Instant deadline, Instant createdAt) {
        long hoursToDeadline = Math.max(0, Duration.between(createdAt, deadline).toHours());
        int pressure = Math.max(0, 240 - (int) Math.min(240, hoursToDeadline));
        return urgency.rank() * 10_000 + pressure;
    }

    private Instant addBusinessDays(Instant start, int days) {
        ZonedDateTime dateTime = start.atZone(BUSINESS_ZONE);
        int remaining = Math.max(0, days);
        while (remaining > 0) {
            dateTime = dateTime.plusDays(1);
            DayOfWeek day = dateTime.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return dateTime.toInstant();
    }

    private ConsultationClassifierRequest classifierRequestFor(InboundEmailReceived event, List<TenantRoutingRule> routingRules) {
        List<ConsultationClassifierRequest.Route> routes = new ArrayList<>();
        for (int index = 0; index < routingRules.size(); index++) {
            TenantRoutingRule rule = routingRules.get(index);
            routes.add(new ConsultationClassifierRequest.Route(
                    index, rule.name(), rule.description(), destinationEmailFor(null, rule),
                    rule.urgentKeywords(), rule.consultationWindows(), urgencyLevelsFor(rule)
            ));
        }
        return new ConsultationClassifierRequest(
                new ConsultationClassifierRequest.InboundEmail(
                        event.subject(), event.plain(), event.html(), firstNonBlank(event.headerFrom(), event.envelopeFrom(), clientEmailFor(event)),
                        event.recipients() == null ? List.of() : event.recipients(), event.messageId()
                ),
                routes, intakeProperties.consultationClassifierSystemPrompt(), intakeProperties.consultationClassifierPromptVersion()
        );
    }

    private boolean isValidClassifierResponse(ConsultationClassifierResponse response, List<TenantRoutingRule> routingRules) {
        if (response == null || response.routeIndex() == null || response.urgency() == null) {
            return false;
        }
        if (response.routeIndex() < 0 || response.routeIndex() >= routingRules.size()) {
            return false;
        }
        String normalizedUrgency = normalize(response.urgency().trim());
        return urgencyLevelsFor(routingRules.get(response.routeIndex())).stream()
                .map(this::normalize)
                .anyMatch(level -> level.equals(normalizedUrgency));
    }

    private TenantSettingsResponse settingsFor(String tenantId) {
        TenantSettingsResponse defaults = new TenantSettingsResponse(
                tenantId, DEFAULT_SETTINGS.urgentKeywords(), DEFAULT_SETTINGS.consultationWindows(), DEFAULT_SETTINGS.urgencyLevels(),
                DEFAULT_SETTINGS.destinationEmail(), intakeProperties.canonicalIntakeEmail(tenantId), DEFAULT_SETTINGS.routingRules(), DEFAULT_SETTINGS.lawyers()
        );
        TenantSettingsResponse settings = normalizeSettings(intakeRepository.settingsFor(tenantId, defaults), tenantId);
        if (settings.intakeEmail() == null || settings.intakeEmail().isBlank() || settings.lawyers().isEmpty()) {
            TenantSettingsResponse healedSettings = new TenantSettingsResponse(
                    settings.tenantId(), settings.urgentKeywords(), settings.consultationWindows(), settings.urgencyLevels(),
                    settings.destinationEmail(), intakeProperties.canonicalIntakeEmail(tenantId), settings.routingRules(), settings.lawyers()
            );
            return intakeRepository.saveSettings(tenantId, healedSettings);
        }
        return settings;
    }

    private TenantSettingsResponse normalizeSettings(TenantSettingsResponse settings, String tenantId) {
        List<LawyerProfile> lawyers = lawyersFor(settings, tenantId);
        List<TenantRoutingRule> routingRules = routingRulesFor(settings, lawyers);
        TenantRoutingRule primaryRule = primaryRoutingRule(routingRules);
        return new TenantSettingsResponse(
                settings.tenantId() == null ? tenantId : settings.tenantId(), primaryRule.urgentKeywords(), primaryRule.consultationWindows(),
                derivedUrgencyLevels(routingRules), primaryRule.destinationEmail(), settings.intakeEmail(), routingRules, lawyers
        );
    }
    private List<LawyerProfile> lawyersFrom(String tenantId, List<LawyerProfile> submitted, List<TenantRoutingRule> routingRules) {
        List<LawyerProfile> lawyers = new ArrayList<>();
        if (submitted != null) {
            for (int index = 0; index < submitted.size(); index++) {
                lawyers.add(sanitizeLawyer(tenantId, submitted.get(index), index));
            }
        }
        if (lawyers.isEmpty() && routingRules != null) {
            LinkedHashSet<String> emails = new LinkedHashSet<>();
            for (TenantRoutingRule rule : routingRules) {
                String email = sanitizeEmail(rule.destinationEmail());
                if (email != null) {
                    emails.add(email);
                }
            }
            int index = 0;
            for (String email : emails) {
                lawyers.add(new LawyerProfile(deterministicUuid("lawyer:" + tenantId + ":" + email), displayNameFromEmail(email), email, true, 60, defaultAvailability()));
                index++;
            }
        }
        if (lawyers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lawyers_required");
        }
        return List.copyOf(lawyers);
    }

    private LawyerProfile sanitizeLawyer(String tenantId, LawyerProfile lawyer, int index) {
        if (lawyer == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lawyer_required");
        }
        String email = sanitizeRequiredEmail(lawyer.email(), "lawyer_email_required");
        String id = lawyer.id() == null || lawyer.id().isBlank() ? deterministicUuid("lawyer:" + tenantId + ":" + email) : lawyer.id().trim();
        assertUuid(id, "invalid_lawyer_id");
        int duration = lawyer.defaultEventDurationMinutes() == null ? 60 : lawyer.defaultEventDurationMinutes();
        if (duration < 15 || duration > 480) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_lawyer_duration");
        }
        return new LawyerProfile(
                id,
                lawyer.displayName() == null || lawyer.displayName().isBlank() ? "Lawyer " + (index + 1) : lawyer.displayName().trim(),
                email,
                sanitizeMeetingUrl(lawyer.meetingUrl()),
                lawyer.active() == null || lawyer.active(),
                duration,
                sanitizeAvailability(lawyer.availabilityWindows())
        );
    }

    private List<LawyerAvailabilityWindow> sanitizeAvailability(List<LawyerAvailabilityWindow> windows) {
        if (windows == null || windows.isEmpty()) {
            return defaultAvailability();
        }
        List<LawyerAvailabilityWindow> sanitized = new ArrayList<>();
        for (LawyerAvailabilityWindow window : windows) {
            if (window == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_availability_window");
            }
            if (window.weekday() == null || window.weekday() < 1 || window.weekday() > 7) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_availability_weekday");
            }
            LocalTime start = parseTime(window.startTime(), "invalid_availability_time");
            LocalTime end = parseTime(window.endTime(), "invalid_availability_time");
            if (!start.isBefore(end)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_availability_time_range");
            }
            sanitized.add(new LawyerAvailabilityWindow(window.weekday(), start.toString().substring(0, 5), end.toString().substring(0, 5), timezoneFor(window.timezone())));
        }
        return List.copyOf(sanitized);
    }

    private List<TenantRoutingRule> routingRulesFrom(TenantSettingsRequest request, List<LawyerProfile> lawyers) {
        if (request.routingRules() == null || request.routingRules().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "routing_rules_required");
        }
        Set<String> lawyerIds = lawyers.stream().map(LawyerProfile::id).collect(java.util.stream.Collectors.toSet());
        java.util.Map<String, String> lawyerIdByEmail = lawyers.stream().collect(java.util.stream.Collectors.toMap(lawyer -> lawyer.email().toLowerCase(Locale.ROOT), LawyerProfile::id, (left, right) -> left));
        List<TenantRoutingRule> rules = new ArrayList<>();
        for (int index = 0; index < request.routingRules().size(); index++) {
            TenantRoutingRule rule = request.routingRules().get(index);
            String defaultLawyerId = lawyerIdByEmail.getOrDefault(sanitizeEmail(rule.destinationEmail()), lawyers.get(0).id());
            rules.add(sanitizeRoutingRule(rule, index, defaultLawyerId, lawyerIds));
        }
        return List.copyOf(rules);
    }

    private TenantRoutingRule sanitizeRoutingRule(TenantRoutingRule rule, int index, String defaultLawyerId, Set<String> lawyerIds) {
        if (rule == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "routing_rule_required");
        }
        String lawyerId = rule.lawyerId() == null || rule.lawyerId().isBlank() ? defaultLawyerId : rule.lawyerId().trim();
        if (!lawyerIds.contains(lawyerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "routing_rule_lawyer_required");
        }
        List<UrgencyDefinition> definitions = sanitizeUrgencyDefinitions(rule.urgencyDefinitions(), rule.urgencyLevels());
        String name = rule.name() == null || rule.name().isBlank() ? "Route " + (index + 1) : rule.name().trim();
        return new TenantRoutingRule(
                name, sanitizeNullable(rule.description()), sanitize(rule.urgentKeywords()), sanitize(rule.consultationWindows()),
                activeUrgencyNames(definitions), lawyerId, definitions,
                sanitizeRequiredEmail(rule.destinationEmail(), "routing_rule_destination_email_required")
        );
    }

    private List<UrgencyDefinition> sanitizeUrgencyDefinitions(List<UrgencyDefinition> definitions, List<String> fallbackLevels) {
        List<UrgencyDefinition> source = definitions == null || definitions.isEmpty() ? fallbackDefinitions(fallbackLevels) : definitions;
        Set<String> seen = new LinkedHashSet<>();
        List<UrgencyDefinition> sanitized = new ArrayList<>();
        for (UrgencyDefinition definition : source) {
            if (definition == null || definition.name() == null || definition.name().trim().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_urgency_definition_name");
            }
            String name = definition.name().trim();
            if (!seen.add(normalize(name))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duplicate_urgency_definition_name");
            }
            if (definition.rank() == null || definition.rank() < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_urgency_definition_rank");
            }
            if (definition.slaDays() == null || definition.slaDays() < 0 || definition.slaDays() > 365) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_urgency_definition_sla_days");
            }
            sanitized.add(new UrgencyDefinition(name, definition.rank(), definition.slaDays(), definition.active() == null || definition.active()));
        }
        if (activeUrgencyNames(sanitized).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "active_urgency_definitions_required");
        }
        return List.copyOf(sanitized.stream().sorted(Comparator.comparing(UrgencyDefinition::rank)).toList());
    }

    private List<UrgencyDefinition> fallbackDefinitions(List<String> levels) {
        List<String> names = levels == null || levels.isEmpty() ? DEFAULT_URGENCY_LEVELS : sanitizeUrgencyLevels(levels);
        List<UrgencyDefinition> definitions = new ArrayList<>();
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            definitions.add(new UrgencyDefinition(name, index + 1, "URGENT".equalsIgnoreCase(name) ? 1 : 5, true));
        }
        return definitions;
    }

    private List<LawyerProfile> lawyersFor(TenantSettingsResponse settings, String tenantId) {
        if (settings.lawyers() != null && !settings.lawyers().isEmpty()) {
            return settings.lawyers().stream().map(lawyer -> sanitizeLawyer(tenantId, lawyer, 0)).toList();
        }
        String email = firstNonBlank(settings.destinationEmail(), "lawyer@" + tenantId + ".legalgate.invalid");
        return List.of(new LawyerProfile(deterministicUuid("lawyer:" + tenantId + ":" + email), displayNameFromEmail(email), sanitizeEmail(email), true, 60, defaultAvailability()));
    }

    private LawyerProfile lawyerWithDefaultAvailability(LawyerProfile lawyer) {
        if (lawyer.availabilityWindows() != null && !lawyer.availabilityWindows().isEmpty()) {
            return lawyer;
        }
        return new LawyerProfile(
                lawyer.id(),
                lawyer.displayName(),
                lawyer.email(),
                lawyer.meetingUrl(),
                lawyer.active(),
                lawyer.defaultEventDurationMinutes(),
                defaultAvailability()
        );
    }

    private List<TenantRoutingRule> routingRulesFor(TenantSettingsResponse settings) {
        return routingRulesFor(settings, settings.lawyers() == null ? List.of() : settings.lawyers());
    }

    private List<TenantRoutingRule> routingRulesFor(TenantSettingsResponse settings, List<LawyerProfile> lawyers) {
        String defaultLawyerId = lawyers.isEmpty() ? null : lawyers.get(0).id();
        if (settings.routingRules() != null && !settings.routingRules().isEmpty()) {
            List<TenantRoutingRule> rules = new ArrayList<>();
            for (int index = 0; index < settings.routingRules().size(); index++) {
                TenantRoutingRule rule = settings.routingRules().get(index);
                String lawyerId = firstNonBlank(rule.lawyerId(), lawyerIdByDestinationEmail(lawyers, rule.destinationEmail()), defaultLawyerId);
                List<UrgencyDefinition> definitions = sanitizeUrgencyDefinitions(rule.urgencyDefinitions(), rule.urgencyLevels());
                rules.add(new TenantRoutingRule(
                        rule.name() == null || rule.name().isBlank() ? "Route " + (index + 1) : rule.name().trim(),
                        sanitizeNullable(rule.description()), sanitize(rule.urgentKeywords()), sanitize(rule.consultationWindows()),
                        activeUrgencyNames(definitions), lawyerId, definitions,
                        sanitizeEmail(firstNonBlank(rule.destinationEmail(), lawyerEmailFor(lawyers, lawyerId), settings.destinationEmail()))
                ));
            }
            return List.copyOf(rules);
        }
        List<UrgencyDefinition> definitions = fallbackDefinitions(settings.urgencyLevels());
        return List.of(new TenantRoutingRule(
                "Default intake route", null, sanitize(settings.urgentKeywords()), sanitize(settings.consultationWindows()),
                activeUrgencyNames(definitions), defaultLawyerId, definitions,
                sanitizeEmail(firstNonBlank(settings.destinationEmail(), lawyerEmailFor(lawyers, defaultLawyerId)))
        ));
    }

    private String lawyerIdByDestinationEmail(List<LawyerProfile> lawyers, String destinationEmail) {
        String email = sanitizeEmail(destinationEmail);
        if (email == null || lawyers == null) {
            return null;
        }
        return lawyers.stream().filter(lawyer -> email.equalsIgnoreCase(lawyer.email())).map(LawyerProfile::id).findFirst().orElse(null);
    }

    private TenantRoutingRule routingRuleForSummary(String summary, List<TenantRoutingRule> routingRules) {
        return routingRules.stream().filter(rule -> !matchUrgentKeywords(summary, rule.urgentKeywords()).isEmpty()).findFirst().orElse(primaryRoutingRule(routingRules));
    }

    private TenantRoutingRule primaryRoutingRule(List<TenantRoutingRule> routingRules) {
        return routingRules == null || routingRules.isEmpty() ? DEFAULT_ROUTING_RULE : routingRules.get(0);
    }

    private Optional<LawyerProfile> lawyerFor(TenantSettingsResponse settings, TenantRoutingRule rule) {
        if (settings.lawyers() == null) {
            return Optional.empty();
        }
        Optional<LawyerProfile> routeLawyer = settings.lawyers().stream()
                .filter(lawyer -> lawyer.id().equals(rule.lawyerId()))
                .filter(lawyer -> lawyer.active() == null || lawyer.active())
                .findFirst();
        if (routeLawyer.isPresent()) {
            return routeLawyer;
        }
        return settings.lawyers().stream()
                .filter(lawyer -> lawyer.active() == null || lawyer.active())
                .findFirst();
    }

    private UrgencyDefinition urgencyDefinitionFor(TenantRoutingRule route, String urgencyName) {
        String normalized = normalize(urgencyName);
        return route.urgencyDefinitions().stream()
                .filter(definition -> definition.active() == null || definition.active())
                .filter(definition -> normalize(definition.name()).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "route_urgency_definition_not_found"));
    }

    private List<String> urgencyLevelsFor(TenantRoutingRule rule) {
        return activeUrgencyNames(rule.urgencyDefinitions() == null || rule.urgencyDefinitions().isEmpty() ? DEFAULT_URGENCY_DEFINITIONS : rule.urgencyDefinitions());
    }

    private List<String> activeUrgencyNames(List<UrgencyDefinition> definitions) {
        return definitions.stream().filter(definition -> definition.active() == null || definition.active()).sorted(Comparator.comparing(UrgencyDefinition::rank)).map(UrgencyDefinition::name).toList();
    }

    private List<String> derivedUrgencyLevels(List<TenantRoutingRule> routingRules) {
        LinkedHashSet<String> levels = new LinkedHashSet<>();
        for (TenantRoutingRule rule : routingRules) {
            levels.addAll(urgencyLevelsFor(rule));
        }
        return levels.isEmpty() ? DEFAULT_URGENCY_LEVELS : List.copyOf(levels);
    }

    private String destinationEmailFor(TenantSettingsResponse settings, TenantRoutingRule rule) {
        return firstNonBlank(rule.destinationEmail(), settings == null ? null : settings.destinationEmail());
    }

    private String lawyerEmailFor(List<LawyerProfile> lawyers, String lawyerId) {
        if (lawyers == null || lawyerId == null) {
            return null;
        }
        return lawyers.stream().filter(lawyer -> lawyerId.equals(lawyer.id())).map(LawyerProfile::email).findFirst().orElse(null);
    }

    private List<LawyerAvailabilityWindow> defaultAvailability() {
        List<LawyerAvailabilityWindow> windows = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            windows.add(new LawyerAvailabilityWindow(day, "09:00", "17:00", "America/Bogota"));
        }
        return List.copyOf(windows);
    }

    private ZoneId zoneFor(LawyerAvailabilityWindow window) {
        return ZoneId.of(timezoneFor(window.timezone()));
    }

    private String timezoneFor(String value) {
        if (value == null || value.isBlank()) {
            return "America/Bogota";
        }
        try {
            return ZoneId.of(value.trim()).getId();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_timezone");
        }
    }

    private LocalTime parseTime(String value, String reason) {
        try {
            return LocalTime.parse(value);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }
    }

    private void assertUuid(String value, String reason) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }
    }

    private String deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String displayNameFromEmail(String email) {
        String localPart = email == null ? "Lawyer" : email.split("@")[0];
        return localPart.replace('.', ' ').replace('-', ' ');
    }

    private String preferredWindowFor(String requestedWindow, List<String> configuredWindows) {
        return requestedWindow != null && !requestedWindow.isBlank() ? requestedWindow.trim() : firstConfiguredWindow(configuredWindows);
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
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> matchUrgentKeywords(String summary, List<String> urgentKeywords) {
        if (urgentKeywords == null) {
            return List.of();
        }
        String normalizedSummary = normalize(summary);
        return urgentKeywords.stream().filter(keyword -> normalizedSummary.contains(normalize(keyword))).toList();
    }

    private List<String> sanitize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> value != null).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
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
        return firstEmail(event.headerFrom()).or(() -> firstEmail(event.envelopeFrom())).orElse("unknown@example.invalid").toLowerCase(Locale.ROOT);
    }

    private Optional<String> firstEmail(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = EMAIL_PATTERN.matcher(value);
        return matcher.find() ? Optional.of(matcher.group().trim()) : Optional.empty();
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
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String sanitizeMeetingUrl(String value) {
        String sanitized = sanitizeNullable(value);
        if (sanitized == null) {
            return null;
        }
        if (!sanitized.startsWith("https://") && !sanitized.startsWith("http://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_meeting_url");
        }
        return sanitized;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private record Slot(Instant start, Instant end) { }
    private record SlotSearchResult(Instant start, Instant end, List<EventResponse> movedEvents) { }
    private record SchedulingResult(EventResponse event, List<EventResponse> movedEvents) { }
}


