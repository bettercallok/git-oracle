package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.entity.Escalation;
import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.repository.PrOutcomeRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The one way a job gets an Escalation row a human has to act on.
 *
 * <p>Agent failures ({@code job-escalated}) and the {@link JobWatchdog} both
 * escalate, and both used to build the row themselves. Neither handled a job
 * that already has a pull request: the dashboard's "regenerate" moves a
 * PR_OPENED job back into the pipeline, and when that second cycle failed the
 * job was escalated like a fresh failure. Confirmed live on job 9e09ccf6 —
 * approving that escalation re-ran planning and fixing against a job whose
 * correct fix was already open as chillcall#18, and could have opened a second
 * PR for the same bug. A failed regeneration now returns the job to PR_OPENED
 * with the reason on its timeline, and queues nothing.
 */
@Service
public class EscalationService {

    private static final Logger logger = LoggerFactory.getLogger(EscalationService.class);

    private final JobStateService jobState;
    private final EntityManager entityManager;
    private final PrOutcomeRepository prOutcomes;

    public EscalationService(JobStateService jobState, EntityManager entityManager, PrOutcomeRepository prOutcomes) {
        this.jobState = jobState;
        this.entityManager = entityManager;
        this.prOutcomes = prOutcomes;
    }

    /**
     * Escalates {@code job} for human review. Returns true only if an Escalation
     * row was created.
     *
     * @param confidence the reporting agent's confidence, or null when it has
     *                   none — a crash or a rate limit has no meaningful score,
     *                   and a placeholder 0.0 read as "very low confidence"
     */
    public boolean escalate(AgentJob job, String reason, String actor, Double confidence) {
        if (hasPullRequest(job)) {
            // Also covers a late escalation for a job still in its PR state: the
            // transition is a no-op there, and nothing is queued either way.
            if (jobState.transition(job, stateForExistingPr(job),
                    "Regeneration failed — " + reason + " The existing pull request is unchanged: " + job.getPrUrl(),
                    actor)) {
                logger.warn("Job {}: regeneration failed, returned to PR_OPENED instead of escalating: {}",
                            job.getId(), reason);
            }
            return false;
        }
        if (!jobState.transition(job, "ESCALATED", reason, actor)) {
            return false;
        }

        Escalation escalation = new Escalation();
        escalation.setId(UUID.randomUUID());
        escalation.setJob(job);
        escalation.setReason(reason);
        escalation.setConfidenceScore(confidence);
        escalation.setStatus("PENDING");
        entityManager.persist(escalation);
        return true;
    }

    /**
     * The state that describes a job's existing PR: PR_MERGED or PR_CLOSED once
     * that outcome is recorded for this PR URL, else PR_OPENED. What a job with a
     * PR returns to when a later fix cycle (a regeneration) doesn't succeed.
     */
    public String stateForExistingPr(AgentJob job) {
        if (prOutcomes.existsByJobIdAndOutcomeAndPrUrl(job.getId(), "MERGED", job.getPrUrl())) return "PR_MERGED";
        if (prOutcomes.existsByJobIdAndOutcomeAndPrUrl(job.getId(), "CLOSED", job.getPrUrl())) return "PR_CLOSED";
        return "PR_OPENED";
    }

    public static boolean hasPullRequest(AgentJob job) {
        return job.getPrUrl() != null && !job.getPrUrl().isBlank();
    }
}
