package ai.gitoracle.orchestrator;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.core.model.postgres.JobEvent;
import ai.gitoracle.orchestrator.repository.JobEventRepository;
import ai.gitoracle.orchestrator.service.JobStateService;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * JobStateService is the only place job state changes, so its rules are the
 * pipeline's state machine: what a finished job may still become, and what
 * every change leaves behind for the timeline and the watchdog.
 */
class JobStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    private AgentJobRepository jobRepository;
    private JobEventRepository eventRepository;
    private JobStateService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(AgentJobRepository.class);
        eventRepository = mock(JobEventRepository.class);
        service = new JobStateService(jobRepository, eventRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AgentJob job(String state) {
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setTenantId(UUID.randomUUID());
        job.setState(state);
        return job;
    }

    @Test
    void aTransitionStampsTheJobAndAppendsATimelineEvent() {
        AgentJob job = job("TESTING");

        boolean moved = service.transition(job, "FAILED", "Tests failed", "test-runner");

        assertThat(moved).isTrue();
        assertThat(job.getState()).isEqualTo("FAILED");
        assertThat(job.getStateReason()).isEqualTo("Tests failed");
        assertThat(job.getStateChangedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(jobRepository).save(job);

        ArgumentCaptor<JobEvent> event = ArgumentCaptor.forClass(JobEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getType()).isEqualTo(JobEvent.Type.STATE_CHANGE);
        assertThat(event.getValue().getFromState()).isEqualTo("TESTING");
        assertThat(event.getValue().getToState()).isEqualTo("FAILED");
        assertThat(event.getValue().getActor()).isEqualTo("test-runner");
        assertThat(event.getValue().getJobId()).isEqualTo(job.getId());
        assertThat(event.getValue().getTenantId()).isEqualTo(job.getTenantId());
    }

    @Test
    void aFinishedJobIsNeverMovedToAnythingButRegenerating() {
        for (String terminal : JobStateService.TERMINAL) {
            AgentJob job = job(terminal);

            assertThat(service.transition(job, "ESCALATED", "late", "agent")).isFalse();
            assertThat(service.transition(job, "TESTING", "late", "fixer")).isFalse();
            assertThat(job.getState()).isEqualTo(terminal);
        }
        verify(eventRepository, never()).save(any());
    }

    @Test
    void aHumanCanStillAskForAnotherFixCycleOnAFinishedJob() {
        AgentJob job = job("PR_OPENED");

        assertThat(service.transition(job, "REGENERATING", "review comment", "reviewer")).isTrue();
        assertThat(job.getState()).isEqualTo("REGENERATING");
    }

    @Test
    void movingToTheCurrentStateIsANoOp() {
        AgentJob job = job("PLANNING");

        assertThat(service.transition(job, "PLANNING", "again", "dashboard")).isFalse();
        verify(eventRepository, never()).save(any());
    }

    @Test
    void transitionFromOnlyMovesFromTheListedStates() {
        AgentJob testing = job("TESTING");
        AgentJob planning = job("PLANNING");
        Set<String> beforeFixing = Set.of("QUEUED", "INVESTIGATING", "PLANNING", "REGENERATING");

        assertThat(service.transitionFrom(testing, beforeFixing, "FIXING", "late message", "planner")).isFalse();
        assertThat(testing.getState()).isEqualTo("TESTING");

        assertThat(service.transitionFrom(planning, beforeFixing, "FIXING", "plan ready", "planner")).isTrue();
        assertThat(planning.getState()).isEqualTo("FIXING");
    }

    @Test
    void veryLongReasonsAreTruncatedRatherThanFailingTheWrite() {
        AgentJob job = job("TESTING");

        service.transition(job, "FAILED", "x".repeat(10_000), "test-runner");

        assertThat(job.getStateReason()).hasSizeLessThanOrEqualTo(4001);
    }

    @Test
    void agentProgressIsRecordedWithoutTouchingState() {
        AgentJob job = job("FIXING");

        service.record(job, JobEvent.Type.AGENT_PROGRESS, "fixer", "Attempt 2 started", Map.of("attempt", 2));

        assertThat(job.getState()).isEqualTo("FIXING");
        ArgumentCaptor<JobEvent> event = ArgumentCaptor.forClass(JobEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getType()).isEqualTo(JobEvent.Type.AGENT_PROGRESS);
        assertThat(event.getValue().getDetails()).contains("\"attempt\":2");
        verify(jobRepository, never()).save(any());
    }

    @Test
    void anOpenPrCanBeMergedOrClosedAndAClosedOneCanStillBeMerged() {
        AgentJob closed = job("PR_OPENED");
        assertThat(service.transition(closed, "PR_CLOSED", "closed by a human", "github")).isTrue();
        assertThat(service.transition(closed, "PR_MERGED", "reopened and merged", "github")).isTrue();

        AgentJob merged = job("PR_OPENED");
        assertThat(service.transition(merged, "PR_MERGED", "merged", "github")).isTrue();
        assertThat(service.transition(merged, "PR_CLOSED", "late", "github")).isFalse();
        assertThat(merged.getState()).isEqualTo("PR_MERGED");
    }

    @Test
    void aFailedJobCannotBeMarkedMergedOrClosed() {
        AgentJob failed = job("FAILED");

        assertThat(service.transition(failed, "PR_MERGED", "?", "github")).isFalse();
        assertThat(service.transition(failed, "PR_CLOSED", "?", "github")).isFalse();
    }
}
