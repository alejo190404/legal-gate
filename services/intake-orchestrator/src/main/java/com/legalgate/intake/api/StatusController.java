package com.legalgate.intake.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/status")
public class StatusController {

    @GetMapping
    public Map<String, Object> status() {
        return Map.of(
                "service", "legal-gate-intake-orchestrator",
                "status", "UP",
                "channels", Map.of(
                        "web", true,
                        "email", true
                )
        );
    }
}
