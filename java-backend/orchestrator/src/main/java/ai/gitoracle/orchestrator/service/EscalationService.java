package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.entity.Escalation;
import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.repository.EscalationRepository;
import ai.gitoracle.orchestrator.repository.PrOutcomeRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
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
    private final EscalationRepository escalationRows;

    public EscalationService(JobStateService jobState, EntityManager entityManager, PrOutcomeRepository prOutcomes,
                             EscalationRepository escalationRows) {
        this.jobState = jobState;
        this.entityManager = entityManager;
        this.prOutcomes = prOutcomes;
        this.escalationRows = escalationRows;
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
        // A job already ESCALATED with a pending row needs nothing more. One that
        // is ESCALATED with NO pending row (escalated by a path that skipped this
        // service, or whose row was lost) gets one now — otherwise it shows as
        // escalated everywhere except the queue where a human could act on it.
        boolean alreadyEscalated = "ESCALATED".equals(job.getState());
        if (alreadyEscalated) {
            if (escalationRows.existsByJob_IdAndStatus(job.getId(), "PENDING")) {
                return false;
            }
        } else if (!jobState.transition(job, "ESCALATED", reason, actor)) {
            return false;
        }

        // One pending escalation per job: a row left PENDING from an earlier
        // escalation the pipeline later moved past would otherwise be listed
        // (and counted) alongside this one.
        escalationRows.supersedePending(job.getId(), OffsetDateTime.now());

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
     * Gives every ESCALATED job without a pending escalation one, at startup.
     *
     * <p>Until this release a failed PR creation moved the job to ESCALATED
     * directly, creating no row: those jobs were counted as escalated on the
     * jobs page but missing from the escalation queue and its sidebar count,
     * with no way to approve or reject them. The row carries the reason the job
     * was escalated with. Idempotent: a job that already has one is skipped.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @org.springframework.transaction.annotation.Transactional
    public void backfillMissingEscalations() {
        int created = 0;
        for (UUID jobId : escalationRows.escalatedJobsWithoutPendingEscalation()) {
            AgentJob job = entityManager.find(AgentJob.class, jobId);
            if (job == null) continue;
            String reason = job.getStateReason() != null ? job.getStateReason() : "Escalated (reason not recorded)";
            if (escalate(job, reason, "orchestrator", null)) created++;
        }
        if (created > 0) {
            logger.warn("Created {} missing escalation(s) for jobs that were ESCALATED with nothing in the queue.", created);
        }
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
