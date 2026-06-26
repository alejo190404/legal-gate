package com.legalgate.intake;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.legalgate.intake.classifier.ClassifierUnavailableException;
import com.legalgate.intake.classifier.ConsultationClassifierClient;
import com.legalgate.intake.classifier.ConsultationClassifierResponse;
import com.legalgate.intake.model.TenantSettingsResponse;
import com.legalgate.intake.repository.IntakeRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "legalgate.intake.persistence=memory"
})
@AutoConfigureMockMvc
class IntakeOrchestratorApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IntakeRepository intakeRepository;

    @MockBean
    private ConsultationClassifierClient consultationClassifierClient;

    @BeforeEach
    void resetClassifier() {
        reset(consultationClassifierClient);
    }

    @Test
    void statusEndpointDescribesReadyIntakeService() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("legal-gate-intake-orchestrator"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.channels.web").value(true))
                .andExpect(jsonPath("$.channels.email").value(true));
    }

    @Test
    void firmOwnerCanRegisterWithEmailPasswordAndFirmNameWithoutCreatingLawyer() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@barragan-legal.test",
                                  "password": "StrongPass2026!",
                                  "firmName": "Barragán Legal"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/tenants/barragan-legal/consultations"))
                .andExpect(jsonPath("$.email").value("owner@barragan-legal.test"))
                .andExpect(jsonPath("$.tenantId").value("barragan-legal"))
                .andExpect(jsonPath("$.displayName").value("Barragán Legal admin"))
                .andExpect(jsonPath("$.role").value("FIRM_ADMIN"))
                .andExpect(jsonPath("$.lawyerId").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());

        mockMvc.perform(get("/api/admin/tenants/barragan-legal/consultations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("barragan-legal"))
                .andExpect(jsonPath("$.consultations", hasSize(0)));

        mockMvc.perform(get("/api/tenants/barragan-legal/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intakeEmail").value("barragan-legal@intake.legal-gate.co"));
    }

    @Test
    void firmOwnerCanRegisterWithEightCharacterPrototypePassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "alejo190404@gmail.com",
                                  "password": "password",
                                  "firmName": "ABA juridico"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/tenants/aba-juridico/consultations"))
                .andExpect(jsonPath("$.email").value("alejo190404@gmail.com"))
                .andExpect(jsonPath("$.tenantId").value("aba-juridico"))
                .andExpect(jsonPath("$.displayName").value("ABA juridico admin"))
                .andExpect(jsonPath("$.role").value("FIRM_ADMIN"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void firmOwnerCanLoginAfterRegistration() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "Owner@login-barragan-legal.test",
                                  "password": "StrongPass2026!",
                                  "firmName": "Login Barragan Legal"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": " owner@login-BARRAGAN-LEGAL.test ",
                                  "password": "StrongPass2026!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("owner@login-barragan-legal.test"))
                .andExpect(jsonPath("$.tenantId").value("login-barragan-legal"))
                .andExpect(jsonPath("$.displayName").value("Login Barragan Legal admin"))
                .andExpect(jsonPath("$.role").value("FIRM_ADMIN"));
    }

    @Test
    void wrongPasswordReturnsInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@firma.test",
                                  "password": "StrongPass2026!",
                                  "firmName": "Firma Test"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@firma.test",
                                  "password": "WrongPass2026!"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andExpect(jsonPath("$.message").value("Email or password is incorrect."));
    }

    @Test
    void invalidRegistrationPayloadReturnsValidationProblem() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "bad-email",
                                  "password": "short",
                                  "firmName": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.password").exists())
                .andExpect(jsonPath("$.fields.firmName").exists());
    }

    @Test
    void settingsPutRejectsLegacyFlatRoutingFields() throws Exception {
        mockMvc.perform(put("/api/tenants/bogota-legal/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "urgentKeywords": ["tutela", "captura", "audiencia"],
                                  "consultationWindows": ["LUN-VIE 08:00-12:00", "LUN-VIE 14:00-17:00"],
                                  "destinationEmail": "consultas@firma.test"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("legacy_flat_routing_settings_not_supported"));
    }

    @Test
    void settingsGetBackfillsMissingIntakeEmail() throws Exception {
        intakeRepository.saveSettings("missing-email", new TenantSettingsResponse(
                "missing-email",
                List.of("captura"),
                List.of(),
                List.of("NORMAL", "URGENT"),
                "notificaciones@firma.test",
                null,
                List.of()
        ));

        mockMvc.perform(get("/api/tenants/missing-email/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intakeEmail").value("missing-email@intake.legal-gate.co"));

        mockMvc.perform(get("/api/tenants/missing-email/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intakeEmail").value("missing-email@intake.legal-gate.co"));
    }

    @Test
    void settingsGetPreservesExistingIntakeEmail() throws Exception {
        intakeRepository.saveSettings("manual-email", new TenantSettingsResponse(
                "manual-email",
                List.of("captura"),
                List.of(),
                List.of("NORMAL", "URGENT"),
                "notificaciones@firma.test",
                "manual-email@intake.legal-gate.co",
                List.of()
        ));

        mockMvc.perform(get("/api/tenants/manual-email/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intakeEmail").value("manual-email@intake.legal-gate.co"));
    }

    @Test
    void firmCanConfigureMultipleContentRoutesAndRouteMatchingConsultation() throws Exception {
        mockMvc.perform(put("/api/tenants/laboral-legal/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "routingRules": [
                                    {
                                      "name": "Workspace incidents",
                                      "description": "Incidentes de convivencia laboral",
                                      "urgentKeywords": ["harassment", "workspace incident"],
                                      "consultationWindows": ["LUN 08:00-10:00"],
                                      "urgencyLevels": ["NORMAL", "URGENT"],
                                      "destinationEmail": "personal@firma.test"
                                    },
                                    {
                                      "name": "Labor penalties",
                                      "description": "Sanciones y penalidades laborales",
                                      "urgentKeywords": ["penalties"],
                                      "consultationWindows": ["MAR 09:00-12:00", "JUE 09:00-12:00"],
                                      "urgencyLevels": ["URGENT", "CRITICAL"],
                                      "destinationEmail": "laboral@firma.test"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("laboral-legal"))
                .andExpect(jsonPath("$.urgencyLevels[0]").value("NORMAL"))
                .andExpect(jsonPath("$.urgencyLevels[1]").value("URGENT"))
                .andExpect(jsonPath("$.urgencyLevels[2]").value("CRITICAL"))
                .andExpect(jsonPath("$.routingRules", hasSize(2)))
                .andExpect(jsonPath("$.routingRules[0].description").value("Incidentes de convivencia laboral"))
                .andExpect(jsonPath("$.routingRules[0].destinationEmail").value("personal@firma.test"))
                .andExpect(jsonPath("$.routingRules[1].urgencyLevels[1]").value("CRITICAL"))
                .andExpect(jsonPath("$.routingRules[1].destinationEmail").value("laboral@firma.test"));

        mockMvc.perform(post("/api/tenants/laboral-legal/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientName": "Ana Diaz",
                                  "clientEmail": "ana@example.com",
                                  "summary": "Necesito apoyo urgente por penalties laborales."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.urgency").value("CRITICAL"))
                .andExpect(jsonPath("$.classification.matchedUrgentKeywords[0]").value("penalties"))
                .andExpect(jsonPath("$.notifications.destinationEmail").value("laboral@firma.test"))
                .andExpect(jsonPath("$.notifications.preferredWindow").value("MAR 09:00-12:00"));
    }

    @Test
    void inboundEmailCreatesConsultationThroughMockedClassifier() throws Exception {
        mockMvc.perform(put("/api/tenants/inbound-legal/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "routingRules": [
                                    {
                                      "name": "Civil",
                                      "description": "Contratos y asuntos civiles",
                                      "urgentKeywords": ["contrato"],
                                      "consultationWindows": ["LUN 08:00-10:00"],
                                      "urgencyLevels": ["BAJA", "MEDIA"],
                                      "destinationEmail": "civil@firma.test"
                                    },
                                    {
                                      "name": "Laboral",
                                      "description": "Despidos y relaciones laborales",
                                      "urgentKeywords": ["despido"],
                                      "consultationWindows": ["MAR 09:00-12:00", "JUE 09:00-12:00"],
                                      "urgencyLevels": ["MEDIA", "ALTA"],
                                      "destinationEmail": "laboral@firma.test"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());
        when(consultationClassifierClient.classify(any())).thenReturn(new ConsultationClassifierResponse(
                1,
                "ignored freeform type",
                "ALTA",
                "Terminacion laboral",
                "Cliente solicita asesoria por despido.",
                "Ana Diaz",
                "El texto menciona despido, que corresponde a la ruta laboral.",
                0.94
        ));

        mockMvc.perform(post("/api/internal/inbound-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-1",
                                  "tenantId": "inbound-legal",
                                  "envelopeFrom": "ana@example.com",
                                  "headerFrom": "Ana Diaz <ana@example.com>",
                                  "recipients": ["inbound-legal@intake.legal-gate.co"],
                                  "subject": "Consulta por despido",
                                  "messageId": "<msg-1@example.com>",
                                  "plain": "Fui despedida y necesito asesoria."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("created"))
                .andExpect(jsonPath("$.consultationId").exists());

        mockMvc.perform(get("/api/admin/tenants/inbound-legal/consultations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations", hasSize(1)))
                .andExpect(jsonPath("$.consultations[0].clientEmail").value("ana@example.com"))
                .andExpect(jsonPath("$.consultations[0].clientName").value("Ana Diaz"))
                .andExpect(jsonPath("$.consultations[0].urgency").value("ALTA"))
                .andExpect(jsonPath("$.consultations[0].consultationType").value("Laboral"))
                .andExpect(jsonPath("$.consultations[0].assignedLawyerEmail").value("laboral@firma.test"))
                .andExpect(jsonPath("$.consultations[0].preferredWindow").value("MAR 09:00-12:00"))
                .andExpect(jsonPath("$.consultations[0].classification.label").value("LLM_CLASSIFIED"))
                .andExpect(jsonPath("$.consultations[0].classification.concept").value("Terminacion laboral"))
                .andExpect(jsonPath("$.consultations[0].notifications.emailQueued").value(true))
                .andExpect(jsonPath("$.consultations[0].notifications.calendarUpdateQueued").value(true))
                .andExpect(jsonPath("$.consultations[0].event.status").value("TENTATIVE"))
                .andExpect(jsonPath("$.consultations[0].event.scheduledWithinSla").value(true))
                .andExpect(jsonPath("$.consultations[0].sourceEventId").value("evt-1"))
                .andExpect(jsonPath("$.consultations[0].sourceMessageId").value("<msg-1@example.com>"));
    }

    @Test
    void invalidClassifierRouteOrUrgencyFallsBackToManualReview() throws Exception {
        mockMvc.perform(put("/api/tenants/invalid-classifier/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "routingRules": [
                                    {
                                      "name": "Primary",
                                      "description": "Primary inbox",
                                      "urgentKeywords": [],
                                      "consultationWindows": ["LUN 08:00-10:00"],
                                      "urgencyLevels": ["NORMAL", "URGENT"],
                                      "destinationEmail": "primary@firma.test"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());
        when(consultationClassifierClient.classify(any())).thenReturn(new ConsultationClassifierResponse(
                7,
                "Primary",
                "CRITICAL",
                "General",
                "Summary",
                "Client",
                "Invalid values",
                0.7
        ));

        mockMvc.perform(post("/api/internal/inbound-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-invalid",
                                  "tenantId": "invalid-classifier",
                                  "envelopeFrom": "client@example.com",
                                  "subject": "Consulta",
                                  "messageId": "<invalid-response@example.com>",
                                  "plain": "Necesito asesoria."
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/tenants/invalid-classifier/consultations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations[0].urgency").value("NORMAL"))
                .andExpect(jsonPath("$.consultations[0].consultationType").value("Primary"))
                .andExpect(jsonPath("$.consultations[0].assignedLawyerEmail").value("primary@firma.test"))
                .andExpect(jsonPath("$.consultations[0].classification.label").value("LLM_INVALID_RESPONSE"))
                .andExpect(jsonPath("$.consultations[0].notifications.emailQueued").value(true))
                .andExpect(jsonPath("$.consultations[0].notifications.calendarUpdateQueued").value(true))
                .andExpect(jsonPath("$.consultations[0].event.status").value("TENTATIVE"));
    }

    @Test
    void classifierTimeoutCreatesFallbackWithQueuedNotifications() throws Exception {
        when(consultationClassifierClient.classify(any()))
                .thenThrow(new ClassifierUnavailableException("timeout"));

        mockMvc.perform(post("/api/internal/inbound-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-timeout",
                                  "tenantId": "timeout-legal",
                                  "envelopeFrom": "cliente@example.com",
                                  "headerFrom": "Cliente <cliente@example.com>",
                                  "subject": "Consulta",
                                  "messageId": "<timeout@example.com>",
                                  "plain": "Necesito asesoria urgente."
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/tenants/timeout-legal/consultations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations", hasSize(1)))
                .andExpect(jsonPath("$.consultations[0].classification.label").value("LLM_FAILED"))
                .andExpect(jsonPath("$.consultations[0].notifications.emailQueued").value(true))
                .andExpect(jsonPath("$.consultations[0].notifications.calendarUpdateQueued").value(true))
                .andExpect(jsonPath("$.consultations[0].event.status").value("TENTATIVE"));
    }

    @Test
    void duplicateSourceMessageIdDoesNotCreateDuplicateConsultations() throws Exception {
        when(consultationClassifierClient.classify(any())).thenReturn(new ConsultationClassifierResponse(
                0,
                "Default intake route",
                "NORMAL",
                "General",
                "Summary",
                "Client",
                "Route 0",
                0.8
        ));
        String payload = """
                {
                  "eventId": "evt-duplicate",
                  "tenantId": "duplicate-legal",
                  "envelopeFrom": "client@example.com",
                  "subject": "Consulta",
                  "messageId": "<duplicate@example.com>",
                  "plain": "Necesito asesoria."
                }
                """;

        mockMvc.perform(post("/api/internal/inbound-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/internal/inbound-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/tenants/duplicate-legal/consultations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations", hasSize(1)));
    }

    @Test
    void settingsValidationAcceptsPerRuleUrgencyEnumsAndRejectsBlankOrDuplicateLists() throws Exception {
        mockMvc.perform(put("/api/tenants/custom-urgency/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "routingRules": [
                                    {
                                      "name": "General",
                                      "description": "Consultas generales",
                                      "urgentKeywords": [],
                                      "consultationWindows": [],
                                      "urgencyLevels": ["BAJA", "MEDIA", "ALTA"],
                                      "destinationEmail": "general@firma.test"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urgencyLevels[0]").value("BAJA"))
                .andExpect(jsonPath("$.urgencyLevels[2]").value("ALTA"))
                .andExpect(jsonPath("$.routingRules[0].urgencyLevels[1]").value("MEDIA"));

        mockMvc.perform(put("/api/tenants/custom-urgency/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "routingRules": [
                                    {
                                      "name": "General",
                                      "urgentKeywords": [],
                                      "consultationWindows": [],
                                      "urgencyLevels": ["BAJA", "BAJA"],
                                      "destinationEmail": "general@firma.test"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_urgency_levels"));

        mockMvc.perform(put("/api/tenants/custom-urgency/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "urgencyLevels": ["BAJA", "ALTA"],
                                  "routingRules": [
                                    {
                                      "name": "General",
                                      "urgentKeywords": [],
                                      "consultationWindows": [],
                                      "urgencyLevels": ["BAJA", "ALTA"],
                                      "destinationEmail": "general@firma.test"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("tenant_wide_urgency_levels_not_supported"));
    }

    @Test
    void settingsPutRejectsSystemManagedIntakeEmail() throws Exception {
        mockMvc.perform(put("/api/tenants/firma-uno/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "intakeEmail": null,
                                  "routingRules": [
                                    {
                                      "name": "Default intake route",
                                      "urgentKeywords": ["audiencia"],
                                      "consultationWindows": [],
                                      "destinationEmail": "notificaciones@firma-uno.test"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("system_managed_intake_email"));
    }

    @Test
    void duplicateTenantSlugRegistrationFailsWithoutAutoSuffixing() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "uno@firma.test",
                                  "password": "StrongPass2026!",
                                  "firmName": "Firma Uno"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dos@firma.test",
                                  "password": "StrongPass2026!",
                                  "firmName": "Firma Uno"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void consultantCanSubmitPlainLanguageCaseAndReceiveUrgencyAndQueuedSideEffects() throws Exception {
        mockMvc.perform(put("/api/tenants/familia-legal/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "routingRules": [
                                    {
                                      "name": "Familia",
                                      "description": "Consultas de familia",
                                      "urgentKeywords": ["audiencia", "captura"],
                                      "consultationWindows": ["LUN-VIE 09:00-13:00"],
                                      "urgencyLevels": ["NORMAL", "URGENT"],
                                      "destinationEmail": "intake@familia.test"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tenants/familia-legal/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientName": "María Pérez",
                                  "clientEmail": "maria@example.com",
                                  "summary": "No sé si esto es un caso legal, pero tengo una audiencia mañana y necesito orientación.",
                                  "preferredWindow": "LUN-VIE 09:00-13:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.tenantId").value("familia-legal"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.urgency").value("URGENT"))
                .andExpect(jsonPath("$.classification.label").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.notifications.emailQueued").value(true))
                .andExpect(jsonPath("$.notifications.calendarUpdateQueued").value(true));
    }

    @Test
    void fullSlaSchedulesEarliestPostSlaSlotWithoutManualStatus() throws Exception {
        String lawyerId = "11111111-1111-1111-1111-111111111111";
        mockMvc.perform(put("/api/tenants/post-sla/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lawyers": [
                                    {
                                      "id": "%s",
                                      "displayName": "Agenda Legal",
                                      "email": "agenda@firma.test",
                                      "meetingUrl": "https://meet.example.com/agenda",
                                      "active": true,
                                      "defaultEventDurationMinutes": 60,
                                      "availabilityWindows": [
                                        {"weekday": 1, "startTime": "09:00", "endTime": "17:00", "timezone": "America/Bogota"},
                                        {"weekday": 2, "startTime": "09:00", "endTime": "17:00", "timezone": "America/Bogota"},
                                        {"weekday": 3, "startTime": "09:00", "endTime": "17:00", "timezone": "America/Bogota"},
                                        {"weekday": 4, "startTime": "09:00", "endTime": "17:00", "timezone": "America/Bogota"},
                                        {"weekday": 5, "startTime": "09:00", "endTime": "17:00", "timezone": "America/Bogota"}
                                      ]
                                    }
                                  ],
                                  "routingRules": [
                                    {
                                      "name": "Cero SLA",
                                      "description": "Schedules after expired SLA",
                                      "urgentKeywords": ["hoy"],
                                      "consultationWindows": [],
                                      "lawyerId": "%s",
                                      "urgencyDefinitions": [
                                        {"name": "NORMAL", "rank": 1, "slaDays": 0, "active": true}
                                      ],
                                      "destinationEmail": "agenda@firma.test"
                                    }
                                  ]
                                }
                                """.formatted(lawyerId, lawyerId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tenants/post-sla/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientName": "Laura Torres",
                                  "clientEmail": "laura@example.com",
                                  "summary": "Necesito revisar una citacion hoy."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.event.status").value("TENTATIVE"))
                .andExpect(jsonPath("$.event.scheduledStart").exists())
                .andExpect(jsonPath("$.event.scheduledEnd").exists())
                .andExpect(jsonPath("$.event.scheduledWithinSla").value(false))
                .andExpect(jsonPath("$.event.meetingUrl").value("https://meet.example.com/agenda"))
                .andExpect(jsonPath("$.notifications.emailQueued").value(true))
                .andExpect(jsonPath("$.notifications.calendarUpdateQueued").value(true));
    }

    @Test
    void noActiveLawyerReturnsConfigurationError() throws Exception {
        String lawyerId = "22222222-2222-2222-2222-222222222222";
        mockMvc.perform(put("/api/tenants/no-active-lawyer/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lawyers": [
                                    {
                                      "id": "%s",
                                      "displayName": "Inactive Lawyer",
                                      "email": "inactive@firma.test",
                                      "active": false,
                                      "defaultEventDurationMinutes": 60,
                                      "availabilityWindows": []
                                    }
                                  ],
                                  "routingRules": [
                                    {
                                      "name": "Inactive Route",
                                      "urgentKeywords": [],
                                      "consultationWindows": [],
                                      "lawyerId": "%s",
                                      "urgencyLevels": ["NORMAL"],
                                      "destinationEmail": "inactive@firma.test"
                                    }
                                  ]
                                }
                                """.formatted(lawyerId, lawyerId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tenants/no-active-lawyer/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientName": "Pedro Ruiz",
                                  "clientEmail": "pedro@example.com",
                                  "summary": "Necesito una consulta legal general."
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("active_lawyer_required"));
    }

    @Test
    void adminCanReviewConsultationsForATenant() throws Exception {
        mockMvc.perform(post("/api/tenants/admin-review/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientName": "Carlos Gómez",
                                  "clientEmail": "carlos@example.com",
                                  "summary": "Tengo dudas sobre un contrato de arrendamiento."
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/tenants/admin-review/consultations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("admin-review"))
                .andExpect(jsonPath("$.consultations", hasSize(1)))
                .andExpect(jsonPath("$.consultations[0].clientName").value("Carlos Gómez"))
                .andExpect(jsonPath("$.consultations[0].classification.label").value("MANUAL_REVIEW"));
    }

    @Test
    void invalidConsultationPayloadReturnsValidationProblem() throws Exception {
        mockMvc.perform(post("/api/tenants/invalid/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientName": "",
                                  "clientEmail": "not-an-email",
                                  "summary": "muy corto"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.clientName").exists())
                .andExpect(jsonPath("$.fields.clientEmail").exists())
                .andExpect(jsonPath("$.fields.summary").exists());
    }
}


