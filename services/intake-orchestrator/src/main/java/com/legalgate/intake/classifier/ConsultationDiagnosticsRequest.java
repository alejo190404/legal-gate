package com.legalgate.intake.classifier;

import java.util.List;

/**
 * The diagnose contract with consultation-classifier. Field names and order are pinned by
 * a golden example asserted on both sides (DiagnosticsContractTests here,
 * tests/test_diagnostics.py there).
 */
public record ConsultationDiagnosticsRequest(
        String diagnosticsPrompt,
        ConsultationClassifierRequest.InboundEmail email,
        List<Message> exchange,
        String systemPrompt,
        String promptVersion
) {
    public record Message(String role, String body) {
    }
}
