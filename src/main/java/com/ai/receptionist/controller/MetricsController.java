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
            @RequestParam(required = false) Long tenantId,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String apiKey) {

        // Simple API key protection
        if (!configuredApiKey.isBlank() && !configuredApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or missing API key"));
        }

        LocalDate since = LocalDate.now().minusDays(days);
        return ResponseEntity.ok(Map.of(
                "totalCalls", callSessionService.countSince(since, tenantId),
                "byOutcome", callSessionService.countByOutcome(since, tenantId),
                "avgDurationSeconds", callSessionService.avgDuration(since, tenantId),
                "failedCalls", callSessionService.countFailed(since, tenantId),
                "periodDays", days
        ));
    }
}
