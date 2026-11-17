package ai.gitoracle.ingestor.service;

import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.core.model.postgres.AgentJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SemanticDedupService {

    private static final Logger logger = LoggerFactory.getLogger(SemanticDedupService.class);
    private final EntityManager entityManager;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SemanticDedupService(EntityManager entityManager, KafkaTemplate<String, Object> kafkaTemplate) {
        this.entityManager = entityManager;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public void ingest(UUID tenantId, Map<String, Object> payload) {
        // No repository means there is nothing to clone or fix. This used to default
        // to "unknown/repo", so any payload without one became a job that could only
        // fail. A Sentry-style report may omit error_id, so that default stays.
        if (!(payload.get("repo") instanceof String repo) || repo.isBlank()) {
            logger.warn("Ignoring report for tenant {} with no repository.", tenantId);
            return;
        }
        String errorId = payload.get("error_id") instanceof String id && !id.isBlank()
            ? id : "err-" + UUID.randomUUID().toString().substring(0, 8);
        String stackTrace = payload.get("stacktrace") instanceof String st ? st : "";

        // Deduplicate by errorId: exact match, not semantic. Similarity dedup would
        // need embeddings (EMBEDDINGS_BASE_URL, as agent memory uses) and a vector
        // column on agent_job; neither exists yet.
        try {
            logger.info("Deduplicating error in repo: {}", repo);

            // Scoped to the tenant. Without the tenant predicate this deduplicated
            // across tenant boundaries: because error_id is caller-supplied, one
            // tenant reporting an error whose id happened to match another's
            // would have its report silently discarded as a "duplicate" — a
            // cross-tenant denial of service, and a way to probe whether an
            // arbitrary error id already exists somewhere in the installation.
            List<AgentJob> existing = entityManager.createQuery(
                    "SELECT j FROM AgentJob j WHERE j.errorId = :errorId AND j.tenantId = :tenantId", AgentJob.class)
                .setParameter("errorId", errorId)
                .setParameter("tenantId", tenantId)
                .getResultList();

            if (!existing.isEmpty()) {
                logger.info("Duplicate error found (id={}). Skipping ingestion.", errorId);
                return;
            }

            // 3. Persist new job
            AgentJob job = new AgentJob();
            job.setTenantId(tenantId);
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
}
