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
    private ai.gitoracle.orchestrator.repository.EscalationRepository escalationRows;
    private OrchestratorService service;
    private ai.gitoracle.orchestrator.service.RepoAccessService repoAccess;
    private MockRestServiceServer mockGitHubBot;
    private KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository = mock(AgentJobRepository.class);
        prOutcomeRepository = mock(PrOutcomeRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        entityManager = mock(EntityManager.class);
        escalationRows = mock(ai.gitoracle.orchestrator.repository.EscalationRepository.class);
        jobEventRepository = mock(JobEventRepository.class);
        // The real state service over mocked repositories: these tests assert on
        // the job's resulting state, which is exactly what it decides.
        JobStateService jobState = new JobStateService(jobRepository, jobEventRepository,
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-29T12:00:00Z"), java.time.ZoneOffset.UTC));

        repoAccess = mock(ai.gitoracle.orchestrator.service.RepoAccessService.class);
        when(repoAccess.isAllowed(any(), any())).thenReturn(true);
        service = new OrchestratorService(jobRepository, kafkaTemplate, workspaceService,
                                           entityManager, prOutcomeRepository, jobState,
                                           new EscalationService(jobState, entityManager, prOutcomeRepository, escalationRows),
                                           repoAccess,
                                           mock(ai.gitoracle.orchestrator.service.GitHubInstallations.class, invocation ->
                                               invocation.getMethod().getReturnType() == java.util.Optional.class ? java.util.Optional.of("1") : "1"),
                                           mock(ai.gitoracle.orchestrator.service.UsageLimits.class, invocation -> java.util.Optional.empty()));

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
        // ...and queued for a human. This used to be a bare state change with no
        // Escalation row: escalated on the jobs page, absent from the queue.
        var row = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(entityManager).persist(row.capture());
        assertThat(row.getValue()).isInstanceOf(ai.gitoracle.core.entity.Escalation.class);
        ai.gitoracle.core.entity.Escalation escalation = (ai.gitoracle.core.entity.Escalation) row.getValue();
        assertThat(escalation.getStatus()).isEqualTo("PENDING");
        assertThat(escalation.getReason()).startsWith("PR creation failed: Repository name must be");
        verify(escalationRows).supersedePending(org.mockito.ArgumentMatchers.eq(jobId), any());
    }

    @Test
    void anEscalatedJobWithNoPendingRowGetsOneButOneWithARowIsLeftAlone() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "ESCALATED", null);
        EscalationService escalationService = new EscalationService(
            new JobStateService(jobRepository, jobEventRepository, java.time.Clock.systemUTC()),
            entityManager, prOutcomeRepository, escalationRows);

        when(escalationRows.existsByJob_IdAndStatus(jobId, "PENDING")).thenReturn(true);
        assertThat(escalationService.escalate(job, "again", "watchdog", null)).isFalse();
        verify(entityManager, never()).persist(any());

        when(escalationRows.existsByJob_IdAndStatus(jobId, "PENDING")).thenReturn(false);
        assertThat(escalationService.escalate(job, "PR creation failed: boom", "orchestrator", null)).isTrue();
        verify(entityManager).persist(any(ai.gitoracle.core.entity.Escalation.class));
        assertThat(job.getState()).isEqualTo("ESCALATED");
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

    // ── regenerated fixes on a job that already has a PR ──────────────────

    @Test
    void aRegeneratedFixThatPassedTestsUpdatesTheExistingPr() {
        // Job 9e09ccf6: the regenerated fix passed every test and was then
        // dropped as a "duplicate delivery" because the job already had a PR.
        UUID jobId = UUID.randomUUID();
        String pr = "https://github.com/bettercallok/chillcall/pull/18";
        AgentJob job = jobWith(jobId, "TESTING", pr);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        mockGitHubBot.expect(requestTo("http://localhost:8085/pull-request"))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                .jsonPath("$.existingPrUrl").value(pr))
            .andRespond(withSuccess("{\"success\":true,\"prUrl\":\"" + pr + "\"}", MediaType.APPLICATION_JSON));

        service.handleTestsPassed(Map.of("jobId", jobId.toString(), "patch", "diff", "isRegeneration", "true"));

        mockGitHubBot.verify();
        assertThat(job.getState()).isEqualTo("PR_OPENED");
        assertThat(job.getPrUrl()).isEqualTo(pr);
        assertThat(job.getStateReason()).startsWith("Pull request updated with the regenerated fix");
    }

    @Test
    void aRegeneratedFixWhoseOldPrWasClosedRecordsTheNewPr() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", "https://github.com/bettercallok/chillcall/pull/18");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        mockGitHubBot.expect(requestTo("http://localhost:8085/pull-request"))
            .andRespond(withSuccess(
                "{\"success\":true,\"prUrl\":\"https://github.com/bettercallok/chillcall/pull/25\"}",
                MediaType.APPLICATION_JSON));

        service.handleTestsPassed(Map.of("jobId", jobId.toString(), "patch", "diff"));

        assertThat(job.getPrUrl()).isEqualTo("https://github.com/bettercallok/chillcall/pull/25");
        assertThat(job.getStateReason()).startsWith("Pull request opened");
    }

    @Test
    void aFailedUpdateOfARegeneratedFixReturnsTheJobToPrOpenedWithTheReason() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", "https://github.com/bettercallok/chillcall/pull/18");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        mockGitHubBot.expect(requestTo("http://localhost:8085/pull-request"))
            .andRespond(withSuccess("{\"success\":false,\"error\":\"push rejected\"}", MediaType.APPLICATION_JSON));

        service.handleTestsPassed(Map.of("jobId", jobId.toString(), "patch", "diff"));

        assertThat(job.getState()).isEqualTo("PR_OPENED");
        assertThat(job.getPrUrl()).isEqualTo("https://github.com/bettercallok/chillcall/pull/18");
        assertThat(job.getStateReason()).contains("updating the pull request failed: push rejected");
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
    void aTestRunnerTimeoutFailsTheJobAndSaysItTimedOut() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "FIXING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockGitHubBot.expect(requestTo("http://localhost:9006/validate/patch"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        mockGitHubBot.expect(requestTo("http://localhost:8084/test")).andRespond(request -> {
            throw new java.net.SocketTimeoutException("Read timed out");
        });

        service.handleFixGenerated(Map.of("jobId", jobId.toString(), "patch", "diff"));

        assertThat(job.getState()).isEqualTo("FAILED");
        assertThat(job.getStateReason()).isEqualTo("Test runner timed out after 10 minutes");
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

    // ── what a human did with the PR ──────────────────────────────────────

    @Test
    void aPrClosedWithoutMergingMovesTheJobToPrClosed() {
        // chillcall#18 was closed without merging and job 9e09ccf6 kept showing
        // "PR opened": the outcome went into pr_outcomes only.
        UUID jobId = UUID.randomUUID();
        String pr = "https://github.com/bettercallok/chillcall/pull/18";
        AgentJob job = jobWith(jobId, "PR_OPENED", pr);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.handlePrOutcome(Map.of("jobId", jobId.toString(), "type", "PR_CLOSED",
                                       "reviewer", "bettercallok", "prUrl", pr));

        assertThat(job.getState()).isEqualTo("PR_CLOSED");
        assertThat(job.getStateReason()).isEqualTo("Pull request closed without merging by bettercallok: " + pr);
        verify(prOutcomeRepository).save(any());
    }

    @Test
    void aMergedPrMovesTheJobToPrMerged() {
        UUID jobId = UUID.randomUUID();
        String pr = "https://github.com/bettercallok/chillcall/pull/18";
        AgentJob job = jobWith(jobId, "PR_OPENED", pr);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.recordPrOutcome(jobId, "MERGED", "bettercallok", pr);

        assertThat(job.getState()).isEqualTo("PR_MERGED");
    }

    @Test
    void anOutcomeAlreadyRecordedForThatPrChangesNothing() {
        UUID jobId = UUID.randomUUID();
        String pr = "https://github.com/bettercallok/chillcall/pull/18";
        AgentJob job = jobWith(jobId, "PR_OPENED", pr);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(prOutcomeRepository.existsByJobIdAndOutcomeAndPrUrl(jobId, "CLOSED", pr)).thenReturn(true);

        service.recordPrOutcome(jobId, "CLOSED", "bettercallok", pr);

        assertThat(job.getState()).isEqualTo("PR_OPENED");
        verify(prOutcomeRepository, never()).save(any());
    }

    @Test
    void closingAnOlderPrIsRecordedButLeavesAJobWithANewerPrAlone() {
        // After a regeneration opened a new PR, the old one being closed is history.
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "PR_OPENED", "https://github.com/bettercallok/chillcall/pull/25");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.recordPrOutcome(jobId, "CLOSED", "bettercallok", "https://github.com/bettercallok/chillcall/pull/18");

        verify(prOutcomeRepository).save(any());
        assertThat(job.getState()).isEqualTo("PR_OPENED");
    }

    @Test
    void anOutcomeFromAPrOnAnotherRepositoryIsNotRecordedAgainstTheJob() {
        // The job id marker is public; anyone can open and close a PR elsewhere carrying it.
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "PR_OPENED", "https://github.com/bettercallok/chillcall/pull/25");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.recordPrOutcome(jobId, "CLOSED", "stranger", "https://github.com/stranger/fork/pull/1");

        verify(prOutcomeRepository, never()).save(any());
        assertThat(job.getState()).isEqualTo("PR_OPENED");
    }

    @Test
    void anApprovalIsRecordedWithoutChangingTheJobsState() {
        UUID jobId = UUID.randomUUID();
        String pr = "https://github.com/bettercallok/chillcall/pull/18";
        AgentJob job = jobWith(jobId, "PR_OPENED", pr);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.recordPrOutcome(jobId, "APPROVED", "reviewer", pr);

        verify(prOutcomeRepository).save(any());
        assertThat(job.getState()).isEqualTo("PR_OPENED");
    }

    @Test
    void aFailedRegenerationOfAJobWhosePrWasClosedReturnsItToPrClosedNotPrOpened() {
        UUID jobId = UUID.randomUUID();
        String pr = "https://github.com/bettercallok/chillcall/pull/18";
        AgentJob job = jobWith(jobId, "FIXING", pr);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(prOutcomeRepository.existsByJobIdAndOutcomeAndPrUrl(jobId, "CLOSED", pr)).thenReturn(true);

        service.handleJobEscalated(Map.of("jobId", jobId.toString(), "reason", "Fixer Agent exhausted all attempts."));

        assertThat(job.getState()).isEqualTo("PR_CLOSED");
        verify(entityManager, never()).persist(any());
    }

    // ── the PR description carries the job's real numbers ─────────────────

    private static org.springframework.test.web.client.RequestMatcher json(String path, Object value) {
        return org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(path).value(value);
    }

    @Test
    void thePrRequestCarriesTheJobsRealScoreTokensTestCountsAndRootCause() {
        // Job 9e09ccf6's PR said "Causal Confidence: 95%" while its investigator
        // had scored 0.90 — the 0.95 was a constant, as were "1/1 passed" and
        // 500 tokens, and the root-cause section read "null".
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", null);
        job.setCausalScore(0.9);
        job.setTokenBudgetUsed(5365);
        job.setInvestigationResult("{\"narrative\": \"The NPE comes from an unchecked user.\"}");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        mockGitHubBot.expect(requestTo("http://localhost:8085/pull-request"))
            .andExpect(json("$.causalScore", 0.9))
            .andExpect(json("$.tokenBudgetUsed", 5365))
            .andExpect(json("$.testsTotal", 6))
            .andExpect(json("$.testsPassed", 5))
            .andExpect(json("$.causalChainNarrative", "The NPE comes from an unchecked user."))
            .andRespond(withSuccess("{\"success\":true,\"prUrl\":\"https://github.com/o/r/pull/1\"}",
                                    MediaType.APPLICATION_JSON));

        service.handleTestsPassed(Map.of("jobId", jobId.toString(), "patch", "diff",
                                         "testsTotal", "6", "testsFailed", "1"));

        mockGitHubBot.verify();
    }

    @Test
    void aDirectFixJobSendsNoCausalScoreAndUnknownTestCountsRatherThanMadeUpOnes() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", null);   // never investigated: no causal score
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        mockGitHubBot.expect(requestTo("http://localhost:8085/pull-request"))
            .andExpect(json("$.causalScore", null))
            .andExpect(json("$.testsTotal", null))
            .andExpect(json("$.testsPassed", null))
            .andExpect(json("$.causalChainNarrative", null))
            .andRespond(withSuccess("{\"success\":true,\"prUrl\":\"https://github.com/o/r/pull/1\"}",
                                    MediaType.APPLICATION_JSON));

        service.handleTestsPassed(Map.of("jobId", jobId.toString(), "patch", "diff"));

        mockGitHubBot.verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTestRunnersCountsAreForwardedOnTheTestsPassedEvent() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "FIXING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockGitHubBot.expect(requestTo("http://localhost:9006/validate/patch"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        mockGitHubBot.expect(requestTo("http://localhost:8084/test"))
            .andRespond(withSuccess("{\"allPassed\":true,\"qualityScore\":1.0,\"coverageDelta\":0.0,"
                + "\"testsTotal\":6,\"testsFailed\":0}", MediaType.APPLICATION_JSON));

        service.handleFixGenerated(Map.of("jobId", jobId.toString(), "patch", "diff"));

        org.mockito.ArgumentCaptor<Object> payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(ai.gitoracle.core.kafka.KafkaTopics.TESTS_PASSED),
                                   payload.capture());
        Map<String, String> event = (Map<String, String>) payload.getValue();
        assertThat(event).containsEntry("testsTotal", "6").containsEntry("testsFailed", "0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSafePassWithNoTestSuiteIsMarkedUnverifiedOnTheTestsPassedEvent() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "FIXING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockGitHubBot.expect(requestTo("http://localhost:9006/validate/patch"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        mockGitHubBot.expect(requestTo("http://localhost:8084/test"))
            .andRespond(withSuccess("{\"allPassed\":true,\"verified\":false,\"qualityScore\":1.0}",
                MediaType.APPLICATION_JSON));

        service.handleFixGenerated(Map.of("jobId", jobId.toString(), "patch", "diff"));

        org.mockito.ArgumentCaptor<Object> payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(ai.gitoracle.core.kafka.KafkaTopics.TESTS_PASSED),
                                   payload.capture());
        assertThat((Map<String, String>) payload.getValue()).containsEntry("testsVerified", "false");
    }

    @Test
    void anUnverifiedFixSaysSoToGithubBotAndOnTheTimeline() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        mockGitHubBot.expect(requestTo("http://localhost:8085/pull-request"))
            .andExpect(json("$.testsVerified", false))
            .andExpect(json("$.qualityScore", null))
            .andExpect(json("$.coverageDelta", null))
            .andRespond(withSuccess("{\"success\":true,\"prUrl\":\"https://github.com/o/r/pull/1\"}",
                                    MediaType.APPLICATION_JSON));

        service.handleTestsPassed(Map.of("jobId", jobId.toString(), "patch", "diff",
                                         "testsVerified", "false", "qualityScore", "1.0"));

        mockGitHubBot.verify();
        assertThat(job.getState()).isEqualTo("PR_OPENED");
        assertThat(job.getStateReason())
            .isEqualTo("Pull request opened (no test suite ran — not verified): https://github.com/o/r/pull/1");
    }

    @Test
    void aVerifiedFixKeepsItsScoreAndAnOrdinaryReason() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "TESTING", null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        mockGitHubBot.expect(requestTo("http://localhost:8085/pull-request"))
            .andExpect(json("$.testsVerified", true))
            .andExpect(json("$.qualityScore", 0.5))
            .andRespond(withSuccess("{\"success\":true,\"prUrl\":\"https://github.com/o/r/pull/1\"}",
                                    MediaType.APPLICATION_JSON));

        // No testsVerified: an event from before the field existed.
        service.handleTestsPassed(Map.of("jobId", jobId.toString(), "patch", "diff", "qualityScore", "0.5"));

        mockGitHubBot.verify();
        assertThat(job.getStateReason()).isEqualTo("Pull request opened: https://github.com/o/r/pull/1");
    }

    // ── PR comments may only steer the job's own PR ────────────────────────

    /**
     * The job id marker is public (printed in every GitOracle PR body), so a
     * comment on some other repository's issue/PR carrying it must not reach
     * the reviewer, let alone re-point the job at that repository.
     */
    @Test
    void ignoresAReviewCommentThatWasNotMadeOnTheJobsOwnPullRequest() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "PR_OPENED", "https://github.com/bettercallok/chillcall/pull/6");
        job.setTenantId(UUID.randomUUID());
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        // No reviewer expectation registered: any HTTP call fails the test.
        service.handleReviewReceived(Map.of(
            "job_id", jobId.toString(),
            "comment", "Please also add my file",
            "repo", "https://github.com/attacker/elsewhere",
            "pr_url", "https://github.com/attacker/elsewhere/pull/1"));

        mockGitHubBot.verify();
        verify(kafkaTemplate, never()).send(any(String.class), any());
        assertThat(job.getState()).isEqualTo("PR_OPENED");
    }

    @Test
    void ignoresAReviewEventWithNoPullRequestUrl() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "PR_OPENED", "https://github.com/bettercallok/chillcall/pull/6");
        job.setTenantId(UUID.randomUUID());
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.handleReviewReceived(Map.of("job_id", jobId.toString(), "comment", "fix it"));

        mockGitHubBot.verify();
        verify(kafkaTemplate, never()).send(any(String.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCommentOnTheJobsOwnPrRegeneratesAgainstTheJobsRepoNotThePayloads() {
        UUID jobId = UUID.randomUUID();
        AgentJob job = jobWith(jobId, "PR_OPENED", "https://github.com/bettercallok/chillcall/pull/6");
        job.setTenantId(UUID.randomUUID());
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        mockGitHubBot.expect(requestTo("http://localhost:9003/review"))
            .andRespond(withSuccess("{\"action_needed\": true, \"stance\": \"agree\"}", MediaType.APPLICATION_JSON));

        service.handleReviewReceived(Map.of(
            "job_id", jobId.toString(),
            "comment", "Handle the null payment too",
            "repo", "https://github.com/attacker/elsewhere",
            "pr_url", "https://github.com/BetterCallOK/chillcall/pull/6"));

        mockGitHubBot.verify();
        var payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(ai.gitoracle.core.kafka.KafkaTopics.JOB_FIX),
                                   payload.capture());
        Map<String, Object> fix = (Map<String, Object>) payload.getValue();
        assertThat(fix).containsEntry("repo_url", "https://github.com/bettercallok/chillcall")
                       .containsEntry("target_repo", "bettercallok/chillcall");
    }

    // ─── Tenant repository allow-list (security audit D1) ─────────────────

    private AgentJob runIngest(ai.gitoracle.core.kafka.event.ErrorIngestedEvent event) {
        org.mockito.ArgumentCaptor<AgentJob> saved = org.mockito.ArgumentCaptor.forClass(AgentJob.class);
        service.handleErrorIngested(event);
        verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues().get(saved.getAllValues().size() - 1);
    }

    @Test
    void handleErrorIngested_failsAJobForARepoNotOnTheTenantsAllowList() {
        UUID tenant = UUID.randomUUID();
        when(repoAccess.isAllowed(tenant, "https://github.com/someone/private")).thenReturn(false);

        AgentJob job = runIngest(ai.gitoracle.core.kafka.event.ErrorIngestedEvent.builder()
            .tenantId(tenant).errorId("e1").repoUrl("https://github.com/someone/private").build());

        assertThat(job.getState()).isEqualTo("FAILED");
        assertThat(job.getStateReason()).contains("https://github.com/someone/private", "allow-list");
        verifyNoInteractions(kafkaTemplate);                          // nothing cloned or dispatched
    }

    @Test
    void handleErrorIngested_failsAJobWhosePrTargetIsNotAllowed() {
        UUID tenant = UUID.randomUUID();
        when(repoAccess.isAllowed(tenant, "someone/elsewhere")).thenReturn(false);

        AgentJob job = runIngest(ai.gitoracle.core.kafka.event.ErrorIngestedEvent.builder()
            .tenantId(tenant).errorId("e2").repoUrl("https://github.com/me/mine")
            .targetRepo("someone/elsewhere").build());

        assertThat(job.getState()).isEqualTo("FAILED");
        assertThat(job.getStateReason()).contains("someone/elsewhere");
        verifyNoInteractions(kafkaTemplate);
    }
}
