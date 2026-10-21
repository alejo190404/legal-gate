package com.legalgate.intake.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A Consultation's Stage, as the console sorts its Bandeja by it. */
class ConsultationStageTests {

    @Test
    void aConsultationUnderDiagnosticsIsInDiagnostics() {
        assertThat(consultation("DIAGNOSTICS_PENDING").stage()).isEqualTo("diagnostics");
    }

    @Test
    void aMatterTheFirmDoesNotTakeIsRejected() {
        assertThat(consultation("DIAGNOSTICS_REJECTED").stage()).isEqualTo("rejected");
    }

    @Test
    void aPotentialClientWhoStoppedReplyingLeavesTheConsultationAbandoned() {
        assertThat(consultation("DIAGNOSTICS_ABANDONED").stage()).isEqualTo("abandoned");
    }

    @Test
    void aTentativeEventWithATimeOutsideTheSlaIsScheduled() {
        assertThat(withEvent("LLM_CLASSIFIED", scheduledEvent("TENTATIVE", false)).stage()).isEqualTo("scheduled");
    }

    @Test
    void aTentativeEventWhoseSlaIsUnknownIsScheduled() {
        assertThat(withEvent("LLM_CLASSIFIED", scheduledEvent("TENTATIVE", null)).stage()).isEqualTo("scheduled");
    }

    // The console has always shown an Event booked within its SLA as confirmed; Stage keeps that.
    @Test
    void anEventBookedWithinItsSlaIsConfirmed() {
        assertThat(withEvent("LLM_CLASSIFIED", scheduledEvent("TENTATIVE", true)).stage()).isEqualTo("confirmed");
    }

    @Test
    void aConfirmedEventIsConfirmed() {
        assertThat(withEvent("LLM_CLASSIFIED", scheduledEvent("CONFIRMED", false)).stage()).isEqualTo("confirmed");
    }

    @Test
    void aConfirmedConsultationWithATimeIsConfirmed() {
        assertThat(withEvent("CONFIRMED", scheduledEvent("TENTATIVE", false)).stage()).isEqualTo("confirmed");
    }

    @Test
    void anEventWithALawyerButNoTimeYetIsClassified() {
        EventResponse unscheduled = new EventResponse("e-1", "l-1", "Laura", "laura@firma.co", "Laboral", "Normal",
                5, null, 10, null, null, "TENTATIVE", "AUTO");
        assertThat(withEvent("LLM_CLASSIFIED", unscheduled).stage()).isEqualTo("classified");
    }

    @Test
    void aConsultationAssignedToALawyerIsClassifiedEvenWhenClassificationWasUnsure() {
        assertThat(build("LLM_CLASSIFIED", "laura@firma.co", confidence(0.2), null).stage()).isEqualTo("classified");
    }

    @Test
    void anUnassignedConsultationClassifiedWithLowConfidenceIsUnrouted() {
        assertThat(build("LLM_CLASSIFIED", null, confidence(0.49), null).stage()).isEqualTo("unrouted");
    }

    @Test
    void anUnassignedConsultationClassifiedAtHalfConfidenceIsNew() {
        assertThat(build("LLM_CLASSIFIED", null, confidence(0.5), null).stage()).isEqualTo("new");
    }

    @Test
    void aConsultationWhoseClassificationFailedIsUnrouted() {
        assertThat(consultation("LLM_FAILED").stage()).isEqualTo("unrouted");
    }

    @Test
    void aJustReceivedConsultationIsNew() {
        assertThat(consultation("RECEIVED").stage()).isEqualTo("new");
    }

    private static ConsultationResponse consultation(String status) {
        return withEvent(status, null);
    }

    private static ConsultationResponse withEvent(String status, EventResponse event) {
        return build(status, null, null, event);
    }

    private static ConsultationResponse build(String status, String assignedLawyerEmail,
            ClassificationResult classification, EventResponse event) {
        return new ConsultationResponse("c-1", "firma", "Ana", "ana@example.com", "Contrato", null, status,
                "NORMAL", null, assignedLawyerEmail, classification, null, null, null,
                Instant.parse("2026-10-01T10:00:00Z"), event == null ? null : event.id(), event);
    }

    private static ClassificationResult confidence(double confidence) {
        return new ClassificationResult("Laboral", List.of(), "despido", "", confidence);
    }

    private static EventResponse scheduledEvent(String status, Boolean withinSla) {
        Instant start = Instant.parse("2026-10-05T15:00:00Z");
        return new EventResponse("e-1", "l-1", "Laura", "laura@firma.co", "Laboral", "Normal", 5,
                Instant.parse("2026-10-08T23:00:00Z"), 10, start, start.plusSeconds(1800), null, withinSla,
                status, "AUTO");
    }
}
