package com.legalgate.intake.model;

import jakarta.validation.Valid;
import java.util.List;

public record TenantSettingsRequest(
        List<@Valid TenantRoutingRule> routingRules,
        List<@Valid LawyerProfile> lawyers,
        String diagnosticsPrompt,
        String nonEngagementNotice,
        /** Whether Status Inquiries are answered on their own; null leaves the current setting. */
        Boolean automaticAnswers
) {
    public TenantSettingsRequest(List<TenantRoutingRule> routingRules, List<LawyerProfile> lawyers) {
        this(routingRules, lawyers, null, null);
    }

    public TenantSettingsRequest(List<TenantRoutingRule> routingRules, List<LawyerProfile> lawyers,
            String diagnosticsPrompt, String nonEngagementNotice) {
        this(routingRules, lawyers, diagnosticsPrompt, nonEngagementNotice, null);
    }
}
