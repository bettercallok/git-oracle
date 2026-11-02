package ai.gitoracle.orchestrator;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.repository.JobEventRepository;
import ai.gitoracle.orchestrator.repository.PrOutcomeRepository;
import ai.gitoracle.orchestrator.service.EscalationService;
import ai.gitoracle.orchestrator.service.JobStateService;
import ai.gitoracle.orchestrator.service.WorkspaceService;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Regression tests for OrchestratorService's TESTS_PASSED handling and
 * investigation-to-root-cause promotion.
 *
 * These pin two bugs found live this session:
 *
 *   1. handleTestsPassed was not idempotent against Kafka's at-least-once
 *      redelivery. A duplicate delivery for an already-PR_OPENED job re-ran PR
 *      creation, which failed on the already-pushed branch and then overwrote the
 *      job's PR_OPENED state with ESCALATED — job 7f7f8bd9 held a real merged PR
 *      URL while displaying ESCALATED in the dashboard.
 *
 *   2. rootCommit was set once, from `git rev-parse HEAD`, and never updated from
 *      the Investigator's actual findings — job fe6a69a8's investigation ranked
 *      commit 7f8e49eb at 0.8 confidence and bbda67f8 (HEAD) at 0.0, yet the job
 *      recorded bbda67f8 as its root cause.
 *
 * No Spring context is started: OrchestratorService's dependencies are all
 * constructor-injected except RestTemplate, which is reached via reflection so a
 * MockRestServiceServer can be bound to it — this keeps the test fast and focused
 * on the service's own logic rather than Spring wiring.
 */
class OrchestratorServiceTest {

    private AgentJobRepository jobRepository;
    private PrOutcomeRepository prOutcomeRepository;
    private JobEventRepository jobEventRepository;
    private EntityManager entityManager;
    private OrchestratorService service;
    private MockRestServiceServer mockGitHubBot;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository = mock(AgentJobRepository.class);
        prOutcomeRepository = mock(PrOutcomeRepository.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        entityManager = mock(EntityManager.class);
        jobEventRepository = mock(JobEventRepository.class);
        // The real state service over mocked repositories: these tests assert on
        // the job's resulting state, which is exactly what it decides.
        JobStateService jobState = new JobStateService(jobRepository, jobEventRepository,
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-29T12:00:00Z"), java.time.ZoneOffset.UTC));

        service = new OrchestratorService(jobRepository, kafkaTemplate, workspaceService,
                                           entityManager, prOutcomeRepository, jobState,
                                           new EscalationService(jobState, entityManager));

        // OrchestratorService constructs its own `new RestTemplate()` rather than
        // taking one as a constructor argument, so the only way to intercept the
        // GitHub Bot call without starting a real HTTP server is to reach that
        // instance via reflection and bind Spring's MockRestServiceServer to it.
        Field restTemplateField = OrchestratorService.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        RestTemplate restTemplate = (RestTemplate) restTemplateField.get(service);
        mockGitHubBot = MockRestServiceServer.createServer(restTemplate);
    }

    private AgentJob jobWith(UUID id, String state, String prUrl) {
        AgentJob job = new AgentJob();
        job.setId(id);
        job.setState(state);
        job.setPrUrl(prUrl);
        job.setRepo("https://github.com/bettercallok/chillcall");
        job.setErrorId("test-error");
        return job;
    }

    // ── handleTestsPassed idempotency ──────────────────────────────────────

    @Test
    void ignoresDuplicateDeliveryForAJobThatAlreadyHasAPr() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "PR_OPENED", "https://github.com/bettercallok/chillcall/pull/6");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        // No MockRestServiceServer expectation is registered. If the idempotency
        // guard fails to short-circuit, the resulting HTTP call has nowhere to go
        // and MockRestServiceServer throws — so the test fails loudly rather than
        // silently passing on the wrong behavior.
        service.handleTestsPassed(Map.of("jobId", jobId.toString(), "patch", "diff"));

