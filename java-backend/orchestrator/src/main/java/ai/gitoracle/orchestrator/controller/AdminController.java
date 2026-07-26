package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.orchestrator.model.TenantConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
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

    private final jakarta.persistence.EntityManager entityManager;

    public AdminController(jakarta.persistence.EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @GetMapping("/tenants/{id}/metrics")
    public ResponseEntity<Map<String, Object>> getTenantMetrics(@PathVariable("id") String tenantId) {
        logger.info("Fetching metrics for tenant {}", tenantId);
        
        try {
            UUID tenantUuid = UUID.fromString(tenantId);
            
            Long jobsCompleted = entityManager.createQuery(
                "SELECT COUNT(j) FROM AgentJob j WHERE j.tenantId = :tenantId AND j.state = 'COMPLETED'", Long.class)
                .setParameter("tenantId", tenantUuid)
                .getSingleResult();
                
            Long totalJobs = entityManager.createQuery(
                "SELECT COUNT(j) FROM AgentJob j WHERE j.tenantId = :tenantId", Long.class)
                .setParameter("tenantId", tenantUuid)
                .getSingleResult();
                
            double avgSuccessRate = totalJobs > 0 ? (double) jobsCompleted / totalJobs : 0.0;
            
            return ResponseEntity.ok(Map.of(
                "tenantId", tenantId,
                "tokensUsed", 45000, // Still hardcoded as token tracking requires external API aggregator in this version
                "jobsCompleted", jobsCompleted,
                "avgSuccessRate", avgSuccessRate
            ));
        } catch (Exception e) {
            logger.error("Failed to fetch real metrics", e);
            return ResponseEntity.ok(Map.of(
                "tenantId", tenantId,
                "error", "Failed to fetch metrics"
            ));
        }
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

    @GetMapping("/risk-heatmap")
    public ResponseEntity<Map<String, Object>> getRiskHeatmap() {
        logger.info("Fetching risk heatmap from Neo4j...");
        // In a fully integrated system, this would execute a Cypher query against Neo4j
        // For now, we simulate the backend returning the graph analysis result
        return ResponseEntity.ok(Map.of(
            "files", List.of(
                Map.of("file", "PaymentService.java", "bugs", 14, "heat", "critical"),
                Map.of("file", "OrderQueue.java", "bugs", 11, "heat", "critical"),
                Map.of("file", "UserRepository.java", "bugs", 9, "heat", "high"),
                Map.of("file", "TokenValidator.java", "bugs", 8, "heat", "high"),
                Map.of("file", "EmailSender.java", "bugs", 5, "heat", "medium")
            ),
            "developers", List.of(
                Map.of("name", "alice.chen", "commits", 142, "bugRate", 0.12),
                Map.of("name", "bob.kumar", "commits", 98, "bugRate", 0.08),
                Map.of("name", "charlie.wright", "commits", 201, "bugRate", 0.15),
                Map.of("name", "diana.park", "commits", 67, "bugRate", 0.03)
            )
        ));
    }
}
