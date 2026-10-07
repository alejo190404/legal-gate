package com.legalgate.intake.clients;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A Contact Address is on file for one Client per Tenant; this names the Client holding it. */
public class ContactAddressTakenException extends ResponseStatusException {

    private final String address;
    private final String clientId;
    private final String clientName;

    public ContactAddressTakenException(String address, String clientId, String clientName) {
        super(HttpStatus.CONFLICT, "contact_address_taken");
        this.address = address;
        this.clientId = clientId;
        this.clientName = clientName;
    }

    public String address() {
        return address;
    }

    public String clientId() {
        return clientId;
    }

    public String clientName() {
        return clientName;
    }
}
