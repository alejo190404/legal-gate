package com.legalgate.gateway.api;

import com.legalgate.gateway.config.GatewayProperties;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {

    private final GatewayProperties properties;

    public StatusController(GatewayProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "service", "legal-gate-gateway",
                "status", "UP",
                "backendConfigured", properties.hasBackendBaseUrl(),
                "timestamp", Instant.now().toString()
        ));
    }
}
