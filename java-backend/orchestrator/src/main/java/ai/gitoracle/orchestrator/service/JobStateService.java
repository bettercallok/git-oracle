package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.core.model.postgres.JobEvent;
import ai.gitoracle.orchestrator.repository.JobEventRepository;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only place a job's state changes.
 *
 * <p>State used to be set ad hoc with {@code job.setState(...)} in a dozen
 * places across OrchestratorService and DashboardController, with no record of
 * when or why. Three concrete problems followed:
 * <ul>
 *   <li>Nothing could tell how long a job had been in its state, so a job whose
 *       agent had died looked exactly like one that was still working.</li>
 *   <li>Failures carried no reason — FAILED meant "guardrails rejected it", "the
 *       guardrails service was down" or "the tests failed", indistinguishably.</li>
 *   <li>A late {@code job-escalated} event overwrote a job that had already
 *       opened its PR, the same class of bug escalateUnlessAlreadySucceeded
 *       guards for PR creation only.</li>
 * </ul>
 * Every change now goes through {@link #transition}, which refuses to leave a
 * finished job, stamps {@code stateChangedAt}, stores the reason, and appends a
 * {@link JobEvent} to the job's timeline.
 */
@Service
public class JobStateService {

    private static final Logger logger = LoggerFactory.getLogger(JobStateService.class);

    /** States a job does not leave on its own. */
    public static final Set<String> TERMINAL = Set.of("PR_OPENED", "FAILED");

    /**
     * The one way back out of a terminal state: a human asked for another fix
     * cycle (a PR review comment, or the dashboard's "regenerate").
     */
    private static final String REENTRY = "REGENERATING";

    private static final int MAX_REASON_CHARS = 4000;

    private final AgentJobRepository jobRepository;
    private final JobEventRepository eventRepository;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JobStateService(AgentJobRepository jobRepository, JobEventRepository eventRepository, Clock clock) {
        this.jobRepository = jobRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    /**
     * Moves {@code job} to {@code to}. Returns false (and changes nothing) if the
     * job is already there, or is finished and this isn't a {@code REGENERATING}
     * re-entry.
     */
    public boolean transition(AgentJob job, String to, String reason, String actor) {
        return transitionFrom(job, null, to, reason, actor);
    }

    /**
     * Like {@link #transition}, but only if the job is currently in one of
     * {@code allowedFrom}. For listeners that can see events out of order — e.g.
     * the job.events.fix snoop must not drag a job that is already TESTING back
     * to FIXING if its message is processed late.
     */
    public boolean transitionFrom(AgentJob job, Set<String> allowedFrom, String to, String reason, String actor) {
        String from = job.getState();
        if (to.equals(from)) {
            return false;
        }
        if (from != null && TERMINAL.contains(from) && !REENTRY.equals(to)) {
            logger.warn("Job {} is {} — refusing to move it to {} ({}: {})",
                        job.getId(), from, to, actor, reason);
            return false;
        }
        if (allowedFrom != null && (from == null || !allowedFrom.contains(from))) {
            logger.info("Job {} is {} — skipping {} -> {} ({}): not one of {}",
                        job.getId(), from, from, to, actor, allowedFrom);
            return false;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        job.setState(to);
        job.setStateReason(truncate(reason));
        job.setStateChangedAt(now);
        jobRepository.save(job);

        JobEvent event = newEvent(job.getId(), job.getTenantId(), JobEvent.Type.STATE_CHANGE, actor, reason, null);
        event.setFromState(from);
        event.setToState(to);
        event.setCreatedAt(now);
        eventRepository.save(event);

        logger.info("Job {}: {} -> {} ({}){}", job.getId(), from, to, actor,
                    reason == null || reason.isBlank() ? "" : " — " + reason);
        return true;
    }

    /**
     * Opens the timeline of a job that was just persisted in its initial state
     * (which callers set directly — {@code state} is NOT NULL, so a row can't be
     * saved before it has one).
     */
    public void recordCreated(AgentJob job, String reason, String actor) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        job.setStateChangedAt(now);
        job.setStateReason(truncate(reason));
        jobRepository.save(job);

        JobEvent event = newEvent(job.getId(), job.getTenantId(), JobEvent.Type.STATE_CHANGE, actor, reason, null);
        event.setToState(job.getState());
        event.setCreatedAt(now);
        eventRepository.save(event);
    }

    /** Appends a non-state entry (agent progress, an error) to the job's timeline. */
    public JobEvent record(AgentJob job, JobEvent.Type type, String actor, String message, Map<String, Object> details) {
        JobEvent event = newEvent(job.getId(), job.getTenantId(), type, actor, message, details);
        event.setCreatedAt(OffsetDateTime.now(clock));
        return eventRepository.save(event);
    }

    private JobEvent newEvent(UUID jobId, UUID tenantId, JobEvent.Type type, String actor,
                              String message, Map<String, Object> details) {
        JobEvent event = new JobEvent();
        event.setJobId(jobId);
        event.setTenantId(tenantId);
        event.setType(type);
        event.setActor(actor);
        event.setMessage(truncate(message));
        if (details != null && !details.isEmpty()) {
            try {
                event.setDetails(objectMapper.writeValueAsString(details));
            } catch (Exception e) {
                // Details are a convenience for the timeline; never lose the event over them.
                logger.warn("Dropping unserialisable details on job {} event: {}", jobId, e.getMessage());
            }
        }
        return event;
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= MAX_REASON_CHARS) return s;
        return s.substring(0, MAX_REASON_CHARS) + "…";
    }
}
