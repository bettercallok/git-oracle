package ai.gitoracle.ingestor.controller;

import ai.gitoracle.ingestor.service.SemanticDedupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);
    private final SemanticDedupService dedupService;

    public WebhookController(SemanticDedupService dedupService) {
        this.dedupService = dedupService;
    }

    @PostMapping("/{tenantId}/sentry")
    public ResponseEntity<Void> sentryWebhook(
            @PathVariable UUID tenantId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Sentry-Signature", required = false) String sig) {
        
        logger.info("Received Sentry webhook for tenant: {}", tenantId);
        
        // In a full implementation, we'd verify the HMAC signature here
        // signatureVerifier.verify(payload, sig);

        dedupService.ingest(tenantId, payload);
        
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/github")
    public ResponseEntity<Void> githubWebhook(
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "00000000-0000-0000-0000-000000000000") UUID tenantId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String githubEvent,
            @RequestBody Map<String, Object> payload) {
        
        logger.info("Received GitHub webhook for tenant: {} (Event: {})", tenantId, githubEvent);
        
        // Parse GitHub Actions workflow_run event
        if ("workflow_run".equals(githubEvent)) {
            String action = (String) payload.get("action");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> workflowRun = (Map<String, Object>) payload.get("workflow_run");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> repository = (Map<String, Object>) payload.get("repository");
            
            if ("completed".equals(action) && workflowRun != null && repository != null) {
                String conclusion = (String) workflowRun.get("conclusion");
                
                if ("failure".equals(conclusion)) {
                    String repoName = (String) repository.get("full_name");
                    Long runIdLong = ((Number) workflowRun.get("id")).longValue();
                    String runId = String.valueOf(runIdLong);
                    
                    logger.info("Parsed failed workflow_run for repository: {} (Run ID: {})", repoName, runId);
                    
                    // Create an internal payload for dedupService using the extracted real data
                    Map<String, Object> internalPayload = Map.of(
                        "repo", "https://github.com/" + repoName,
                        "error_id", "run-" + runId,
                        "stacktrace", "GitHub Actions Run Failed: " + runId // We'll fetch real logs in the next phase
                    );
                    
                    dedupService.ingest(tenantId, internalPayload);
                    return ResponseEntity.accepted().build();
                } else {
                    logger.info("Ignoring successful workflow_run.");
                }
            }
        } else {
            // Fallback for custom mocked payload or other events
            dedupService.ingest(tenantId, payload);
        }
        
        return ResponseEntity.accepted().build();
    }
}
