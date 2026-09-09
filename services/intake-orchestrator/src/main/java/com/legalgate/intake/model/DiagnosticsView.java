package com.legalgate.intake.model;

import java.time.Instant;
import java.util.List;

/** What the firm sees in the console for one diagnostics session. */
public record DiagnosticsView(
        String consultationId,
        String status,
        String verdict,
        String reason,
        String extractedSummary,
        String promptSnapshot,
        int rounds,
        Instant awaitingReplySince,
        Instant resolvedAt,
        boolean lateReply,
        /** Null when a Verdict was reached; otherwise why Diagnostics could not reach one. */
        String unfilteredCause,
        List<DiagnosticsMessage> transcript
) {
}
