package com.legalgate.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.gateway.auth.AuthMode;
import com.legalgate.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            GatewayProperties properties,
            ObjectMapper objectMapper) throws Exception {
        validateAuthMode(properties);

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/status", "/api/gateway/fallback", "/error").permitAll()
                        // public-prototype: only the gateway facade paths needed by the Vercel demo are public.
                        // TODO(workos): require validated WorkOS JWTs here before exposing real admin workflows.
                        .requestMatchers(HttpMethod.POST, "/api/backend/api/tenants/*/consultations").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/backend/api/admin/tenants/*/consultations").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/backend/api/tenants/*/settings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/backend/api/status").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getOutputStream(), Map.of(
                            "status", HttpServletResponse.SC_UNAUTHORIZED,
                            "error", "unauthorized",
                            "message", "WorkOS authentication is required for this route.",
                            "timestamp", Instant.now().toString()
                    ));
                }))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getCors().getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setExposedHeaders(List.of("Location", "X-Proxied-By"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void validateAuthMode(GatewayProperties properties) {
        if (properties.getAuthMode() == AuthMode.WORKOS) {
            throw new IllegalStateException("WorkOS auth mode is reserved for the next milestone; JWT validation is not implemented yet.");
        }
    }
}
