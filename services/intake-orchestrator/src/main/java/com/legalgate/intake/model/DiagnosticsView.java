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
        List<DiagnosticsMessage> transcript
) {
}
