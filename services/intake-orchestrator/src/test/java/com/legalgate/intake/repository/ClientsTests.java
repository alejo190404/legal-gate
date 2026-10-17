package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.ClientRequest;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import com.legalgate.intake.clients.ClientsService;
import com.legalgate.intake.clients.ContactAddressTakenException;
import com.legalgate.intake.clients.InMemoryClientsRepository;

/**
 * Seam 1 for Clients: the Clients service on the real in-memory repositories. Lives in the
 * repository package to reach the package-private InMemoryIntakeRepository.
 */
class ClientsTests {

    static final String TENANT = "firma-demo";
    static final String ADMIN = "user_admin";

    private final InMemoryIntakeRepository intake = new InMemoryIntakeRepository();
    private final InMemoryClientsRepository clientsRepository = new InMemoryClientsRepository(intake);
    private final ClientsService clients = ClientsFixtures.clientsService(intake, clientsRepository);

    @Test
    void aClientIsRecordedWithItsContactAddressesAndWhoCreatedIt() {
        ClientsFixtures.saveLawyers(intake, TENANT);

        Client client = clients.create(TENANT, ADMIN, request("Acme SAS", "ORGANIZATION",
                List.of("GC@Acme.co", "ceo@acme.co")));

        assertThat(client.contactAddresses()).containsExactly("gc@acme.co", "ceo@acme.co");
        assertThat(client.responsibleLawyerId()).isEqualTo(ClientsFixtures.ANA);
        assertThat(clients.history(TENANT, client.id()))
                .extracting(HistoryEntry::field, HistoryEntry::oldValue, HistoryEntry::newValue, HistoryEntry::actorUserId)
                .contains(
                        tuple("client", null, "Acme SAS", ADMIN),
                        tuple("contactAddress", null, "gc@acme.co", ADMIN),
                        tuple("contactAddress", null, "ceo@acme.co", ADMIN));
    }

    @Test
    void aContactAddressOnFileForAnotherClientIsRefusedAndTheHolderIsNamed() {
        ClientsFixtures.saveLawyers(intake, TENANT);
        Client acme = clients.create(TENANT, ADMIN, request("Acme SAS", "ORGANIZATION", List.of("gc@acme.co")));

        assertThatThrownBy(() -> clients.create(TENANT, ADMIN,
                request("Otro", "PERSON", List.of("GC@ACME.CO"))))
                .isInstanceOfSatisfying(ContactAddressTakenException.class, taken -> {
                    assertThat(taken.address()).isEqualTo("gc@acme.co");
                    assertThat(taken.clientId()).isEqualTo(acme.id());
                    assertThat(taken.clientName()).isEqualTo("Acme SAS");
                });
    }

    @Test
    void anotherFirmMayHaveTheSameAddressOnFile() {
        ClientsFixtures.saveLawyers(intake, TENANT);
        ClientsFixtures.saveLawyers(intake, "otra-firma");
        clients.create(TENANT, ADMIN, request("Acme SAS", "ORGANIZATION", List.of("gc@acme.co")));

        assertThatCode(() -> clients.create("otra-firma", ADMIN,
                request("Acme SAS", "ORGANIZATION", List.of("gc@acme.co"))))
                .doesNotThrowAnyException();
    }

    @Test
    void aClientNeedsANameAContactAddressAndAnActiveResponsibleLawyer() {
        ClientsFixtures.saveLawyers(intake, TENANT);

        assertThatThrownBy(() -> clients.create(TENANT, ADMIN, request("Acme", "ORGANIZATION", List.of())))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("contact_address_required");
        assertThatThrownBy(() -> clients.create(TENANT, ADMIN, request(" ", "PERSON", List.of("a@b.co"))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("client_name_required");
        assertThatThrownBy(() -> clients.create(TENANT, ADMIN, request("Acme", "PERSON", List.of("not-an-email"))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("invalid_contact_address");
        assertThatThrownBy(() -> clients.create(TENANT, ADMIN, new ClientRequest(
                "Acme", "PERSON", null, null, null, ClientsFixtures.RETIRED, List.of("a@b.co"))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("responsible_lawyer_required");
    }

    @Test
    void historyListsTheLatestChangeFirst() {
        ClientsFixtures.saveLawyers(intake, TENANT);
        Client client = clients.create(TENANT, ADMIN, request("Acme", "ORGANIZATION", List.of("gc@acme.co")));

        clients.update(TENANT, ADMIN, client.id(), new ClientRequest(
                "Acme SAS", "ORGANIZATION", null, null, null, ClientsFixtures.ANA, List.of("gc@acme.co")));

        assertThat(clients.history(TENANT, client.id())).extracting(HistoryEntry::field)
                .first().isEqualTo("name");
        assertThat(clients.history(TENANT, client.id())).extracting(HistoryEntry::field)
                .last().isIn("client", "contactAddress");
    }

    @Test
    void editingAClientRecordsEachChangeWithItsOldAndNewValue() {
        ClientsFixtures.saveLawyers(intake, TENANT);
        Client client = clients.create(TENANT, ADMIN, request("Acme", "ORGANIZATION", List.of("gc@acme.co")));

        clients.update(TENANT, "user_other", client.id(), new ClientRequest(
                "Acme SAS", "ORGANIZATION", "900123456", null, null, ClientsFixtures.LUIS,
                List.of("ceo@acme.co")));

        assertThat(clients.history(TENANT, client.id()))
                .filteredOn(entry -> "user_other".equals(entry.actorUserId()))
                .extracting(HistoryEntry::field, HistoryEntry::oldValue, HistoryEntry::newValue)
                .containsExactlyInAnyOrder(
                        tuple("name", "Acme", "Acme SAS"),
                        tuple("idNumber", null, "900123456"),
                        tuple("responsibleLawyerId", ClientsFixtures.ANA, ClientsFixtures.LUIS),
                        tuple("contactAddress", null, "ceo@acme.co"),
                        tuple("contactAddress", "gc@acme.co", null));
        // The removed address is free again.
        assertThatCode(() -> clients.create(TENANT, ADMIN, request("Otro", "PERSON", List.of("gc@acme.co"))))
                .doesNotThrowAnyException();
    }

    @Test
    void theListSearchesByNameContactAddressOrIdNumber() {
        ClientsFixtures.saveLawyers(intake, TENANT);
        clients.create(TENANT, ADMIN, request("Acme SAS", "ORGANIZATION", List.of("gc@acme.co")));
        clients.create(TENANT, ADMIN, new ClientRequest(
                "Maria Perez", "PERSON", "1020304050", null, null, ClientsFixtures.ANA, List.of("maria@example.com")));

        assertThat(clients.search(TENANT, "acme", false)).extracting(Client::name).containsExactly("Acme SAS");
        assertThat(clients.search(TENANT, "MARIA@", false)).extracting(Client::name).containsExactly("Maria Perez");
        assertThat(clients.search(TENANT, "10203", false)).extracting(Client::name).containsExactly("Maria Perez");
        assertThat(clients.search(TENANT, null, true)).hasSize(2);
    }

    private ClientRequest request(String name, String kind, List<String> addresses) {
        return new ClientRequest(name, kind, null, null, null, ClientsFixtures.ANA, addresses);
    }
}
