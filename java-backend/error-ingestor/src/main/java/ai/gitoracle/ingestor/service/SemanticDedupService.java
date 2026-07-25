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

        // 1. Get embedding for the stack trace (mocked via dummy vector for now)
        String embeddingStr = generateEmbedding(stackTrace);

        // 2. Perform semantic deduplication using pgvector
        // Check if there's a recent job with cosine similarity > 0.92
        try {
            // Note: In a production pgvector setup, we would cast the string to a vector type:
            // "SELECT j FROM AgentJob j WHERE j.tenantId = :tenantId AND j.errorEmbedding <=> CAST(:embedding AS vector) < 0.08"
            // Since errorEmbedding is not fully typed as a vector object in JPA, we will simulate the dedup logic here.
            
            logger.info("Performing semantic deduplication for error in repo: {}", repo);
            
            // For now, simple strict string check to avoid complex custom JPA dialects
            List<AgentJob> existing = entityManager.createQuery("SELECT j FROM AgentJob j WHERE j.errorId = :errorId", AgentJob.class)
                .setParameter("errorId", errorId)
                .getResultList();

            if (!existing.isEmpty()) {
                logger.info("Duplicate error found (id={}). Skipping ingestion.", errorId);
                return;
            }

            // 3. Persist new job
            AgentJob job = new AgentJob();
            job.setId(UUID.randomUUID());
            job.setRepo(repo);
            job.setState("QUEUED");
            job.setErrorId(errorId);
            job.setCreatedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            
            entityManager.persist(job);
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
