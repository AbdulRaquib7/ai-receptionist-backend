package com.ai.receptionist.controller;

import com.ai.receptionist.config.RealtimeApiProperties;
import com.ai.receptionist.entity.Tenant;
import com.ai.receptionist.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final TenantService tenantService;
    private final RealtimeApiProperties realtimeProps;

    @Value("${metrics.api-key:}")
    private String configuredApiKey;

    @Value("${openai.api-key}")
    private String openAiApiKey;

    /**
     * Toggle the Realtime API flag for a tenant.
     * Protected by the same API key as /api/metrics/calls.
     */
    @PatchMapping("/tenants/{slug}/realtime-api")
    public ResponseEntity<?> toggleRealtimeApi(
            @PathVariable("slug") String slug,
            @RequestParam(name = "apiKey", required = false) String apiKey,
            @RequestBody Map<String, Object> body) {

        if (!configuredApiKey.isBlank() && !configuredApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or missing API key"));
        }

        Boolean enabled = (Boolean) body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'enabled' field"));
        }

        try {
            Tenant tenant = tenantService.setRealtimeApiEnabled(slug, enabled);
            return ResponseEntity.ok(Map.of(
                    "tenant", tenant.getSlug(),
                    "useRealtimeApi", tenant.isUseRealtimeApi()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get the current realtime configuration for a tenant.
     */
    @GetMapping("/tenants/{slug}/realtime-config")
    public ResponseEntity<?> getRealtimeConfig(
            @PathVariable("slug") String slug,
            @RequestParam(name = "apiKey", required = false) String apiKey) {

        if (!configuredApiKey.isBlank() && !configuredApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or missing API key"));
        }

        Tenant tenant = tenantService.findBySlug(slug);
        if (tenant == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Tenant not found: " + slug));
        }
        String voice = tenantService.getConfig(tenant.getId(), "realtime_voice", "alloy");
        return ResponseEntity.ok(Map.of(
                "tenant", tenant.getSlug(),
                "useRealtimeApi", tenant.isUseRealtimeApi(),
                "voice", voice
        ));
    }

    /**
     * Connectivity check: verifies that the OpenAI Realtime API endpoint is reachable.
     * Makes a lightweight HTTP OPTIONS/GET to the REST equivalent to validate the API key
     * without establishing a full WebSocket connection.
     */
    @GetMapping("/realtime-health")
    public ResponseEntity<?> realtimeHealth(
            @RequestParam(name = "apiKey", required = false) String apiKey) {

        if (!configuredApiKey.isBlank() && !configuredApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or missing API key"));
        }

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("globalEnabled", realtimeProps.isEnabled());
        health.put("model", realtimeProps.getModel());
        health.put("apiUrl", realtimeProps.getApiUrl());

        // Quick connectivity check: HTTP GET to OpenAI models endpoint to validate API key
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/models/" + realtimeProps.getModel()))
                    .header("Authorization", "Bearer " + openAiApiKey)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            health.put("apiKeyValid", response.statusCode() == 200);
            health.put("httpStatus", response.statusCode());
        } catch (Exception e) {
            health.put("apiKeyValid", false);
            health.put("error", e.getMessage());
            log.warn("Realtime health check failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(health);
    }
}
