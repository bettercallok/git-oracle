package ai.gitoracle.gateway.controller;

import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.core.kafka.event.ErrorIngestedEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhook")
public class GitHubWebhookController {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public GitHubWebhookController(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping("/github/{tenantId}")
    public ResponseEntity<Void> receiveWebhook(
            @PathVariable UUID tenantId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestBody String payload) {

        // Note: In production, verify the HMAC signature here.

        // Drop event to Kafka
        ErrorIngestedEvent event = ErrorIngestedEvent.builder()
                .tenantId(tenantId)
                .errorId(UUID.randomUUID().toString()) // Mock ID for now
                .errorType(eventType != null ? eventType : "unknown_event")
                .rawPayload(payload)
                .build();

        kafkaTemplate.send(KafkaTopics.ERROR_INGESTED, event);

        // Return 202 Accepted immediately
        return ResponseEntity.accepted().build();
    }
}
