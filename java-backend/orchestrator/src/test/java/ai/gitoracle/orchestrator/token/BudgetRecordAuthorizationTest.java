package ai.gitoracle.orchestrator.token;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.security.TestTenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /api/v1/budget/{jobId}/record is how agents report LLM usage, and is
 * also reachable through the gateway. It had no ownership check, so a tenant
 * could post a huge count against another tenant's (publicly visible) job id
 * and get that job escalated as TOKEN_BUDGET_EXCEEDED.
 */
class BudgetRecordAuthorizationTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID DEFAULT = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private TokenBudgetManager budget;
    private AgentJobRepository jobs;
    private BudgetController controller;
    private ai.gitoracle.orchestrator.repository.JobPromptVersionRepository promptVersions;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        budget = mock(TokenBudgetManager.class);
        jobs = mock(AgentJobRepository.class);
        promptVersions = mock(ai.gitoracle.orchestrator.repository.JobPromptVersionRepository.class);
        controller = new BudgetController(budget, jobs, promptVersions);
        jobId = UUID.randomUUID();
        AgentJob job = new AgentJob();
        job.setId(jobId);
        job.setTenantId(OWNER);
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
    }

    @AfterEach
    void tearDown() {
        TestTenantContext.clear();
    }

    private static RecordUsageRequest usage(int tokens) {
        RecordUsageRequest r = new RecordUsageRequest();
        r.setTokensUsed(tokens);
        r.setAgentName("fixer_agent");
        return r;
    }

    @Test
    void anotherTenantCannotRecordUsageAgainstAJobItDoesNotOwn() {
        TestTenantContext.set(OTHER);
        var response = controller.recordUsage(jobId, usage(999_999));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(budget, never()).recordUsage(any(), anyInt());
    }

    @Test
    void theOwningTenantCan() {
        TestTenantContext.set(OWNER);
        assertThat(controller.recordUsage(jobId, usage(500)).getStatusCode().value()).isEqualTo(200);
        verify(budget).recordUsage(jobId, 500);
    }

    @Test
    void anInternalAgentCanRecordForAnyTenantsJob() {
        TestTenantContext.setInternalCaller(DEFAULT);
        assertThat(controller.recordUsage(jobId, usage(500)).getStatusCode().value()).isEqualTo(200);
        verify(budget).recordUsage(jobId, 500);
    }

    /** "fixer_agent" (how agents report usage) is the "fixer" row (how they report prompt versions). */
    @Test
    void usageIsAlsoAddedToTheAgentsOwnPromptVersionRow() {
        TestTenantContext.setInternalCaller(DEFAULT);
        controller.recordUsage(jobId, usage(500));
        verify(promptVersions).addTokens(jobId, "fixer", 500);
    }

    @Test
    void negativeOrAbsurdCountsAreRejected() {
        TestTenantContext.setInternalCaller(DEFAULT);
        assertThat(controller.recordUsage(jobId, usage(-1_000_000)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.recordUsage(jobId, usage(Integer.MAX_VALUE)).getStatusCode().value()).isEqualTo(400);
        verify(budget, never()).recordUsage(any(), anyInt());
    }
}
