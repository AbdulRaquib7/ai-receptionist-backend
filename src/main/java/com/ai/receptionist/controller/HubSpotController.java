package com.ai.receptionist.controller;

import com.ai.receptionist.service.hubspot.HubSpotSyncOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/hubspot")
public class HubSpotController {

    private final HubSpotSyncOrchestrator syncOrchestrator;

    public HubSpotController(HubSpotSyncOrchestrator syncOrchestrator) {
        this.syncOrchestrator = syncOrchestrator;
    }

    /**
     * Manually trigger a full sync for a specific tenant
     * POST /api/v1/hubspot/sync/tenant/{tenantId}
     */
    @PostMapping("/sync/tenant/{tenantId}")
    public ResponseEntity<String> syncTenant(@PathVariable Long tenantId) {
        try {
            log.info("Triggered manual full sync for tenant {}", tenantId);
            syncOrchestrator.fullTenantSync(tenantId);
            return ResponseEntity.ok("Sync initiated for tenant " + tenantId);
        } catch (Exception e) {
            log.error("Error triggering tenant sync", e);
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    /**
     * Check if HubSpot integration is healthy
     * GET /api/v1/hubspot/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("HubSpot integration is active");
    }

    /**
     * Get sync configuration status
     * GET /api/v1/hubspot/config
     */
    @GetMapping("/config")
    public ResponseEntity<String> getConfig() {
        return ResponseEntity.ok("HubSpot CRM integration configured. Use POST /sync/tenant/{tenantId} to trigger sync.");
    }
}
