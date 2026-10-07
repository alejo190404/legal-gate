package com.legalgate.intake.classifier;

/** Jev's answer, final with no confidence gate (ADR-0006). */
public record ClientMailIntentResponse(String intent, Double confidence) {
    public static final String STATUS_INQUIRY = "status_inquiry";
    public static final String NEEDS_LAWYER = "needs_lawyer";

    public boolean isStatusInquiry() {
        return STATUS_INQUIRY.equals(intent);
    }
}
