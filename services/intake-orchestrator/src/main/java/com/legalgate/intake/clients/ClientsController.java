package com.legalgate.intake.clients;

import com.legalgate.intake.clients.ClientsModels.ActivityType;
import com.legalgate.intake.clients.ClientsModels.ActivityTypeRequest;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.ClientRequest;
import com.legalgate.intake.clients.ClientsModels.Correspondence;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import com.legalgate.intake.service.TenantContextResolver;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClientsController {

    static final String ORGANIZATION = "X-LegalGate-Organization-Id";
    static final String USER = "X-LegalGate-User-Id";

    private final ClientsService clients;
    private final TenantContextResolver tenants;

    public ClientsController(ClientsService clients, TenantContextResolver tenants) {
        this.clients = clients;
        this.tenants = tenants;
    }

    @GetMapping("/api/clients")
    public List<Client> clients(
            @RequestHeader(ORGANIZATION) String organizationId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean idle
    ) {
        return clients.search(tenant(organizationId), q, idle);
    }

    @PostMapping("/api/clients")
    public ResponseEntity<Client> create(
            @RequestHeader(ORGANIZATION) String organizationId,
            @RequestHeader(USER) String userId,
            @RequestBody ClientRequest request
    ) {
        Client client = clients.create(tenant(organizationId), userId, request);
        return ResponseEntity.created(URI.create("/api/clients/" + client.id())).body(client);
    }

    @GetMapping("/api/clients/{clientId}")
    public Client client(@RequestHeader(ORGANIZATION) String organizationId, @PathVariable String clientId) {
        return clients.client(tenant(organizationId), clientId);
    }

    @PutMapping("/api/clients/{clientId}")
    public Client update(
            @RequestHeader(ORGANIZATION) String organizationId,
            @RequestHeader(USER) String userId,
            @PathVariable String clientId,
            @RequestBody ClientRequest request
    ) {
        return clients.update(tenant(organizationId), userId, clientId, request);
    }

    @GetMapping("/api/clients/{clientId}/history")
    public List<HistoryEntry> history(@RequestHeader(ORGANIZATION) String organizationId, @PathVariable String clientId) {
        return clients.history(tenant(organizationId), clientId);
    }

    @GetMapping("/api/clients/{clientId}/correspondence")
    public List<Correspondence> correspondence(@RequestHeader(ORGANIZATION) String organizationId, @PathVariable String clientId) {
        return clients.correspondence(tenant(organizationId), clientId);
    }

    @GetMapping("/api/activity-types")
    public List<ActivityType> activityTypes(@RequestHeader(ORGANIZATION) String organizationId) {
        return clients.activityTypes(tenant(organizationId));
    }

    @PostMapping("/api/activity-types")
    public ActivityType createActivityType(
            @RequestHeader(ORGANIZATION) String organizationId,
            @RequestBody ActivityTypeRequest request
    ) {
        return clients.createActivityType(tenant(organizationId), request);
    }

    @PutMapping("/api/activity-types/{activityTypeId}")
    public ActivityType updateActivityType(
            @RequestHeader(ORGANIZATION) String organizationId,
            @PathVariable String activityTypeId,
            @RequestBody ActivityTypeRequest request
    ) {
        return clients.updateActivityType(tenant(organizationId), activityTypeId, request);
    }

    @PostMapping("/api/activity-types/{activityTypeId}/retire")
    public ActivityType retireActivityType(
            @RequestHeader(ORGANIZATION) String organizationId,
            @PathVariable String activityTypeId
    ) {
        return clients.retireActivityType(tenant(organizationId), activityTypeId);
    }

    /** The form needs to say which Client already holds the address, not just that one does. */
    @ExceptionHandler(ContactAddressTakenException.class)
    public ResponseEntity<Map<String, Object>> contactAddressTaken(ContactAddressTakenException taken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "contact_address_taken");
        body.put("message", taken.address() + " ya pertenece a " + taken.clientName());
        body.put("address", taken.address());
        body.put("clientId", taken.clientId());
        body.put("clientName", taken.clientName());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private String tenant(String organizationId) {
        return tenants.requireActiveTenant(organizationId).slug();
    }
}
