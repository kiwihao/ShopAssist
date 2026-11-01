package com.luhao.ecommerce.ops.api;

import com.luhao.ecommerce.ops.application.HealthService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        var report = healthService.check();
        return ResponseEntity.status(report.healthy() ? 200 : 503).body(report.body());
    }
}
