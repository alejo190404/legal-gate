package com.legalgate.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "legalgate.gateway")
public class GatewayProperties {

    /**
     * External clients must send this value in X-Gateway-Api-Key for protected gateway routes.
     */
    private String apiKey;

    /**
     * Token sent from the gateway to downstream LegalGate services.
     */
    private String forwardedToken;

    private Duration requestTimeout = Duration.ofSeconds(3);

    private final Backend backend = new Backend();

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

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

    public Backend getBackend() {
        return backend;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasForwardedToken() {
        return forwardedToken != null && !forwardedToken.isBlank();
    }

    public boolean hasBackendBaseUrl() {
        return backend.baseUrl != null && !backend.baseUrl.isBlank();
    }

    public static class Backend {
        /**
         * Base URL of the LegalGate backend service, e.g. http://backend:8080.
         */
        private String baseUrl;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
