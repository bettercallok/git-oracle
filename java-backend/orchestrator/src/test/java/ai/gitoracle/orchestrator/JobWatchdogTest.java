package ai.gitoracle.orchestrator;

import ai.gitoracle.core.entity.Escalation;
import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.repository.JobEventRepository;
import ai.gitoracle.orchestrator.service.EscalationService;
import ai.gitoracle.orchestrator.service.JobStateService;
import ai.gitoracle.orchestrator.service.JobWatchdog;
import ai.gitoracle.orchestrator.service.WatchdogProperties;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The watchdog is what turns "a job nothing is working on" from an invisible,
 * permanent hang (the first CI eval run) into an escalation with a reason.
 */
class JobWatchdogTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    private AgentJobRepository jobRepository;
    private EntityManager entityManager;
    private SimpleMeterRegistry meters;
    private JobWatchdog watchdog;

    @BeforeEach
    void setUp() {
        jobRepository = mock(AgentJobRepository.class);
        entityManager = mock(EntityManager.class);
        meters = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        JobStateService jobState = new JobStateService(jobRepository, mock(JobEventRepository.class), clock);
        watchdog = new JobWatchdog(jobRepository, new EscalationService(jobState, entityManager,
                                       mock(ai.gitoracle.orchestrator.repository.PrOutcomeRepository.class),
                                       mock(ai.gitoracle.orchestrator.repository.EscalationRepository.class)),
                                   new WatchdogProperties(), meters, clock);
    }

    private AgentJob job(String state, Duration ago) {
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setState(state);
        job.setStateChangedAt(ago == null ? null : OffsetDateTime.ofInstant(NOW.minus(ago), ZoneOffset.UTC));
        return job;
    }

    private void candidates(AgentJob... jobs) {
        when(jobRepository.findByStateInAndStateChangedAtBefore(anyCollection(), any())).thenReturn(List.of(jobs));
    }

    @Test
    void aJobPastItsStatesLimitIsEscalatedWithAReasonAndAnEscalationRow() {
        AgentJob stuck = job("INVESTIGATING", Duration.ofMinutes(12));   // limit 10m
        candidates(stuck);

        watchdog.escalateStalledJobs();

        assertThat(stuck.getState()).isEqualTo("ESCALATED");
        assertThat(stuck.getStateReason()).isEqualTo(
            "Stalled in INVESTIGATING for 12m (limit 10m) — no agent reported progress");
        ArgumentCaptor<Escalation> escalation = ArgumentCaptor.forClass(Escalation.class);
        verify(entityManager).persist(escalation.capture());
        assertThat(escalation.getValue().getJob()).isSameAs(stuck);
        assertThat(escalation.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(meters.counter("gitoracle.watchdog.escalations", "state", "INVESTIGATING").count()).isEqualTo(1.0);
    }

    @Test
    void eachStateHasItsOwnLimit() {
        // 12 minutes is past PLANNING's 5m limit but within FIXING's 20m.
        AgentJob planning = job("PLANNING", Duration.ofMinutes(12));
        AgentJob fixing = job("FIXING", Duration.ofMinutes(12));
        candidates(planning, fixing);

        watchdog.escalateStalledJobs();

        assertThat(planning.getState()).isEqualTo("ESCALATED");
        assertThat(fixing.getState()).isEqualTo("FIXING");
    }

    @Test
    void jobsFromBeforeStateChangedAtExistedAreLeftAlone() {
        AgentJob legacy = job("INVESTIGATING", null);
        candidates(legacy);

        watchdog.escalateStalledJobs();

        assertThat(legacy.getState()).isEqualTo("INVESTIGATING");
        verify(entityManager, never()).persist(any());
    }

    @Test
    void statesWithNoLimitAreNeverTouched() {
        // ESCALATED (waiting for a human) has no limit; the repository query is
        // scoped to limited states, but the watchdog must not rely on that alone.
        AgentJob escalated = job("ESCALATED", Duration.ofDays(3));
        candidates(escalated);

        watchdog.escalateStalledJobs();

        verify(entityManager, never()).persist(any());
    }

    @Test
    void aStalledRegenerationOfAJobWithAnOpenPrGoesBackToPrOpenedNotTheQueue() {
        AgentJob regenerating = job("FIXING", Duration.ofMinutes(25));   // limit 20m
        regenerating.setPrUrl("https://github.com/bettercallok/chillcall/pull/18");
        candidates(regenerating);

        watchdog.escalateStalledJobs();

        assertThat(regenerating.getState()).isEqualTo("PR_OPENED");
        verify(entityManager, never()).persist(any());
        assertThat(meters.find("gitoracle.watchdog.escalations").counter()).isNull();
    }

    @Test
    void aWatchdogEscalationHasNoConfidenceScore() {
        candidates(job("INVESTIGATING", Duration.ofMinutes(12)));

        watchdog.escalateStalledJobs();

        ArgumentCaptor<Escalation> escalation = ArgumentCaptor.forClass(Escalation.class);
        verify(entityManager).persist(escalation.capture());
        assertThat(escalation.getValue().getConfidenceScore()).isNull();
    }
}
