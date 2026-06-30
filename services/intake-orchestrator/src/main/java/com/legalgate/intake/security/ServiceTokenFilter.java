package com.legalgate.intake.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.config.IntakeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ServiceTokenFilter extends OncePerRequestFilter {
    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    public ServiceTokenFilter(IntakeProperties properties, ObjectMapper objectMapper) {
        this.expectedToken = properties.internalServiceToken().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/error") || path.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String supplied = request.getHeader("X-LegalGate-Service-Token");
        byte[] suppliedBytes = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, suppliedBytes)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "status", 401,
                    "error", "invalid_service_token",
                    "message", "A valid internal service token is required."));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
