package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.OrchestratorService;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * chillcall#18 was closed without merging and job 9e09ccf6 still said "PR
 * opened": GitHub's webhook can't reach a local deployment, so nothing ever
 * reported it. The reconciler asks GitHub instead.
 */
class PrStatusReconcilerTest {

    private static final String PR = "https://github.com/bettercallok/chillcall/pull/18";
    private static final String STATUS_URL =
        "http://bot/pull-request/status?url=https://github.com/bettercallok/chillcall/pull/18&installationId=55";

    private AgentJobRepository jobRepository;
    private OrchestratorService orchestrator;
    private MockRestServiceServer github;
    private PrStatusReconciler reconciler;
    private AgentJob job;

    @BeforeEach
    void setUp() {
        jobRepository = mock(AgentJobRepository.class);
        orchestrator = mock(OrchestratorService.class);
        RestTemplate rest = new RestTemplate();
        github = MockRestServiceServer.createServer(rest);
        GitHubInstallations installations = mock(GitHubInstallations.class);
        org.mockito.Mockito.when(installations.require(org.mockito.ArgumentMatchers.any())).thenReturn("55");
        reconciler = new PrStatusReconciler(jobRepository, orchestrator, installations, rest, "http://bot", "secret");

        job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setState("PR_OPENED");
        job.setPrUrl(PR);
        when(jobRepository.findByState("PR_OPENED")).thenReturn(List.of(job));
    }

    private void githubSays(String json) {
        github.expect(requestTo(STATUS_URL))
            .andExpect(header("X-Internal-Token", "secret"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void aClosedUnmergedPrIsRecordedAsClosed() {
        githubSays("{\"state\":\"closed\",\"merged\":false,\"by\":\"bettercallok\"}");

        reconciler.reconcileOpenPullRequests();

        github.verify();
        verify(orchestrator).recordPrOutcome(job.getId(), "CLOSED", "bettercallok", PR);
    }

    @Test
    void aMergedPrIsRecordedAsMerged() {
        githubSays("{\"state\":\"closed\",\"merged\":true,\"by\":\"bettercallok\"}");

        reconciler.reconcileOpenPullRequests();

        verify(orchestrator).recordPrOutcome(job.getId(), "MERGED", "bettercallok", PR);
    }

    @Test
    void anOpenPrIsLeftAlone() {
        githubSays("{\"state\":\"open\",\"merged\":false,\"by\":null}");

        reconciler.reconcileOpenPullRequests();

        verify(orchestrator, never()).recordPrOutcome(any(), anyString(), anyString(), anyString());
    }

    @Test
    void oneUnreachablePrDoesNotStopTheOthers() {
        AgentJob other = new AgentJob();
        other.setId(UUID.randomUUID());
        other.setState("PR_OPENED");
        other.setPrUrl("https://github.com/bettercallok/chillcall/pull/19");
        when(jobRepository.findByState("PR_OPENED")).thenReturn(List.of(job, other));
        github.expect(requestTo(STATUS_URL)).andRespond(withServerError());
        github.expect(requestTo("http://bot/pull-request/status?url=https://github.com/bettercallok/chillcall/pull/19&installationId=55"))
            .andRespond(withSuccess("{\"state\":\"closed\",\"merged\":true,\"by\":\"x\"}", MediaType.APPLICATION_JSON));

        reconciler.reconcileOpenPullRequests();

        verify(orchestrator).recordPrOutcome(eq(other.getId()), eq("MERGED"), eq("x"), anyString());
    }

    @Test
    void aJobWithNoPrUrlIsNotAsked() {
        job.setPrUrl(null);

        reconciler.reconcileOpenPullRequests();

        github.verify();   // no request expected, none made
    }
}
