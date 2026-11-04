package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.entity.Escalation;
import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.dto.Requests;
import ai.gitoracle.orchestrator.repository.JobEventRepository;
import ai.gitoracle.orchestrator.security.TestTenantContext;
import ai.gitoracle.orchestrator.service.JobStateService;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resolving an escalation must happen once. Confirmed live on job 9e09ccf6:
 * resolved escalations stayed in the queue, Approve ran again on every click,
 * and five clicks in twelve seconds started five concurrent pipeline runs.
 */
class EscalationResolveTest {

    private static final UUID TENANT = UUID.randomUUID();

    private EntityManager entityManager;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private Query claimUpdate;
    private DashboardController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entityManager = mock(EntityManager.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        claimUpdate = mock(Query.class);
        when(entityManager.createQuery(anyString())).thenReturn(claimUpdate);
        when(claimUpdate.setParameter(anyString(), any())).thenReturn(claimUpdate);
        when(claimUpdate.executeUpdate()).thenReturn(1);

        JobStateService jobState = new JobStateService(mock(AgentJobRepository.class), mock(JobEventRepository.class),
            Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC));
        controller = new DashboardController(entityManager, kafkaTemplate, null, null, jobState,
                                             mock(JobEventRepository.class),
                                             mock(ai.gitoracle.orchestrator.service.RepoAccessService.class));
        TestTenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TestTenantContext.clear();
    }

    private Escalation escalation(String status, String jobState, String prUrl) {
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setTenantId(TENANT);
        job.setState(jobState);
        job.setPrUrl(prUrl);
        job.setRepo("https://github.com/bettercallok/chillcall");
        Escalation escalation = new Escalation();
        escalation.setId(UUID.randomUUID());
        escalation.setJob(job);
        escalation.setStatus(status);
        escalation.setReason("Fixer Agent exhausted all attempts");
        when(entityManager.find(Escalation.class, escalation.getId())).thenReturn(escalation);
        return escalation;
    }

    private ResponseEntity<Map<String, String>> resolve(Escalation escalation, String action) {
        return controller.resolveEscalation(escalation.getId(), new Requests.ResolveEscalation(action));
    }

    @Test
    void approvingAPendingEscalationResumesTheJobOnce() {
        Escalation escalation = escalation("PENDING", "ESCALATED", null);

        ResponseEntity<Map<String, String>> response = resolve(escalation, "approve");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(escalation.getJob().getState()).isEqualTo("PLANNING");
        verify(claimUpdate).setParameter("status", "APPROVED");
        verify(kafkaTemplate).send(eq(KafkaTopics.JOB_PLAN), any());
    }

    @Test
    void anAlreadyResolvedEscalationIsAConflictAndDispatchesNothing() {
        Escalation escalation = escalation("APPROVED", "PLANNING", null);

        ResponseEntity<Map<String, String>> response = resolve(escalation, "approve");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error")).contains("already approved");
        verify(claimUpdate, never()).executeUpdate();
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    void losingTheRaceToClaimItIsAConflictAndDispatchesNothing() {
        // A double click: both requests saw PENDING, the other one claimed it first.
        Escalation escalation = escalation("PENDING", "ESCALATED", null);
        when(claimUpdate.executeUpdate()).thenReturn(0);

        ResponseEntity<Map<String, String>> response = resolve(escalation, "approve");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(escalation.getJob().getState()).isEqualTo("ESCALATED");
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    void approvingAJobThatAlreadyHasAPullRequestIsRefused() {
        Escalation escalation = escalation("PENDING", "ESCALATED", "https://github.com/bettercallok/chillcall/pull/18");

        ResponseEntity<Map<String, String>> response = resolve(escalation, "approve");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error")).contains("pull/18");
        verify(claimUpdate, never()).executeUpdate();
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    void approvingAnEscalationWhoseJobHasMovedOnIsRefused() {
        Escalation escalation = escalation("PENDING", "FAILED", null);

        ResponseEntity<Map<String, String>> response = resolve(escalation, "approve");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    void rejectingFailsAnEscalatedJob() {
        Escalation escalation = escalation("PENDING", "ESCALATED", null);

        ResponseEntity<Map<String, String>> response = resolve(escalation, "reject");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(escalation.getJob().getState()).isEqualTo("FAILED");
        verify(claimUpdate).setParameter("status", "REJECTED");
    }

    @Test
    void rejectingAStaleEscalationClosesItWithoutTouchingTheJob() {
        Escalation escalation = escalation("PENDING", "PLANNING", null);

        ResponseEntity<Map<String, String>> response = resolve(escalation, "reject");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(escalation.getJob().getState()).isEqualTo("PLANNING");
    }

    @Test
    void anotherTenantsEscalationIsNotFound() {
        Escalation escalation = escalation("PENDING", "ESCALATED", null);
        escalation.getJob().setTenantId(UUID.randomUUID());

        ResponseEntity<Map<String, String>> response = resolve(escalation, "approve");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(kafkaTemplate, never()).send(anyString(), any());
    }
}
