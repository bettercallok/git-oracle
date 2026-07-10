package ai.gitoracle.orchestrator.token;

import ai.gitoracle.core.kafka.KafkaTopics;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Token Budget Manager.
 *
 * Atomically increments token usage for a job.
 * Publishes Kafka events when the 80% warning threshold is crossed
 * and when the 100% hard cap is exceeded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenBudgetManager {

    private static final double WARN_THRESHOLD = 0.80;

    private final AgentJobRepository jobRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Record tokens consumed by an agent for a given job.
     * This is an atomic SQL update — safe for concurrent agents.
     *
     * @param jobId      the job UUID
     * @param tokensUsed tokens consumed in this LLM call
     * @return current total token usage after this call
     */
    @Transactional
    public int recordUsage(UUID jobId, int tokensUsed) {
        // Atomic: UPDATE agent_job SET token_budget_used = token_budget_used + ? WHERE id = ?
        int updated = jobRepository.incrementTokens(jobId, tokensUsed);
        if (updated == 0) {
            log.warn("recordUsage: job {} not found, skipping budget tracking", jobId);
            return 0;
        }

        int totalUsed = jobRepository.getTokenBudgetUsed(jobId);
        int budget    = jobRepository.getTokenBudgetLimit(jobId);

        log.info("Job {} — tokens used: {}/{}", jobId, totalUsed, budget);

        double utilisation = (double) totalUsed / budget;

        if (utilisation >= 1.0) {
            log.warn("Job {} has EXCEEDED token budget ({}/{}). Escalating.", jobId, totalUsed, budget);
            kafkaTemplate.send(KafkaTopics.JOB_ESCALATED, jobId.toString(), Map.of(
                    "jobId",          jobId.toString(),
                    "reason",         "TOKEN_BUDGET_EXCEEDED",
                    "tokensUsed",     totalUsed,
                    "tokenBudget",    budget
            ));
        } else if (utilisation >= WARN_THRESHOLD) {
            log.warn("Job {} has consumed {}% of token budget.", jobId, (int)(utilisation * 100));
            kafkaTemplate.send(KafkaTopics.AUDIT_EVENT, jobId.toString(), Map.of(
                    "eventType",      "BUDGET_WARNING",
                    "jobId",          jobId.toString(),
                    "tokensUsed",     totalUsed,
                    "tokenBudget",    budget,
                    "utilisation",    utilisation
            ));
        }

        return totalUsed;
    }
}
