package com.legalgate.intake.model;

import java.time.Instant;

public record DiagnosticsMessage(
        String id,
        String role,
        String body,
        Instant createdAt
) {
    public static final String CLIENT = "CLIENT";
    public static final String LEGALGATE = "LEGALGATE";

    public static DiagnosticsMessage fromClient(String body) {
        return new DiagnosticsMessage(null, CLIENT, body, null);
    }

    public static DiagnosticsMessage fromLegalGate(String body) {
        return new DiagnosticsMessage(null, LEGALGATE, body, null);
    }
}