        mockGitHubBot.verify();
        assertThat(job.getState()).isEqualTo("PR_OPENED");
        assertThat(job.getPrUrl()).isEqualTo("https://github.com/bettercallok/chillcall/pull/6");
        verifyNoInteractions(prOutcomeRepository);
    }

    @Test
    void recordsSuccessOnFirstDeliveryForAFreshJob() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        mockGitHubBot.expect(requestTo("http://localhost:8085/pull-request"))
            .andRespond(withSuccess(
                "{\"success\":true,\"prUrl\":\"https://github.com/bettercallok/chillcall/pull/9\"}",
                MediaType.APPLICATION_JSON));

        service.handleTestsPassed(Map.of("jobId", jobId.toString(), "patch", "diff"));

        mockGitHubBot.verify();
        assertThat(job.getState()).isEqualTo("PR_OPENED");
        assertThat(job.getPrUrl()).isEqualTo("https://github.com/bettercallok/chillcall/pull/9");
        verify(jobRepository).save(job);
    }

    @Test
    void escalatesAFreshJobWhenGitHubBotReportsFailure() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        mockGitHubBot.expect(requestTo("http://localhost:8085/pull-request"))
            .andRespond(withSuccess(
                "{\"success\":false,\"error\":\"Repository name must be in format owner/repo\"}",
                MediaType.APPLICATION_JSON));

        service.handleTestsPassed(Map.of("jobId", jobId.toString(), "patch", "diff"));

        assertThat(job.getState()).isEqualTo("ESCALATED");
        assertThat(job.getPrUrl()).isNull();
    }

    @Test
    void escalatesAFreshJobWhenGitHubBotIsUnreachable() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        mockGitHubBot.expect(requestTo("http://localhost:8085/pull-request"))
            .andRespond(withServerError());

        service.handleTestsPassed(Map.of("jobId", jobId.toString(), "patch", "diff"));

        assertThat(job.getState()).isEqualTo("ESCALATED");
    }

    // ── escalateUnlessAlreadySucceeded: the last line of defense ──────────

    @Test
    void escalateUnlessAlreadySucceededNeverDowngradesAJobThatAlreadyHasAPr() {
        AgentJob job = jobWith(UUID.randomUUID(), "PR_OPENED",
                                "https://github.com/bettercallok/chillcall/pull/6");

        service.escalateUnlessAlreadySucceeded(job, "a late failure arriving after success");

        assertThat(job.getState()).isEqualTo("PR_OPENED");
        assertThat(job.getPrUrl()).isEqualTo("https://github.com/bettercallok/chillcall/pull/6");
    }

    @Test
    void escalateUnlessAlreadySucceededEscalatesAJobWithNoPr() {
        AgentJob job = jobWith(UUID.randomUUID(), "TESTING", null);

        service.escalateUnlessAlreadySucceeded(job, "guardrails rejected the patch");

        assertThat(job.getState()).isEqualTo("ESCALATED");
    }

    // ── rankedCause: promoting the Investigator's finding to root cause ───

    @Test
    void rankedCausePicksTheHighestScoringCommit() {
        Map<String, Object> investigation = Map.of(
            "ranked_causes", java.util.List.of(
                Map.of("commit_sha", "bbda67f8813fd964fa65d45100c7ce9253377f5f", "causal_effect_score", 0.0),
                Map.of("commit_sha", "7f8e49eb8fa66bb83ae8d779010165a67c4614c4", "causal_effect_score", 0.9)
            )
        );

        Optional<OrchestratorService.RankedCause> result = service.rankedCause(investigation);

        assertThat(result).isPresent();
        assertThat(result.get().sha()).isEqualTo("7f8e49eb8fa66bb83ae8d779010165a67c4614c4");
        assertThat(result.get().score()).isEqualTo(0.9);
    }

    @Test
    void rankedCauseReturnsEmptyWhenRankedCausesIsMissing() {
        assertThat(service.rankedCause(Map.of("narrative", "no causes found"))).isEmpty();
    }

    @Test
    void rankedCauseReturnsEmptyWhenRankedCausesIsEmpty() {
        assertThat(service.rankedCause(Map.of("ranked_causes", java.util.List.of()))).isEmpty();
    }

    @Test
    void rankedCauseSkipsEntriesWithNoCommitSha() {
        Map<String, Object> investigation = Map.of(
            "ranked_causes", java.util.List.of(
                Map.of("causal_effect_score", 0.95),   // no commit_sha — must be ignored
                Map.of("commit_sha", "7f8e49eb8fa66bb83ae8d779010165a67c4614c4", "causal_effect_score", 0.3)
            )
        );

        Optional<OrchestratorService.RankedCause> result = service.rankedCause(investigation);

        assertThat(result).isPresent();
        assertThat(result.get().sha()).isEqualTo("7f8e49eb8fa66bb83ae8d779010165a67c4614c4");
    }

    @Test
    void rankedCauseReturnsEmptyWhenInvestigationIsNotAMap() {
        assertThat(service.rankedCause("not a map")).isEmpty();
        assertThat(service.rankedCause(null)).isEmpty();
    }

    // ── handleFixGenerated: guardrails must be checked against authorizedFiles,
    //    not the fixer's own filesModified self-report (C4) ─────────────────

    @Test
    void handleFixGeneratedSendsAuthorizedFilesToGuardrailsNotFilesModified() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        // filesModified (the LLM's own claim) deliberately differs from
        // authorizedFiles (resolved before the LLM ran) — a compromised or
        // manipulated fixer completion could claim to have only touched an
        // authorized file while filesModified says otherwise; guardrails must
        // be validated against authorizedFiles regardless of what
        // filesModified says. Respond with a server error so the method
        // returns right after this call, without needing a second mocked
        // expectation for the test-runner call that would otherwise follow.
        mockGitHubBot.expect(requestTo("http://localhost:9006/validate/patch"))
            .andExpect(content().string(containsString("UserService.java")))
            .andExpect(content().string(not(containsString("evil-payload.sh"))))
            .andRespond(withServerError());

        service.handleFixGenerated(Map.of(
            "jobId", jobId.toString(),
            "patch", "diff",
            "filesModified", "evil-payload.sh",
            "authorizedFiles", "src/main/java/com/example/UserService.java"
        ));

        mockGitHubBot.verify();
    }

    @Test
    void handleFixGeneratedSendsEmptyAllowedFilesWhenAuthorizedFilesIsMissing() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        // No authorizedFiles at all (e.g. an older fixer build, or one that
        // couldn't resolve anything) must still mean deny-all — not "trust
        // whatever the diff touches", and not a fallback to filesModified.
        mockGitHubBot.expect(requestTo("http://localhost:9006/validate/patch"))
            .andExpect(content().string(containsString("\"allowed_files\":[]")))
            .andRespond(withServerError());

        service.handleFixGenerated(Map.of(
            "jobId", jobId.toString(),
            "patch", "diff",
            "filesModified", "src/main/java/com/example/UserService.java"
        ));

        mockGitHubBot.verify();
    }

    @Test
    void handleFixGeneratedPersistsAuthorizedFilesOnTheJob() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        mockGitHubBot.expect(requestTo("http://localhost:9006/validate/patch"))
            .andRespond(withServerError());

        service.handleFixGenerated(Map.of(
            "jobId", jobId.toString(),
            "patch", "diff",
            "authorizedFiles", "src/main/java/com/example/UserService.java"
        ));

        assertThat(job.getAuthorizedFiles()).isEqualTo("src/main/java/com/example/UserService.java");
    }

    // ── Honest states: PLANNING / FIXING / reasons (job timeline) ─────────

    private String planEvent(UUID jobId, String investigationJson) {
        return "{\"job_id\":\"" + jobId + "\",\"investigation_result\":" + investigationJson + "}";
    }

    @Test
    void investigationResultMovesTheJobToPlanningAndSetsTheRootCause() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "INVESTIGATING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.persistInvestigationResult(planEvent(jobId,
            "{\"ranked_causes\":[{\"commit_sha\":\"7f8e49eb8fa66bb8\",\"causal_effect_score\":0.8}]}"));

        assertThat(job.getState()).isEqualTo("PLANNING");
        assertThat(job.getRootCommit()).isEqualTo("7f8e49eb8fa66bb8");
        assertThat(job.getStateReason()).contains("7f8e49eb");
        assertThat(job.getStateChangedAt()).isNotNull();
    }

    @Test
    void investigationWithNoRankedCauseLeavesRootCommitNullInsteadOfAPlaceholder() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "INVESTIGATING", null);
        job.setBaseCommit("headheadheadhead");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.persistInvestigationResult(planEvent(jobId, "{\"narrative\":\"nothing found\"}"));

        assertThat(job.getRootCommit()).isNull();
        assertThat(job.getState()).isEqualTo("PLANNING");
    }

    @Test
    void fixDispatchMovesAPlanningJobToFixing() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "PLANNING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.markFixing("{\"job_id\":\"" + jobId + "\",\"plan\":{\"strategy\":\"surgical_patch\"}}");

        assertThat(job.getState()).isEqualTo("FIXING");
        assertThat(job.getStateReason()).contains("surgical_patch");
    }

    @Test
    void aLateFixDispatchNeverPullsATestingJobBackToFixing() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.markFixing("{\"job_id\":\"" + jobId + "\",\"plan\":{\"strategy\":\"surgical_patch\"}}");

        assertThat(job.getState()).isEqualTo("TESTING");
    }

    @Test
    void eachGeneratedFixCountsAsAnAttempt() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "FIXING", null);
        job.setAttempts(1);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockGitHubBot.expect(requestTo("http://localhost:9006/validate/patch")).andRespond(withServerError());

        service.handleFixGenerated(Map.of("jobId", jobId.toString(), "patch", "diff"));

        assertThat(job.getAttempts()).isEqualTo(2);
    }

    @Test
    void guardrailsRejectionFailsTheJobWithTheirReason() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "FIXING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockGitHubBot.expect(requestTo("http://localhost:9006/validate/patch"))
            .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                .body("{\"detail\":\"unauthorized file: evil.sh\"}")
                .contentType(MediaType.APPLICATION_JSON));

        service.handleFixGenerated(Map.of("jobId", jobId.toString(), "patch", "diff"));

        assertThat(job.getState()).isEqualTo("FAILED");
        assertThat(job.getStateReason()).startsWith("Guardrails rejected").contains("evil.sh");
    }

    @Test
    void aTestRunnerErrorFailsTheJobInsteadOfLeavingItInTesting() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "FIXING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockGitHubBot.expect(requestTo("http://localhost:9006/validate/patch"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        mockGitHubBot.expect(requestTo("http://localhost:8084/test")).andRespond(withServerError());

        service.handleFixGenerated(Map.of("jobId", jobId.toString(), "patch", "diff"));

        assertThat(job.getState()).isEqualTo("FAILED");
        assertThat(job.getStateReason()).startsWith("Test runner");
    }

    @Test
    void aLateEscalationNeverOverwritesAJobWhosePrAlreadyOpened() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "PR_OPENED", "https://github.com/bettercallok/chillcall/pull/6");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.handleJobEscalated(Map.of("jobId", jobId.toString(), "reason", "TOKEN_BUDGET_EXCEEDED"));

        assertThat(job.getState()).isEqualTo("PR_OPENED");
        verify(entityManager, never()).persist(any());
    }

    @Test
    void anEscalationRecordsItsReasonOnTheJob() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "FIXING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.handleJobEscalated(Map.of("jobId", jobId.toString(), "reason", "Planner Agent failed: boom"));

        assertThat(job.getState()).isEqualTo("ESCALATED");
        assertThat(job.getStateReason()).isEqualTo("Planner Agent failed: boom");
        verify(entityManager).persist(any());
    }

    @Test
    void aFailedRegenerationOfAJobWithAnOpenPrReturnsItToPrOpenedInsteadOfEscalating() {
        // Job 9e09ccf6: "regenerate" on a PR_OPENED job, the second cycle failed,
        // and the job was queued for approval — which would re-run the pipeline
        // against a job whose fix was already open as a PR.
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "FIXING", "https://github.com/bettercallok/chillcall/pull/18");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.handleJobEscalated(Map.of("jobId", jobId.toString(),
            "reason", "Fixer Agent exhausted all attempts and could not fix the bug."));

        assertThat(job.getState()).isEqualTo("PR_OPENED");
        assertThat(job.getStateReason())
            .startsWith("Regeneration failed — Fixer Agent exhausted all attempts")
            .endsWith("pull/18");
        verify(entityManager, never()).persist(any());
    }

    @Test
    void anEscalationWithNoConfidenceStoresNullNotZero() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "PLANNING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.handleJobEscalated(Map.of("jobId", jobId.toString(), "reason", "Planner Agent failed: 429"));

        org.mockito.ArgumentCaptor<ai.gitoracle.core.entity.Escalation> escalation =
            org.mockito.ArgumentCaptor.forClass(ai.gitoracle.core.entity.Escalation.class);
        verify(entityManager).persist(escalation.capture());
        assertThat(escalation.getValue().getConfidenceScore()).isNull();
    }

    @Test
    void anEscalationKeepsAConfidenceTheAgentReported() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "FIXING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.handleJobEscalated(Map.of("jobId", jobId.toString(), "reason", "exhausted", "confidenceScore", 0.42));

        org.mockito.ArgumentCaptor<ai.gitoracle.core.entity.Escalation> escalation =
            org.mockito.ArgumentCaptor.forClass(ai.gitoracle.core.entity.Escalation.class);
        verify(entityManager).persist(escalation.capture());
        assertThat(escalation.getValue().getConfidenceScore()).isEqualTo(0.42);
    }
}
