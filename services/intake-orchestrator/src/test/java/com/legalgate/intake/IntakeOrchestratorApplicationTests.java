package com.legalgate.intake;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.legalgate.intake.model.TenantSettingsResponse;
import com.legalgate.intake.repository.IntakeRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
    void lawyerCanConfigureUrgencyCriteriaWindowsAndDestinationEmail() throws Exception {
        mockMvc.perform(put("/api/tenants/bogota-legal/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "urgentKeywords": ["tutela", "captura", "audiencia"],
                                  "consultationWindows": ["LUN-VIE 08:00-12:00", "LUN-VIE 14:00-17:00"],
                                  "destinationEmail": "consultas@firma.test"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("bogota-legal"))
                .andExpect(jsonPath("$.urgentKeywords", hasSize(3)))
                .andExpect(jsonPath("$.consultationWindows", hasSize(2)))
                .andExpect(jsonPath("$.destinationEmail").value("consultas@firma.test"))
                .andExpect(jsonPath("$.intakeEmail").value("bogota-legal@intake.legal-gate.co"))
                .andExpect(jsonPath("$.routingRules", hasSize(1)))
                .andExpect(jsonPath("$.routingRules[0].destinationEmail").value("consultas@firma.test"));

        mockMvc.perform(get("/api/tenants/bogota-legal/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("bogota-legal"))
                .andExpect(jsonPath("$.intakeEmail").value("bogota-legal@intake.legal-gate.co"));
    }

    @Test
    void settingsGetSelfHealsMissingOrManualIntakeEmail() throws Exception {
        intakeRepository.saveSettings("manual-email", new TenantSettingsResponse(
                "manual-email",
                List.of("captura"),
                List.of(),
                "notificaciones@firma.test",
                "manual@firma.test",
                List.of()
        ));

        mockMvc.perform(get("/api/tenants/manual-email/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intakeEmail").value("manual-email@intake.legal-gate.co"));

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
                                      "urgentKeywords": ["harassment", "workspace incident"],
                                      "consultationWindows": ["LUN 08:00-10:00"],
                                      "destinationEmail": "personal@firma.test"
                                    },
                                    {
                                      "name": "Labor penalties",
                                      "urgentKeywords": ["penalties"],
                                      "consultationWindows": ["MAR 09:00-12:00", "JUE 09:00-12:00"],
                                      "destinationEmail": "laboral@firma.test"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("laboral-legal"))
                .andExpect(jsonPath("$.routingRules", hasSize(2)))
                .andExpect(jsonPath("$.routingRules[0].destinationEmail").value("personal@firma.test"))
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
                .andExpect(jsonPath("$.urgency").value("URGENT"))
                .andExpect(jsonPath("$.classification.matchedUrgentKeywords[0]").value("penalties"))
                .andExpect(jsonPath("$.notifications.destinationEmail").value("laboral@firma.test"))
                .andExpect(jsonPath("$.notifications.preferredWindow").value("MAR 09:00-12:00"));
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
                                  "urgentKeywords": ["audiencia", "captura"],
                                  "consultationWindows": ["LUN-VIE 09:00-13:00"],
                                  "destinationEmail": "intake@familia.test"
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
