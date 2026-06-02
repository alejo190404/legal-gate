package com.legalgate.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.gateway.config.GatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class GatewayApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Gateway-Api-Key";

    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public GatewayApiKeyAuthenticationFilter(GatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isProtectedGatewayRoute(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader(API_KEY_HEADER);
        if (properties.hasApiKey() && properties.getApiKey().equals(providedKey)) {
            SecurityContextHolder.getContext().setAuthentication(new GatewayAuthentication());
            filterChain.doFilter(request, response);
            return;
        }

        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "status", HttpServletResponse.SC_UNAUTHORIZED,
                "error", "unauthorized",
                "message", "A valid gateway API key is required.",
                "timestamp", Instant.now().toString()
        ));
    }

    private boolean isProtectedGatewayRoute(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/backend/") || request.getRequestURI().equals("/api/backend");
    }

    private static final class GatewayAuthentication extends AbstractAuthenticationToken {
        private GatewayAuthentication() {
            super(List.of(new SimpleGrantedAuthority("ROLE_GATEWAY_CLIENT")));
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "[PROTECTED]";
        }

        @Override
        public Object getPrincipal() {
            return "gateway-client";
        }
    }
}
