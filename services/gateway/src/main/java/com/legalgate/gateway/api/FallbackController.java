package com.legalgate.gateway.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FallbackController {

    @GetMapping("/api/gateway/fallback")
    public ResponseEntity<Map<String, Object>> fallback() {
        return backendUnavailable();
    }

    public static ResponseEntity<Map<String, Object>> backendUnavailable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", "service_unavailable");
        body.put("service", "backend");
        body.put("message", "LegalGate backend is not connected yet.");
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
