package com.legalgate.intake.repository;

import java.util.List;

import com.legalgate.intake.classifier.ConsultationClassifierClient;
import com.legalgate.intake.clients.ClientsRepository;
import com.legalgate.intake.clients.ClientsService;
import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.UrgencyDefinition;
import com.legalgate.intake.service.EmailTemplateRenderer;
import com.legalgate.intake.service.FirmNameResolver;
import com.legalgate.intake.service.IntakeService;

/** Shared wiring for the Clients Seam 1 tests. */
final class ClientsFixtures {

    static final String ANA = "11111111-1111-1111-1111-111111111111";
    static final String LUIS = "22222222-2222-2222-2222-222222222222";
    static final String RETIRED = "33333333-3333-3333-3333-333333333333";

    private ClientsFixtures() {
    }

    static ClientsService clientsService(InMemoryIntakeRepository intake, ClientsRepository clients) {
        return new ClientsService(clients, intake);
    }

    static IntakeService intakeService(InMemoryIntakeRepository intake, ConsultationClassifierClient classifier) {
        return new IntakeService(intake, properties(), classifier, new EmailTemplateRenderer(),
                new FirmNameResolver(intake, properties()));
    }

    static void saveLawyers(InMemoryIntakeRepository intake, String tenant) {
        saveSettings(intake, tenant, null);
    }

    static void saveSettings(InMemoryIntakeRepository intake, String tenant, String diagnosticsPrompt) {
        intakeService(intake, null).saveSettings(tenant, settingsRequest(diagnosticsPrompt, lawyers()));
    }

    static TenantSettingsRequest settingsRequest(String diagnosticsPrompt, List<LawyerProfile> lawyers) {
        return new TenantSettingsRequest(
                List.of(new TenantRoutingRule(
                        "Laboral", "Despidos y contratos", List.of(), List.of("manana"),
                        List.of("NORMAL", "URGENTE"), LUIS,
                        List.of(new UrgencyDefinition("NORMAL", 1, 5, true), new UrgencyDefinition("URGENTE", 2, 1, true)),
                        "luis@firm.co")),
                lawyers, diagnosticsPrompt, null);
    }

    static List<LawyerProfile> lawyers() {
        return List.of(
                new LawyerProfile(ANA, "Ana Abogada", "ana@firm.co", true, 60, null),
                new LawyerProfile(LUIS, "Luis Litigante", "luis@firm.co", true, 60, null),
                new LawyerProfile(RETIRED, "Rita Retirada", "rita@firm.co", false, 60, null));
    }

    static IntakeProperties properties() {
        return new IntakeProperties(
                "memory", false, "intake.legal-gate.co", null, null, null, null, null, null,
                false, null, null, null, null, false, "test-token", "test-key", null, null, null);
    }
}
