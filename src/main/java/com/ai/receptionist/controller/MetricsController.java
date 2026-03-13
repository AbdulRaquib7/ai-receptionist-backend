package com.ai.receptionist.controller;

import com.ai.receptionist.service.CallSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final CallSessionService callSessionService;

    @Value("${metrics.api-key:}")
    private String configuredApiKey;

    @GetMapping("/calls")
    public ResponseEntity<?> callMetrics(
            @RequestParam(name = "tenantId", required = false) Long tenantId,
            @RequestParam(name = "days", defaultValue = "7") int days,
            @RequestParam(name = "apiKey", required = false) String apiKey) {

        // Simple API key protection
        if (!configuredApiKey.isBlank() && !configuredApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or missing API key"));
        }

        LocalDate since = LocalDate.now().minusDays(days);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalCalls", callSessionService.countSince(since, tenantId));
        metrics.put("byOutcome", callSessionService.countByOutcome(since, tenantId));
        metrics.put("byPipeline", callSessionService.countByPipeline(since, tenantId));
        metrics.put("avgDurationSeconds", callSessionService.avgDuration(since, tenantId));
        metrics.put("failedCalls", callSessionService.countFailed(since, tenantId));
        metrics.put("periodDays", days);
        return ResponseEntity.ok(metrics);
    }
}
