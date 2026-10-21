package com.legalgate.intake.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A lawyer is their email within the firm; this names the lawyer who already has it. */
public class LawyerEmailTakenException extends ResponseStatusException {

    private final String email;
    private final String lawyerName;

    public LawyerEmailTakenException(String email, String lawyerName) {
        super(HttpStatus.BAD_REQUEST, "lawyer_email_taken");
        this.email = email;
        this.lawyerName = lawyerName;
    }

    @Override
    public String getMessage() {
        return email + " ya pertenece a " + lawyerName + ". Si vuelve a la firma, agrégalo con ese email en lugar de cambiar otro abogado.";
    }
}
