package com.legalgate.intake;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                .andExpect(jsonPath("$.destinationEmail").value("consultas@firma.test"));
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
