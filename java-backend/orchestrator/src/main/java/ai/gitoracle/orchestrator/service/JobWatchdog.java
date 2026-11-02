package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.entity.Escalation;
import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Escalates jobs that have stopped moving.
 *
 * <p>Nothing used to notice a job nobody was working on any more. When the
 * investigator's Kafka consumer died in the first CI eval run, both jobs sat in
 * INVESTIGATING until the harness gave up; in production they would have sat
 * there forever, invisible in the Escalation Queue. A job past its state's limit
 * (see {@link WatchdogProperties}) is now moved to ESCALATED with a reason that
 * says where it stalled and for how long, gets an Escalation row a human can act
 * on, and bumps {@code gitoracle_watchdog_escalations_total} for alerting.
 *
 * <p>Jobs with no {@code stateChangedAt} (created before it existed) are left
 * alone rather than mass-escalated on the first run after an upgrade.
 */
@Component
@ConditionalOnProperty(prefix = "gitoracle.watchdog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobWatchdog {

    private static final Logger logger = LoggerFactory.getLogger(JobWatchdog.class);

    private final AgentJobRepository jobRepository;
    private final JobStateService jobState;
    private final EntityManager entityManager;
    private final WatchdogProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public JobWatchdog(AgentJobRepository jobRepository, JobStateService jobState, EntityManager entityManager,
                       WatchdogProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.jobRepository = jobRepository;
        this.jobState = jobState;
        this.entityManager = entityManager;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${gitoracle.watchdog.interval:PT1M}",
               initialDelayString = "${gitoracle.watchdog.interval:PT1M}")
    @Transactional
    public void escalateStalledJobs() {
        var limits = properties.getLimits();
        if (limits.isEmpty()) return;

        OffsetDateTime now = OffsetDateTime.now(clock);
        Duration shortest = limits.values().stream().min(Duration::compareTo).orElseThrow();

        // Narrow in SQL by the shortest limit, then apply each job's own limit.
        for (AgentJob job : jobRepository.findByStateInAndStateChangedAtBefore(limits.keySet(), now.minus(shortest))) {
            Duration limit = limits.get(job.getState());
            if (limit == null || job.getStateChangedAt() == null) continue;
            Duration stuck = Duration.between(job.getStateChangedAt(), now);
            if (stuck.compareTo(limit) <= 0) continue;

            String state = job.getState();
            String reason = String.format("Stalled in %s for %s (limit %s) — no agent reported progress",
                                          state, human(stuck), human(limit));
            if (!jobState.transition(job, "ESCALATED", reason, "watchdog")) continue;

            Escalation escalation = new Escalation();
            escalation.setId(UUID.randomUUID());
            escalation.setJob(job);
            escalation.setReason(reason);
            escalation.setConfidenceScore(0.0);
            escalation.setStatus("PENDING");
            entityManager.persist(escalation);

            meterRegistry.counter("gitoracle.watchdog.escalations", "state", state).increment();
            logger.warn("Watchdog escalated job {}: {}", job.getId(), reason);
        }
    }

    private static String human(Duration d) {
        long minutes = d.toMinutes();
        return minutes >= 1 ? minutes + "m" : d.toSeconds() + "s";
    }
}
