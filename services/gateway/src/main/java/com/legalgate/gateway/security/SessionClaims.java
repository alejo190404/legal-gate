package com.legalgate.gateway.security;

import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the organization a Session Token was issued for. The auth provider packs the organization
 * into a single compact claim rather than into top-level ones, and abbreviates the role inside it,
 * so every read of it goes through here instead of being spelled out at each call site.
 */
public final class SessionClaims {

    private static final String ORGANIZATION_CLAIM = "o";
    private static final String ORGANIZATION_ID = "id";
    private static final String ORGANIZATION_ROLE = "rol";

    /** The role the provider gives whoever created the organization. */
    private static final String ADMIN_ROLE = "admin";

    private SessionClaims() {
    }

    public static String organizationId(Jwt token) {
        return claim(token, ORGANIZATION_ID);
    }

    public static boolean isFirmAdmin(Jwt token) {
        return ADMIN_ROLE.equals(claim(token, ORGANIZATION_ROLE));
    }

    private static String claim(Jwt token, String name) {
        Map<String, Object> organization = token.getClaimAsMap(ORGANIZATION_CLAIM);
        if (organization == null) {
            return null;
        }
        return organization.get(name) instanceof String value && !value.isBlank() ? value : null;
    }
}
