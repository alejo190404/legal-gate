package com.legalgate.intake.classifier;

public record ConsultationDiagnosticsResponse(
        String verdict,
        String question,
        String reason,
        String summary
) {
    public static final String ACCEPT = "accept";
    public static final String ASK = "ask";
    public static final String REJECT = "reject";
}
