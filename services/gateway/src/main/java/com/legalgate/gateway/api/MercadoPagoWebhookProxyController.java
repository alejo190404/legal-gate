package com.legalgate.gateway.api;

import com.legalgate.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class MercadoPagoWebhookProxyController {
    private final GatewayProperties properties;
    private final WebClient webClient;

    public MercadoPagoWebhookProxyController(
            GatewayProperties properties,
            WebClient.Builder webClientBuilder
    ) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
    }

    @PostMapping("/api/webhooks/mercadopago")
    public ResponseEntity<?> proxy(
            HttpServletRequest request,
            @RequestBody byte[] rawBody,
            @RequestHeader(name = "x-signature", required = false) String signature,
            @RequestHeader(name = "x-request-id", required = false) String requestId,
            @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType
    ) {
        if (!properties.hasBackendBaseUrl()) return FallbackController.backendUnavailable();
        UriComponentsBuilder uri = UriComponentsBuilder
                .fromHttpUrl(properties.getBackend().getBaseUrl())
                .path("/api/webhooks/mercadopago");
        if (StringUtils.hasText(request.getQueryString())) uri.query(request.getQueryString());
        URI target = uri.build(true).toUri();
        try {
            ResponseEntity<byte[]> response = webClient.method(HttpMethod.POST)
                    .uri(target)
                    .headers(headers -> {
                        headers.set("X-LegalGate-Service-Token", properties.getForwardedToken());
                        if (StringUtils.hasText(signature)) headers.set("x-signature", signature);
                        if (StringUtils.hasText(requestId)) headers.set("x-request-id", requestId);
                        if (StringUtils.hasText(contentType)) {
                            headers.setContentType(MediaType.parseMediaType(contentType));
                        }
                    })
                    .bodyValue(rawBody)
                    .exchangeToMono(value -> value.toEntity(byte[].class))
                    .block(properties.getBillingRequestTimeout() == null
                            ? Duration.ofSeconds(10) : properties.getBillingRequestTimeout());
            if (response == null) return FallbackController.backendUnavailable();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(response.getHeaders().getContentType());
            headers.set("X-Proxied-By", "legal-gate-gateway");
            return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
        } catch (WebClientRequestException exception) {
            return FallbackController.backendUnavailable();
        } catch (RuntimeException exception) {
            return FallbackController.backendUnavailable();
        }
    }
}
