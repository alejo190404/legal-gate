package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.legalgate.intake.clients.ClientsModels.ActivityType;
import com.legalgate.intake.clients.ClientsModels.ActivityTypeRequest;
import com.legalgate.intake.clients.ClientsService;
import com.legalgate.intake.clients.InMemoryClientsRepository;

/** Seam 1 for the Activity Type catalog. */
class ActivityTypesTests {

    private static final String TENANT = "firma-demo";

    private final InMemoryIntakeRepository intake = new InMemoryIntakeRepository();
    private final ClientsService clients = ClientsFixtures.clientsService(intake, new InMemoryClientsRepository());

    @Test
    void theFirstReadSeedsTheStarterSetOnce() {
        assertThat(clients.activityTypes(TENANT))
                .extracting(ActivityType::name, ActivityType::kind, ActivityType::defaultTermDays)
                .contains(
                        tuple("Redacción de contrato", "TURNAROUND", null),
                        tuple("Audiencia", "DATED", null),
                        tuple("Fallo de tutela", "EXTERNAL", 10),
                        tuple("Respuesta a derecho de petición", "EXTERNAL", 15),
                        tuple("Registro de marca", "EXTERNAL", null));
        assertThat(clients.activityTypes(TENANT)).hasSize(12);
        assertThat(clients.activityTypes(TENANT))
                .filteredOn(type -> "TURNAROUND".equals(type.kind()))
                .allSatisfy(type -> assertThat(type.defaultTurnaroundDays()).isPositive());
    }

    @Test
    void aFirmThatRetiresItsWholeStarterSetIsNotReseeded() {
        clients.activityTypes(TENANT).forEach(type -> clients.retireActivityType(TENANT, type.id()));

        assertThat(clients.activityTypes(TENANT)).hasSize(12).allSatisfy(type -> assertThat(type.retiredAt()).isNotNull());
        assertThat(clients.offeredActivityTypes(TENANT)).isEmpty();
    }

    @Test
    void kindSpecificDefaultsAreValidated() {
        assertThatThrownBy(() -> clients.createActivityType(TENANT, new ActivityTypeRequest("Asesoría", "TURNAROUND", null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("turnaround_days_required");
        assertThatThrownBy(() -> clients.createActivityType(TENANT, new ActivityTypeRequest("Asesoría", "TURNAROUND", 0, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("turnaround_days_required");
        assertThatThrownBy(() -> clients.createActivityType(TENANT, new ActivityTypeRequest("Recurso", "EXTERNAL", null, -1)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("invalid_term_days");
        assertThatThrownBy(() -> clients.createActivityType(TENANT, new ActivityTypeRequest("Algo", "OTRO", null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("invalid_activity_kind");

        ActivityType dated = clients.createActivityType(TENANT, new ActivityTypeRequest("Diligencia", "DATED", 5, 7));
        assertThat(dated.defaultTurnaroundDays()).isNull();
        assertThat(dated.defaultTermDays()).isNull();
        ActivityType external = clients.createActivityType(TENANT, new ActivityTypeRequest("Recurso", "EXTERNAL", 5, null));
        assertThat(external.defaultTurnaroundDays()).isNull();
        assertThat(external.defaultTermDays()).isNull();
    }

    @Test
    void aRetiredTypeIsNoLongerOfferedButStaysInTheCatalog() {
        ActivityType type = clients.createActivityType(TENANT, new ActivityTypeRequest("Asesoría", "TURNAROUND", 4, null));

        clients.retireActivityType(TENANT, type.id());

        assertThat(clients.offeredActivityTypes(TENANT)).extracting(ActivityType::id).doesNotContain(type.id());
        assertThat(clients.activityTypes(TENANT)).extracting(ActivityType::id).contains(type.id());
    }

    @Test
    void editingATypeChangesItsDefaults() {
        ActivityType type = clients.createActivityType(TENANT, new ActivityTypeRequest("Asesoría", "TURNAROUND", 4, null));

        ActivityType edited = clients.updateActivityType(TENANT, type.id(), new ActivityTypeRequest("Asesoría express", "TURNAROUND", 2, null));

        assertThat(edited.name()).isEqualTo("Asesoría express");
        assertThat(edited.defaultTurnaroundDays()).isEqualTo(2);
    }
}
