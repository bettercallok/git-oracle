package ai.gitoracle.ingestor.service;

import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.core.model.postgres.AgentJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;

@Service
public class SemanticDedupService {

    private static final Logger logger = LoggerFactory.getLogger(SemanticDedupService.class);
    private final EntityManager entityManager;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public SemanticDedupService(EntityManager entityManager, KafkaTemplate<String, Object> kafkaTemplate) {
        this.entityManager = entityManager;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public void ingest(UUID tenantId, Map<String, Object> payload) {
        String repo = (String) payload.getOrDefault("repo", "unknown/repo");
        String errorId = (String) payload.getOrDefault("error_id", "err-" + UUID.randomUUID().toString().substring(0, 8));
        String stackTrace = (String) payload.getOrDefault("stacktrace", "");

        // Deduplicate by errorId. NOTE: this is exact-match dedup, not semantic —
        // vector-similarity dedup requires an embedding service (not currently deployed)
        // plus a pgvector column on agent_job; see generateEmbedding() below for the
        // intended embedding call to re-enable once that infra exists.
        try {
            logger.info("Deduplicating error in repo: {}", repo);

            List<AgentJob> existing = entityManager.createQuery("SELECT j FROM AgentJob j WHERE j.errorId = :errorId", AgentJob.class)
                .setParameter("errorId", errorId)
                .getResultList();

            if (!existing.isEmpty()) {
                logger.info("Duplicate error found (id={}). Skipping ingestion.", errorId);
                return;
            }

            // 3. Persist new job
            AgentJob job = new AgentJob();
            job.setTenant(entityManager.getReference(ai.gitoracle.core.model.postgres.Tenant.class, tenantId));
            job.setRepo(repo);
            job.setState("QUEUED");
            job.setErrorId(errorId);
            
            entityManager.persist(job);
            entityManager.flush(); // Flush to ensure ID is generated before using it in the event
            logger.info("Saved new job {} for error {}", job.getId(), errorId);

            // 4. Publish to Kafka
            ai.gitoracle.core.kafka.event.ErrorIngestedEvent event = ai.gitoracle.core.kafka.event.ErrorIngestedEvent.builder()
                .jobId(job.getId())
                .tenantId(tenantId)
                .repoUrl(repo)
                .errorId(errorId)
                .errorType("RUNTIME_EXCEPTION")
                .rawPayload(stackTrace)
                .build();
            
            kafkaTemplate.send(KafkaTopics.ERROR_INGESTED, event);
            logger.info("Published to {} for job {}", KafkaTopics.ERROR_INGESTED, job.getId());

        } catch (Exception e) {
            logger.error("Failed to ingest error", e);
        }
    }

    private String generateEmbedding(String text) {
        try {
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            java.util.Map<String, String> request = java.util.Map.of(
                "model", "all-minilm",
                "prompt", text != null ? text : ""
            );
            
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> response = restTemplate.postForObject(
                "http://localhost:11434/api/embeddings", 
                request, 
                java.util.Map.class
            );
            
            if (response != null && response.containsKey("embedding")) {
                Object embeddingObj = response.get("embedding");
                return mapper.writeValueAsString(embeddingObj);
            }
        } catch (Exception e) {
            logger.error("Failed to fetch real embedding from LLM server, falling back to zeros", e);
        }
        
        // Fallback to zeros if LLM server is unreachable
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 384; i++) {
            sb.append("0.0");
            if (i < 383) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
