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
            @RequestBody Map<String, Object> payload) {
        
        logger.info("Received GitHub webhook for tenant: {}", tenantId);
        
        // Pass payload to dedup service, simulating GitHub Actions error
        dedupService.ingest(tenantId, payload);
        
        return ResponseEntity.accepted().build();
    }
}
