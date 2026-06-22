package com.legalgate.intake.model;

import java.time.Instant;
import java.util.List;

public record ClassificationResult(
        String label,
        List<String> matchedUrgentKeywords,
        String concept,
        String explanation,
        Double confidence
) {
}
