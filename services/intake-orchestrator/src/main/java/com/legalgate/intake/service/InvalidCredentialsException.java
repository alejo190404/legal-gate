package com.legalgate.intake.service;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid_credentials");
    }
}
