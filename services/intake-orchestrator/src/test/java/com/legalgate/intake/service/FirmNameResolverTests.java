package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.repository.IntakeRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FirmNameResolverTests {

    @Test
    void usesTheTenantOrganizationDisplayName() {
        IntakeRepository repository = mock(IntakeRepository.class);
        when(repository.tenantDisplayName("firma-demo")).thenReturn(Optional.of("  Vargas & Asociados  "));

        assertThat(resolver(repository).firmNameFor("firma-demo")).isEqualTo("Vargas & Asociados");
    }

    @Test
    void fallsBackToTheGlobalSenderNameWhenTheTenantHasNoDisplayName() {
        IntakeRepository repository = mock(IntakeRepository.class);
        when(repository.tenantDisplayName("firma-demo")).thenReturn(Optional.of("   "));

        assertThat(resolver(repository).firmNameFor("firma-demo")).isEqualTo("LegalGate Agenda");
    }

    @Test
    void fallsBackToTheGlobalSenderNameForAnUnknownTenant() {
        IntakeRepository repository = mock(IntakeRepository.class);
        when(repository.tenantDisplayName("missing")).thenReturn(Optional.empty());

        assertThat(resolver(repository).firmNameFor("missing")).isEqualTo("LegalGate Agenda");
    }

    private FirmNameResolver resolver(IntakeRepository repository) {
        return new FirmNameResolver(repository, new IntakeProperties(
                "memory", false, "intake.legal-gate.co", null, null, null, null, null, null,
                false, null, null, null, null, false, "test-service-token", "sk_test", "https://api.workos.com",
                null, null));
    }
}
