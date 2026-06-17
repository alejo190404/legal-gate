package com.legalgate.mail.service;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TenantLookupService {

    private final JdbcTemplate jdbcTemplate;

    public TenantLookupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> tenantForIntakeEmail(String intakeEmail) {
        if (intakeEmail == null || intakeEmail.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        select app_find_tenant_for_intake_email(?) as slug
                        """, (rs, rowNum) -> rs.getString("slug"), intakeEmail.trim())
                .stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }
}
