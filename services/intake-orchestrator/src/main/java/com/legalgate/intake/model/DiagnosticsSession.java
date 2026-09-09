package com.legalgate.intake.model;

import com.legalgate.intake.classifier.ConsultationClassifierRequest;
import com.legalgate.intake.classifier.ConsultationDiagnosticsResponse;
import java.time.Instant;

/**
 * One diagnostics conversation, scoped to a single Consultation.
 *
 * <p>{@code nextAttemptAt} is the worker's queue: non-null means the session is due for
 * diagnostics work, null means LegalGate is waiting on the potential client. A session in
 * {@code PENDING} is exactly one of those two things.
 */
public record DiagnosticsSession(
        String id,
        String tenantId,
        String consultationId,
        String replyToken,
        String status,
        String verdict,
        String reason,
        String extractedSummary,
        String promptSnapshot,
        ConsultationClassifierRequest.InboundEmail originalEmail,
        int rounds,
        int attempts,
        Instant nextAttemptAt,
        Instant awaitingReplySince,
        String lastError,
        Instant resolvedAt,
        Instant createdAt,
        /** Null when a Verdict was reached; otherwise why Diagnostics could not reach one. */
        String unfilteredCause
) {
    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String ABANDONED = "ABANDONED";

    public boolean isPending() {
        return PENDING.equals(status);
    }

    public DiagnosticsSession dueNow(Instant now) {
        return new DiagnosticsSession(id, tenantId, consultationId, replyToken, status, verdict, reason,
                extractedSummary, promptSnapshot, originalEmail, rounds, attempts, now, null, lastError,
                resolvedAt, createdAt, unfilteredCause);
    }

    public DiagnosticsSession awaitingReply(Instant now, String reason, String summary) {
        return new DiagnosticsSession(id, tenantId, consultationId, replyToken, PENDING,
                ConsultationDiagnosticsResponse.ASK, reason, summary, promptSnapshot, originalEmail,
                rounds + 1, 0, null, now, null, resolvedAt, createdAt, unfilteredCause);
    }

    /**
     * The verdict is accept but the matter is not scheduled yet. Recorded before scheduling so an
     * interrupted acceptance resumes rather than re-asking the model.
     */
    public DiagnosticsSession accepting(String reason, String summary) {
        return new DiagnosticsSession(id, tenantId, consultationId, replyToken, PENDING,
                ConsultationDiagnosticsResponse.ACCEPT, reason, summary == null ? extractedSummary : summary,
                promptSnapshot, originalEmail, rounds, attempts, nextAttemptAt, null, lastError,
                resolvedAt, createdAt, unfilteredCause);
    }

    public DiagnosticsSession withAwaitingReplySince(Instant since) {
        return new DiagnosticsSession(id, tenantId, consultationId, replyToken, status, verdict, reason,
                extractedSummary, promptSnapshot, originalEmail, rounds, attempts, nextAttemptAt, since,
                lastError, resolvedAt, createdAt, unfilteredCause);
    }

    public DiagnosticsSession withResolvedAt(Instant at) {
        return new DiagnosticsSession(id, tenantId, consultationId, replyToken, status, verdict, reason,
                extractedSummary, promptSnapshot, originalEmail, rounds, attempts, nextAttemptAt,
                awaitingReplySince, lastError, at, createdAt, unfilteredCause);
    }

    /** Retention: the transcript's other half. The verdict, reason and prompt snapshot stay. */
    public DiagnosticsSession withTranscriptPurged() {
        return new DiagnosticsSession(id, tenantId, consultationId, replyToken, status, verdict, reason,
                extractedSummary, promptSnapshot, null, rounds, attempts, nextAttemptAt, awaitingReplySince,
                lastError, resolvedAt, createdAt, unfilteredCause);
    }

    public DiagnosticsSession retrying(Instant nextAttempt, String error) {
        return new DiagnosticsSession(id, tenantId, consultationId, replyToken, status, verdict, reason,
                extractedSummary, promptSnapshot, originalEmail, rounds, attempts + 1, nextAttempt,
                awaitingReplySince, error, resolvedAt, createdAt, unfilteredCause);
    }

    /**
     * The matter proceeds without a Verdict because Diagnostics could not reach one. Recorded as
     * its own fact rather than as a {@code reason}, which is the model's words about the matter.
     */
    public DiagnosticsSession unfiltered(String cause) {
        return new DiagnosticsSession(id, tenantId, consultationId, replyToken, status, verdict, reason,
                extractedSummary, promptSnapshot, originalEmail, rounds, attempts, nextAttemptAt,
                awaitingReplySince, lastError, resolvedAt, createdAt, cause);
    }

    public DiagnosticsSession resolved(String newStatus, String newVerdict, String newReason, String summary, Instant now) {
        return new DiagnosticsSession(id, tenantId, consultationId, replyToken, newStatus, newVerdict, newReason,
                summary == null ? extractedSummary : summary, promptSnapshot, originalEmail, rounds, attempts,
                null, null, lastError, now, createdAt, unfilteredCause);
    }
}
