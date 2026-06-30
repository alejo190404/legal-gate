package com.legalgate.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/status", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/onboarding/organization").authenticated()
                        .requestMatchers("/api/session", "/api/tenant/settings", "/api/consultations", "/api/consultations/**")
                        .access((authentication, context) -> {
                            if (!(authentication.get() instanceof JwtAuthenticationToken token)) {
                                return new AuthorizationDecision(false);
                            }
                            boolean hasOrganization = hasText(token.getToken().getClaimAsString("org_id"));
                            boolean isFirmAdmin = token.getAuthorities().stream()
                                    .anyMatch(authority -> "ROLE_FIRM_ADMIN".equals(authority.getAuthority()));
                            return new AuthorizationDecision(hasOrganization && isFirmAdmin);
                        })
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(objectMapper, response, 401, "unauthorized",
                                        "A valid WorkOS access token is required.")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(objectMapper, response, 401, "unauthorized",
                                        "A valid WorkOS access token is required."))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(objectMapper, response, 403, "forbidden",
                                        "An organization-scoped firm_admin session is required.")))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(GatewayProperties properties) {
        require(properties.getWorkos().getClientId(), "WORKOS_CLIENT_ID");
        require(properties.getWorkos().getIssuer(), "WORKOS_ISSUER");
        require(properties.getWorkos().getJwksUrl(), "WORKOS_JWKS_URL");
        require(properties.getForwardedToken(), "LEGALGATE_INTERNAL_SERVICE_TOKEN");

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getWorkos().getJwksUrl()).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(60));
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            boolean valid = properties.getWorkos().getClientId().equals(jwt.getClaimAsString("client_id"))
                    && hasText(jwt.getSubject())
                    && hasText(jwt.getClaimAsString("sid"))
                    && jwt.getIssuedAt() != null
                    && !jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(60));
            return valid
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                            "invalid_token", "Required WorkOS token claims are missing or invalid.", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getWorkos().getIssuer()),
                timestamps,
                requiredClaims));
        return decoder;
    }

    private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.stream().filter(SecurityConfig::hasText)
                        .map(SecurityConfig::roleAuthority)
                        .map(SimpleGrantedAuthority::new)
                        .forEach(authorities::add);
            }
            String role = jwt.getClaimAsString("role");
            if (hasText(role)) {
                authorities.add(new SimpleGrantedAuthority(roleAuthority(role)));
            }
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getCors().getAllowedOrigins());
        configuration.setAllowedOriginPatterns(properties.getCors().getAllowedOriginPatterns());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setExposedHeaders(List.of("Location", "X-Proxied-By"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static String roleAuthority(String role) {
        return "ROLE_" + role.trim().toUpperCase().replace('-', '_');
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(String value, String variable) {
        if (!hasText(value)) {
            throw new IllegalStateException(variable + " must be configured.");
        }
    }

    private static void writeError(
            ObjectMapper objectMapper,
            HttpServletResponse response,
            int status,
            String error,
            String message
    ) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "status", status,
                "error", error,
                "message", message,
                "timestamp", Instant.now().toString()));
    }
}
