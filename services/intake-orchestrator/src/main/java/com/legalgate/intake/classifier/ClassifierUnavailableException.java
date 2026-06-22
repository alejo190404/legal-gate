package com.legalgate.intake.classifier;

public class ClassifierUnavailableException extends RuntimeException {
    public ClassifierUnavailableException(String message) {
        super(message);
    }

    public ClassifierUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
