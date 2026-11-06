package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.Tenant;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * How much each organisation may use per calendar month (UTC).
 *
 * <p>Every job spends the operator's LLM money and runs on their machine, so limits
 * belong to the platform admin rather than the organisation. An organisation's own
 * values win; otherwise it gets the configured defaults. The default organisation is
 * the operator's own and has no limit unless one is set. Zero on a default means none.
 */
@Service
public class UsageLimits {

    public record Usage(long jobs, Integer jobLimit, long tokens, Long tokenLimit) {}

    private final EntityManager entityManager;
    private final int defaultJobLimit;
    private final long defaultTokenLimit;

    public UsageLimits(EntityManager entityManager,
                       @Value("${gitoracle.limits.monthly-jobs:50}") int defaultJobLimit,
                       @Value("${gitoracle.limits.monthly-tokens:5000000}") long defaultTokenLimit) {
        this.entityManager = entityManager;
        this.defaultJobLimit = defaultJobLimit;
        this.defaultTokenLimit = defaultTokenLimit;
    }

    public Usage usage(UUID tenantId) {
        Tenant tenant = entityManager.find(Tenant.class, tenantId);
        boolean operator = Tenant.DEFAULT_ID.equals(tenantId);
        Integer jobLimit = tenant != null && tenant.getMonthlyJobLimit() != null ? tenant.getMonthlyJobLimit()
            : operator || defaultJobLimit <= 0 ? null : Integer.valueOf(defaultJobLimit);
        Long tokenLimit = tenant != null && tenant.getMonthlyTokenLimit() != null ? tenant.getMonthlyTokenLimit()
            : operator || defaultTokenLimit <= 0 ? null : Long.valueOf(defaultTokenLimit);

        OffsetDateTime monthStart = OffsetDateTime.now(ZoneOffset.UTC)
            .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        Long jobs = entityManager.createQuery(
                "SELECT COUNT(j) FROM AgentJob j WHERE j.tenantId = :tenantId AND j.createdAt >= :since", Long.class)
            .setParameter("tenantId", tenantId).setParameter("since", monthStart).getSingleResult();
        Long tokens = entityManager.createQuery(
                "SELECT COALESCE(SUM(j.tokenBudgetUsed), 0) FROM AgentJob j WHERE j.tenantId = :tenantId AND j.createdAt >= :since", Long.class)
            .setParameter("tenantId", tenantId).setParameter("since", monthStart).getSingleResult();
        return new Usage(jobs, jobLimit, tokens, tokenLimit);
    }

    /**
     * @param newJob true before a job exists; false when it's already been counted,
     *               as it has by the time a webhook's job reaches the orchestrator
     * @return why this organisation can't start a job, or empty if it can
     */
    public Optional<String> refusal(UUID tenantId, boolean newJob) {
        Usage usage = usage(tenantId);
        if (usage.jobLimit() != null && usage.jobs() + (newJob ? 1 : 0) > usage.jobLimit()) {
            return Optional.of("This organisation has used its " + usage.jobLimit() + " jobs for this month.");
        }
        if (usage.tokenLimit() != null && usage.tokens() >= usage.tokenLimit()) {
            return Optional.of("This organisation has used its " + usage.tokenLimit() + " LLM tokens for this month.");
        }
        return Optional.empty();
    }

    /** @throws UsageLimitExceededException if a new job would go over */
    public void requireRoomForNewJob(UUID tenantId) {
        refusal(tenantId, true).ifPresent(reason -> {
            throw new UsageLimitExceededException(reason);
        });
    }
}
