package com.legalgate.gateway.api;

import com.legalgate.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@RestController
public class BackendProxyController {

    private static final List<String> HOP_BY_HOP_HEADERS = List.of(
            HttpHeaders.CONNECTION,
            "Keep-Alive",
            HttpHeaders.PROXY_AUTHENTICATE,
            HttpHeaders.PROXY_AUTHORIZATION,
            HttpHeaders.TE,
            HttpHeaders.TRAILER,
            HttpHeaders.TRANSFER_ENCODING,
            HttpHeaders.UPGRADE,
            HttpHeaders.HOST,
            HttpHeaders.CONTENT_LENGTH
    );

    private final GatewayProperties properties;
    private final WebClient webClient;

    public BackendProxyController(GatewayProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
    }

    @RequestMapping({
            "/api/session",
            "/api/onboarding/organization",
            "/api/tenant/settings",
            "/api/billing",
            "/api/billing/**",
            "/api/consultations",
            "/api/consultations/**"
    })
    public ResponseEntity<?> proxy(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body,
            JwtAuthenticationToken authentication
    ) {
        if (!properties.hasBackendBaseUrl()) {
            return FallbackController.backendUnavailable();
        }

        URI targetUri = buildTargetUri(request);
        try {
            ResponseEntity<byte[]> backendResponse = webClient
                    .method(HttpMethod.valueOf(request.getMethod()))
                    .uri(targetUri)
                    .headers(headers -> copyForwardableHeaders(request, headers, authentication))
                    .bodyValue(body == null ? new byte[0] : body)
                    .exchangeToMono(response -> response.toEntity(byte[].class))
                    .block(timeout(request.getRequestURI()));

            if (backendResponse == null) {
                return FallbackController.backendUnavailable();
            }

            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.putAll(backendResponse.getHeaders());
            HOP_BY_HOP_HEADERS.forEach(responseHeaders::remove);
            responseHeaders.set("X-Proxied-By", "legal-gate-gateway");
            return new ResponseEntity<>(backendResponse.getBody(), responseHeaders, backendResponse.getStatusCode());
        } catch (WebClientRequestException ex) {
            return FallbackController.backendUnavailable();
        } catch (RuntimeException ex) {
            if (causedByBackendConnectivity(ex)) {
                return FallbackController.backendUnavailable();
            }
            throw ex;
        }
    }

    private URI buildTargetUri(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String downstreamPath = requestUri;
        if (!StringUtils.hasText(downstreamPath)) {
            downstreamPath = "/";
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(properties.getBackend().getBaseUrl())
                .path(downstreamPath);
        if (StringUtils.hasText(request.getQueryString())) {
            builder.query(request.getQueryString());
        }
        return builder.build(true).toUri();
    }

    private void copyForwardableHeaders(
            HttpServletRequest request,
            HttpHeaders headers,
            JwtAuthenticationToken authentication
    ) {
        Collections.list(request.getHeaderNames()).forEach(headerName -> {
            if (!isHopByHopHeader(headerName) && !isUntrustedSecurityHeader(headerName)) {
                headers.put(headerName, Collections.list(request.getHeaders(headerName)));
            }
        });
        headers.set("X-LegalGate-Service-Token", properties.getForwardedToken());
        headers.set("X-LegalGate-User-Id", authentication.getToken().getSubject());
        headers.set("X-LegalGate-Session-Id", authentication.getToken().getClaimAsString("sid"));
        setIfPresent(headers, "X-LegalGate-Organization-Id",
                authentication.getToken().getClaimAsString("org_id"));
        setIfPresent(headers, "X-LegalGate-Role",
                authentication.getToken().getClaimAsString("role"));
        headers.set("X-Forwarded-Host", request.getServerName());
        headers.set("X-Forwarded-Proto", request.getScheme());
    }

    private boolean isHopByHopHeader(String headerName) {
        return HOP_BY_HOP_HEADERS.stream().anyMatch(disallowed -> disallowed.equalsIgnoreCase(headerName));
    }

    private boolean isUntrustedSecurityHeader(String headerName) {
        return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(headerName)
                || HttpHeaders.COOKIE.equalsIgnoreCase(headerName)
                || "Forwarded".equalsIgnoreCase(headerName)
                || headerName.regionMatches(true, 0, "X-Forwarded-", 0, "X-Forwarded-".length())
                || headerName.regionMatches(true, 0, "X-LegalGate-", 0, "X-LegalGate-".length());
    }

    private void setIfPresent(HttpHeaders headers, String name, String value) {
        if (StringUtils.hasText(value)) {
            headers.set(name, value);
        }
    }

    private Duration timeout(String requestUri) {
        if (requestUri != null && requestUri.startsWith("/api/billing/")) {
            return properties.getBillingRequestTimeout() == null
                    ? Duration.ofSeconds(10) : properties.getBillingRequestTimeout();
        }
        return properties.getRequestTimeout() == null ? Duration.ofSeconds(3) : properties.getRequestTimeout();
    }

    private boolean causedByBackendConnectivity(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientRequestException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
