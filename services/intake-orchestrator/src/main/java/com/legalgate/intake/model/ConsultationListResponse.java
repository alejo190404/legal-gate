package com.legalgate.intake.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * One page of Bandeja, shaped like {@link Page}. Only the first page carries {@code stageCounts}: how
 * many of the firm's Consultations are in each Stage, whatever Stage the page is filtered to.
 */
public record ConsultationListResponse(
        List<ConsultationResponse> items,
        String nextCursor,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Long> stageCounts
) {
}
