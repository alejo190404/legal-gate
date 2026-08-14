package com.legalgate.intake.classifier;

/**
 * @param acknowledgment what the potential client wrote about, in their own terms. Its own field
 *        rather than part of the question so the two can be asserted on — and degraded —
 *        independently. Absent on every verdict but ask, and optional even there.
 */
public record ConsultationDiagnosticsResponse(
        String verdict,
        String question,
        String acknowledgment,
        String reason,
        String summary
) {
    public static final String ACCEPT = "accept";
    public static final String ASK = "ask";
    public static final String REJECT = "reject";
}
