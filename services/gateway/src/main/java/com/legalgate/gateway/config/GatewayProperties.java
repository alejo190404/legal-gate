package com.legalgate.gateway.config;

import com.legalgate.gateway.auth.AuthMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "legalgate.gateway")
public class GatewayProperties {

    /**
     * Token sent from the gateway to downstream LegalGate services when configured.
     * Prototype browser routes do not require clients to send a matching shared secret.
     */
    private String forwardedToken;

    private Duration requestTimeout = Duration.ofSeconds(3);

    /**
     * TODO(workos): switch to WORKOS when JWT validation is implemented and required.
     */
    private AuthMode authMode = AuthMode.PUBLIC_PROTOTYPE;

    private final Backend backend = new Backend();
    private final Cors cors = new Cors();
    private final Workos workos = new Workos();

    public String getForwardedToken() {
        return forwardedToken;
    }

    public void setForwardedToken(String forwardedToken) {
        this.forwardedToken = forwardedToken;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public AuthMode getAuthMode() {
        return authMode;
    }

    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode;
    }

    public Backend getBackend() {
        return backend;
    }

    public Cors getCors() {
        return cors;
    }

    public Workos getWorkos() {
        return workos;
    }

    public boolean hasForwardedToken() {
        return forwardedToken != null && !forwardedToken.isBlank();
    }

    public boolean hasBackendBaseUrl() {
        return backend.baseUrl != null && !backend.baseUrl.isBlank();
    }

    public static class Backend {
        /**
         * Base URL of the LegalGate backend service, e.g. http://intake-orchestrator:8081.
         */
        private String baseUrl;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Cors {
        /**
         * Browser origins allowed to call the gateway facade.
         */
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:4200"));

        /**
         * Browser origin patterns allowed for preview/demo hosts whose subdomains change per deployment.
         */
        private List<String> allowedOriginPatterns = new ArrayList<>(List.of(
                "https://*.vercel.app",
                "https://*.ngrok-free.dev"
        ));

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
        }

        public List<String> getAllowedOriginPatterns() {
            return allowedOriginPatterns;
        }

        public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
            this.allowedOriginPatterns = allowedOriginPatterns == null ? List.of() : allowedOriginPatterns;
        }
    }

    public static class Workos {
        private String issuer;
        private String audience;
        private String jwksUrl;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getJwksUrl() {
            return jwksUrl;
        }

        public void setJwksUrl(String jwksUrl) {
            this.jwksUrl = jwksUrl;
        }
    }
}
