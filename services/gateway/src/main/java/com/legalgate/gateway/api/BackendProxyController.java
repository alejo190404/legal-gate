package com.legalgate.gateway.api;

import com.legalgate.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/backend")
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

    @RequestMapping({"", "/**"})
    public ResponseEntity<?> proxy(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        if (!properties.hasBackendBaseUrl()) {
            return FallbackController.backendUnavailable();
        }

        URI targetUri = buildTargetUri(request);
        try {
            ResponseEntity<byte[]> backendResponse = webClient
                    .method(HttpMethod.valueOf(request.getMethod()))
                    .uri(targetUri)
                    .headers(headers -> copyForwardableHeaders(request, headers))
                    .bodyValue(body == null ? new byte[0] : body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response -> Mono.error(new BackendUnavailableException()))
                    .toEntity(byte[].class)
                    .block(timeout());

            if (backendResponse == null) {
                return FallbackController.backendUnavailable();
            }

            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.putAll(backendResponse.getHeaders());
            HOP_BY_HOP_HEADERS.forEach(responseHeaders::remove);
            responseHeaders.set("X-Proxied-By", "legal-gate-gateway");
            return new ResponseEntity<>(backendResponse.getBody(), responseHeaders, backendResponse.getStatusCode());
        } catch (RuntimeException ex) {
            return FallbackController.backendUnavailable();
        }
    }

    private URI buildTargetUri(HttpServletRequest request) {
        String gatewayPrefix = request.getContextPath() + "/api/backend";
        String requestUri = request.getRequestURI();
        String downstreamPath = requestUri.startsWith(gatewayPrefix)
                ? requestUri.substring(gatewayPrefix.length())
                : requestUri;
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

    private void copyForwardableHeaders(HttpServletRequest request, HttpHeaders headers) {
        Collections.list(request.getHeaderNames()).forEach(headerName -> {
            if (!isHopByHopHeader(headerName)) {
                headers.put(headerName, Collections.list(request.getHeaders(headerName)));
            }
        });
        if (properties.hasForwardedToken()) {
            headers.set("X-LegalGate-Service-Token", properties.getForwardedToken());
        }
        headers.set("X-Forwarded-Host", request.getServerName());
        headers.set("X-Forwarded-Proto", request.getScheme());
    }

    private boolean isHopByHopHeader(String headerName) {
        return HOP_BY_HOP_HEADERS.stream().anyMatch(disallowed -> disallowed.equalsIgnoreCase(headerName));
    }

    private Duration timeout() {
        return properties.getRequestTimeout() == null ? Duration.ofSeconds(3) : properties.getRequestTimeout();
    }

    private static final class BackendUnavailableException extends RuntimeException {
    }
}
