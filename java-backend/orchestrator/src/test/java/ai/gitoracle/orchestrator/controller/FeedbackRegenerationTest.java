package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.core.model.postgres.JobEvent;
import ai.gitoracle.orchestrator.dto.Requests;
import ai.gitoracle.orchestrator.repository.JobEventRepository;
import ai.gitoracle.orchestrator.security.TestTenantContext;
import ai.gitoracle.orchestrator.service.JobStateService;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * "Regenerate" from the dashboard. It used to start a second fix cycle on a job
 * that was still being fixed, and the new cycle forgot the branch and PR
 * repository the first one used.
 */
class FeedbackRegenerationTest {

    private static final UUID TENANT = UUID.randomUUID();

    private EntityManager entityManager;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private JobEventRepository events;
    private DashboardController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entityManager = mock(EntityManager.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        events = mock(JobEventRepository.class);
        JobStateService jobState = new JobStateService(ai.gitoracle.orchestrator.TestJobRepository.create(), events, Clock.systemUTC());
        controller = new DashboardController(entityManager, kafkaTemplate, null, null, jobState, events,
            mock(ai.gitoracle.orchestrator.service.RepoAccessService.class),
            mock(ai.gitoracle.orchestrator.service.UsageLimits.class));
        TestTenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TestTenantContext.clear();
    }

    private AgentJob job(String state, String prUrl) {
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setTenantId(TENANT);
        job.setState(state);
        job.setPrUrl(prUrl);
        job.setRepo("https://github.com/bettercallok/chillcall");
        job.setErrorId("dashboard-trigger-1");
        when(entityManager.find(AgentJob.class, job.getId())).thenReturn(job);
        return job;
    }

    @Test
    void aJobStillBeingWorkedOnIsRefused() {
        for (String state : List.of("QUEUED", "INVESTIGATING", "PLANNING", "FIXING", "TESTING", "REGENERATING")) {
            AgentJob job = job(state, null);

            var response = controller.submitFeedback(job.getId(), new Requests.Feedback("try again"));

            assertThat(response.getStatusCode().value()).as(state).isEqualTo(409);
            assertThat(job.getState()).isEqualTo(state);
        }
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRegenerationKeepsTheBranchAndGoesToThePullRequestsRepository() {
        AgentJob job = job("PR_OPENED", "https://github.com/someone/fork/pull/4");
        JobEvent created = new JobEvent();
        created.setDetails("{\"request\":{\"branch\":\"case/npe\",\"target_repo\":\"other/target\"}}");
        when(events.findByJobIdOrderByCreatedAtAsc(job.getId())).thenReturn(List.of(created));

        var response = controller.submitFeedback(job.getId(), new Requests.Feedback("handle null too"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(job.getState()).isEqualTo("REGENERATING");
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.JOB_FIX), payload.capture());
        assertThat((Map<String, Object>) payload.getValue())
            .containsEntry("branch", "case/npe")
            .containsEntry("target_repo", "someone/fork")        // the PR's own repository wins
            .containsEntry("tenant_id", TENANT.toString())
            .containsEntry("human_instructions", "handle null too");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedJobWithNoPullRequestUsesTheTargetItWasAskedFor() {
        AgentJob job = job("FAILED", null);
        JobEvent created = new JobEvent();
        created.setDetails("{\"request\":{\"target_repo\":\"other/target\"}}");
        when(events.findByJobIdOrderByCreatedAtAsc(job.getId())).thenReturn(List.of(created));

        controller.submitFeedback(job.getId(), new Requests.Feedback("try a smaller change"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.JOB_FIX), payload.capture());
        assertThat((Map<String, Object>) payload.getValue())
            .containsEntry("branch", "")
            .containsEntry("target_repo", "other/target");
    }
}
