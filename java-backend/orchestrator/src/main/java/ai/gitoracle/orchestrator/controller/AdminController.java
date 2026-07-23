package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.orchestrator.model.TenantConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);

    @PostMapping("/tenants")
    public ResponseEntity<Map<String, String>> registerTenant(@RequestBody Map<String, String> request) {
        String tenantName = request.get("name");
        String tenantId = UUID.randomUUID().toString();
        logger.info("Registering new tenant: {} with ID: {}", tenantName, tenantId);
        return ResponseEntity.ok(Map.of("tenantId", tenantId, "status", "REGISTERED"));
    }

    @PutMapping("/tenants/{id}/config")
    public ResponseEntity<Map<String, String>> updateTenantConfig(@PathVariable("id") String tenantId, 
                                                                  @RequestBody TenantConfig config) {
        logger.info("Updating config for tenant {}: Budget {}, Risk {}", 
            tenantId, config.getTokenBudget(), config.getRiskScoreThreshold());
        return ResponseEntity.ok(Map.of("status", "UPDATED", "tenantId", tenantId));
    }

    @GetMapping("/tenants/{id}/metrics")
    public ResponseEntity<Map<String, Object>> getTenantMetrics(@PathVariable("id") String tenantId) {
        logger.info("Fetching metrics for tenant {}", tenantId);
        // Mock metrics response
        return ResponseEntity.ok(Map.of(
            "tenantId", tenantId,
            "tokensUsed", 45000,
            "jobsCompleted", 12,
            "avgSuccessRate", 0.92
        ));
    }

    @PostMapping("/prompts/{agent}/activate")
    public ResponseEntity<Map<String, String>> switchPromptVersion(@PathVariable("agent") String agent, 
                                                                   @RequestBody Map<String, String> request) {
        String version = request.get("version");
        logger.info("Switching active prompt for agent '{}' to version '{}'", agent, version);
        return ResponseEntity.ok(Map.of("agent", agent, "activeVersion", version));
    }

    @GetMapping("/eval/results")
    public ResponseEntity<Map<String, Object>> getEvalResults() {
        logger.info("Fetching latest LLM eval harness results");
        // Mock eval results
        return ResponseEntity.ok(Map.of(
            "runId", "eval-994",
            "accuracy", 0.98,
            "regressionDetected", false
        ));
    }
}
